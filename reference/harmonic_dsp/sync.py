"""
sync.py — Multi-phone tap-synchronization.

Algorithm (§5.3)
----------------
1. High-pass filter both phones' magnitude signals.
2. Detect the impulse peak in each signal within a search window.
3. Refine to sub-sample accuracy with parabolic interpolation.
4. Estimate clock offset Δ₁ at tap-1, Δ₂ at tap-2.
5. Fit a linear clock model: corrected_t_B = t_B − (Δ₁ + ε·(t_B − t₁))
   where drift ε = (Δ₂ − Δ₁) / (t₂ − t₁).

Returns the corrected timestamps for phone B (and any additional phones)
relative to phone A's timebase.

Phase error budget
------------------
At 30 Hz, 1 ms timing error → 10.8° phase error.
At 60 Hz, 1 ms → 21.6°.
Target: < 1 ms offset after sub-sample interpolation.
"""

from __future__ import annotations

import numpy as np
from scipy.signal import butter, filtfilt


# ── Internal helpers ──────────────────────────────────────────────────────────

def _highpass_magnitude(
    accel: np.ndarray,
    fs: float,
    cutoff_hz: float = 5.0,
    order: int = 4,
) -> np.ndarray:
    """
    High-pass filter + vector magnitude of a 3-axis acceleration array.

    Parameters
    ----------
    accel : (N, 3) float array  — raw acceleration, any unit (m/s² or g)
    fs    : sample rate of *accel* (Hz)
    cutoff_hz : high-pass corner frequency (Hz)
    order : Butterworth filter order

    Returns
    -------
    mag : (N,) float array — filtered magnitude
    """
    b, a = butter(order, cutoff_hz / (fs / 2), btype="high")
    filtered = filtfilt(b, a, accel, axis=0)
    return np.linalg.norm(filtered, axis=1)


def _parabolic_peak(signal: np.ndarray, peak_idx: int) -> float:
    """
    Sub-sample peak location via parabolic interpolation.

    Given three consecutive samples around the integer peak, fit a parabola
    and return the fractional sample offset of the true maximum.

    Parameters
    ----------
    signal   : 1-D signal array
    peak_idx : integer index of the coarse maximum (1 ≤ peak_idx ≤ len-2)

    Returns
    -------
    sub_idx : float — sub-sample peak index in signal coordinates
    """
    if peak_idx <= 0 or peak_idx >= len(signal) - 1:
        return float(peak_idx)
    alpha = signal[peak_idx - 1]
    beta  = signal[peak_idx]
    gamma = signal[peak_idx + 1]
    denom = alpha - 2 * beta + gamma
    if abs(denom) < 1e-12:
        return float(peak_idx)
    offset = 0.5 * (alpha - gamma) / denom
    return peak_idx + offset


def _find_impulse(
    mag: np.ndarray,
    timestamps: np.ndarray,
    search_start: float,
    search_end: float,
    fs: float,
) -> tuple[float, float]:
    """
    Locate the highest-amplitude impulse in [search_start, search_end] seconds.

    Returns
    -------
    t_peak   : float — sub-sample time of the impulse (seconds, same epoch as timestamps)
    snr      : float — peak-to-RMS ratio in the search window (quality metric)
    """
    mask = (timestamps >= search_start) & (timestamps <= search_end)
    if mask.sum() < 3:
        raise ValueError(
            f"Search window [{search_start:.3f}, {search_end:.3f}] s has fewer than 3 samples."
        )
    window_mag = mag[mask]
    window_ts  = timestamps[mask]

    coarse_idx = int(np.argmax(window_mag))
    # Map coarse_idx back to global array for sub-sample interpolation
    global_indices = np.where(mask)[0]
    global_coarse  = global_indices[coarse_idx]
    sub_global     = _parabolic_peak(mag, global_coarse)

    # Interpolate timestamp at fractional index
    lo = int(np.floor(sub_global))
    hi = min(lo + 1, len(timestamps) - 1)
    frac = sub_global - lo
    t_peak = timestamps[lo] * (1 - frac) + timestamps[hi] * frac

    rms_window = float(np.sqrt(np.mean(window_mag ** 2)))
    snr = float(window_mag[coarse_idx]) / (rms_window + 1e-12)
    return t_peak, snr


# ── Public API ────────────────────────────────────────────────────────────────

