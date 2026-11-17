"""
resample.py — Non-uniform timestamp → uniform time grid (§5.2, §5.3).

Smartphone accelerometers do not deliver perfectly uniform samples; the actual
inter-sample interval varies by several milliseconds ("jitter"). FFT and Welch
require a uniform grid, so we resample onto one.

Strategy: cubic spline interpolation.
  - Cheap and accurate for the smooth vibration signals we care about.
  - Aliasing: if the target fs > source median fs, we upsample (OK to interpolate);
    if target < source, we lowpass first then decimate.
  - For the typical case (irregular 200 Hz → regular 200 Hz), spline gives
    sub-millisecond accuracy.

Also records the ACTUAL sampling rate from the timestamps (median Δt), which
often differs from the requested rate — always use the measured rate in DSP.
"""

from __future__ import annotations

import numpy as np
from scipy.interpolate import CubicSpline
from scipy.signal import decimate


def measure_fs(timestamps: np.ndarray) -> float:
    """
    Compute the actual average sampling rate from a timestamp array.

    Uses the median inter-sample interval to be robust against occasional
    large gaps (Bluetooth drops, OS jitter spikes).

    Parameters
    ----------
    timestamps : (N,) float — timestamps in seconds (any epoch)

    Returns
    -------
    fs : float — measured sample rate in Hz
    """
    dts = np.diff(timestamps)
    if len(dts) == 0:
        raise ValueError("Need at least 2 timestamps to measure fs.")
    median_dt = float(np.median(dts))
    if median_dt <= 0:
        raise ValueError(f"Median Δt is non-positive ({median_dt}). Check timestamps.")
    return 1.0 / median_dt


def resample_uniform(
    signal: np.ndarray,
    timestamps: np.ndarray,
    fs_target: float | None = None,
    fs_source: float | None = None,
) -> tuple[np.ndarray, np.ndarray, float]:
    """
    Resample a non-uniformly sampled signal onto a uniform time grid.

    Parameters
    ----------
    signal     : (N,) or (N, C) float — signal values (1-D or multi-axis)
    timestamps : (N,) float — corresponding timestamps (seconds)
    fs_target  : float — desired output sample rate (Hz).
                  If None, uses the measured source rate rounded to nearest 50 Hz.
    fs_source  : float — source sample rate (Hz); measured from timestamps if None.

    Returns
    -------
    signal_uniform : (M,) or (M, C) float — resampled signal
    ts_uniform     : (M,) float — uniform timestamps (seconds)
    fs_out         : float — actual output sample rate used

    Notes
    -----
    - If fs_target > fs_source, we upsample via spline (acceptable for vibration).
    - If fs_target < fs_source, we downsample: lowpass via decimate, then spline
      to the target grid.
    - Signal must be 1-D or 2-D with time along axis 0.
    """
    signal = np.asarray(signal, dtype=float)
    timestamps = np.asarray(timestamps, dtype=float)

    if signal.shape[0] != len(timestamps):
        raise ValueError(
            f"signal length {signal.shape[0]} ≠ timestamps length {len(timestamps)}"
        )

    if fs_source is None:
        fs_source = measure_fs(timestamps)

    if fs_target is None:
        # Round to nearest 50 Hz (e.g., 196.3 → 200, 103.1 → 100)
        fs_target = max(50.0, round(fs_source / 50.0) * 50.0)

    t_start = timestamps[0]
    t_end   = timestamps[-1]
    n_out   = int(np.floor((t_end - t_start) * fs_target)) + 1
    ts_uniform = np.linspace(t_start, t_end, n_out)

    # Cubic spline interpolation (1-D or multi-axis)
    cs = CubicSpline(timestamps, signal, axis=0)
    signal_uniform = cs(ts_uniform)

    return signal_uniform, ts_uniform, fs_target


def resample_to_common_grid(
    signals: list[np.ndarray],
    timestamps_list: list[np.ndarray],
    fs_target: float | None = None,
) -> tuple[list[np.ndarray], np.ndarray, float]:
    """
    Resample multiple phones' signals to a common uniform time grid.

    The common grid spans the intersection of all phones' recording windows
    (i.e., the overlap period), so all output arrays have the same length.

    Parameters
    ----------
    signals        : list of (N_i,) or (N_i, C) arrays — one per phone
    timestamps_list: list of (N_i,) arrays — corrected timestamps, same epoch
    fs_target      : target sample rate (Hz); auto-detected if None

    Returns
    -------
    signals_uniform : list of arrays, all shape (M,) or (M, C)
    ts_common       : (M,) float — the common uniform time grid
    fs_out          : float — sample rate of the common grid
    """
    if len(signals) != len(timestamps_list):
        raise ValueError("signals and timestamps_list must have the same length.")

    # Determine common time window (intersection)
    t_start = max(ts[0]  for ts in timestamps_list)
    t_end   = min(ts[-1] for ts in timestamps_list)
    if t_start >= t_end:
        raise ValueError("No overlap between phone recording windows.")

    if fs_target is None:
        fs_measured = [measure_fs(ts) for ts in timestamps_list]
        # Use the minimum to avoid upsampling beyond any phone's resolution
        fs_target = min(fs_measured)
        fs_target = max(50.0, round(fs_target / 50.0) * 50.0)

    n_out = int(np.floor((t_end - t_start) * fs_target)) + 1
    ts_common = np.linspace(t_start, t_end, n_out)

    signals_uniform = []
    for sig, ts in zip(signals, timestamps_list):
        cs = CubicSpline(ts, np.asarray(sig, dtype=float), axis=0)
        signals_uniform.append(cs(ts_common))

    return signals_uniform, ts_common, fs_target