def estimate_offset(
    accel_ref: np.ndarray,
    ts_ref: np.ndarray,
    accel_b: np.ndarray,
    ts_b: np.ndarray,
    tap1_window: tuple[float, float],
    tap2_window: tuple[float, float],
    fs_ref: float | None = None,
    fs_b: float | None = None,
    hp_cutoff_hz: float = 5.0,
) -> dict:
    """
    Estimate the clock offset and drift between phone A (reference) and phone B.

    Parameters
    ----------
    accel_ref : (N, 3) float — phone A acceleration (m/s² or g)
    ts_ref    : (N,)   float — phone A timestamps (seconds)
    accel_b   : (M, 3) float — phone B acceleration
    ts_b      : (M,)   float — phone B timestamps (seconds)
    tap1_window : (start, end) seconds — search window for tap 1 in phone A time
    tap2_window : (start, end) seconds — search window for tap 2 in phone A time
    fs_ref    : sample rate of ref (Hz); computed from median Δt if None
    fs_b      : sample rate of phone B (Hz); computed from median Δt if None
    hp_cutoff_hz : high-pass corner for impulse detection

    Returns
    -------
    dict with keys:
        offset_s    : float  — initial clock offset Δ₁ = t_A_tap1 − t_B_tap1 (seconds)
        drift_ppm   : float  — linear drift ε in parts-per-million
        snr_tap1    : float  — impulse SNR at tap 1 (quality; > 10 is good)
        snr_tap2    : float  — impulse SNR at tap 2
        t_tap1_ref  : float  — tap 1 time in reference clock (s)
        t_tap2_ref  : float  — tap 2 time in reference clock (s)
    """
    if fs_ref is None:
        fs_ref = 1.0 / float(np.median(np.diff(ts_ref)))
    if fs_b is None:
        fs_b = 1.0 / float(np.median(np.diff(ts_b)))

    mag_ref = _highpass_magnitude(accel_ref, fs_ref, hp_cutoff_hz)
    mag_b   = _highpass_magnitude(accel_b,   fs_b,   hp_cutoff_hz)

    # Tap 1
    t1_ref, snr1_ref = _find_impulse(mag_ref, ts_ref, *tap1_window, fs_ref)
    # For phone B, search the same absolute window (we'll correct after)
    t1_b,   snr1_b   = _find_impulse(mag_b,   ts_b,   *tap1_window, fs_b)
    snr1 = min(snr1_ref, snr1_b)

    # Tap 2
    t2_ref, snr2_ref = _find_impulse(mag_ref, ts_ref, *tap2_window, fs_ref)
    t2_b,   snr2_b   = _find_impulse(mag_b,   ts_b,   *tap2_window, fs_b)
    snr2 = min(snr2_ref, snr2_b)

    delta1 = t1_ref - t1_b   # offset at tap 1 (positive → B is behind A)
    delta2 = t2_ref - t2_b   # offset at tap 2

    span = t2_ref - t1_ref
    drift = (delta2 - delta1) / span if abs(span) > 1.0 else 0.0  # dimensionless
    drift_ppm = drift * 1e6

    return {
        "offset_s":   delta1,
        "drift_ppm":  drift_ppm,
        "snr_tap1":   snr1,
        "snr_tap2":   snr2,
        "t_tap1_ref": t1_ref,
        "t_tap2_ref": t2_ref,
    }


def correct_clock(
    ts_b: np.ndarray,
    offset_s: float,
    drift_ppm: float,
    t_ref_start: float,
) -> np.ndarray:
    """
    Apply the linear clock correction to phone B's timestamps.

    Corrected formula (§5.3):
        t_B_corrected = t_B − (Δ₁ + ε·(t_B − t₁))

    where Δ₁ = offset_s, ε = drift_ppm × 1e-6, t₁ = t_ref_start (tap-1 time).

    Parameters
    ----------
    ts_b        : (M,) float — raw phone B timestamps (seconds)
    offset_s    : float — Δ₁ from estimate_offset
    drift_ppm   : float — drift in ppm from estimate_offset
    t_ref_start : float — t₁ (tap-1 time in reference clock)

    Returns
    -------
    ts_corrected : (M,) float — corrected timestamps aligned to phone A's clock
    """
    epsilon = drift_ppm * 1e-6
    return ts_b - (offset_s + epsilon * (ts_b - t_ref_start))
