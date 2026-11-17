"""
velocity.py — Acceleration to velocity conversion and RMS severity (§5.7).

Relationship: a(t) = dv/dt  →  V(f) = A(f) / (2πf)   [in frequency domain]

Units:
    Input:  acceleration amplitude spectrum in m/s²  (peak)
    Output: velocity amplitude spectrum in mm/s       (peak)

Overall velocity RMS
--------------------
Single-sided amplitude spectrum → RMS via:
    v_rms = (1/√2) · sqrt( Σ V(f)² )   for f > 0

Low-frequency exclusion
------------------------
Dividing by 2πf blows up near DC. Exclude f < f_lo_hz (default 2 Hz).
ISO 10816 bands start at 10 Hz; we're band-limited (phone fs/2) so we label
results as "band-limited / estimated" in the UI.
"""

from __future__ import annotations

import numpy as np

from harmonic_dsp.spectrum import HANN_AMPL_CORR

# Default low-frequency cutoff for velocity conversion (Hz)
DEFAULT_F_LO_HZ = 2.0


def accel_to_velocity_rms(
    freqs: np.ndarray,
    amp_accel: np.ndarray,
    f_lo_hz: float = DEFAULT_F_LO_HZ,
) -> tuple[np.ndarray, np.ndarray]:
    """
    Convert an acceleration amplitude spectrum to a velocity amplitude spectrum.

    Parameters
    ----------
    freqs      : (K,) float — frequency array (Hz), one-sided from welch_amplitude
    amp_accel  : (K,) float — peak acceleration amplitude (m/s²)
    f_lo_hz    : float — exclude bins below this frequency to avoid DC blow-up

    Returns
    -------
    freqs_v  : (K',) float — frequency array excluding f < f_lo_hz (Hz)
    amp_vel  : (K',) float — peak velocity amplitude (mm/s)

    Notes
    -----
    V(f) [m/s, peak] = A(f) [m/s², peak] / (2π f)
    mm/s = m/s × 1000
    """
    freqs = np.asarray(freqs, dtype=float)
    amp_accel = np.asarray(amp_accel, dtype=float)

    mask = freqs >= f_lo_hz
    freqs_v   = freqs[mask]
    amp_v_mps = amp_accel[mask] / (2.0 * np.pi * freqs_v + 1e-12)
    amp_vel   = amp_v_mps * 1000.0  # m/s → mm/s

    return freqs_v, amp_vel


def overall_velocity_rms(
    freqs: np.ndarray,
    amp_accel: np.ndarray,
    f_lo_hz: float = DEFAULT_F_LO_HZ,
) -> float:
    """
    Compute overall velocity RMS (mm/s) from an acceleration amplitude spectrum.

    Uses single-sided amplitudes → RMS:
        v_rms = (1/√2) · sqrt( Σ V_peak(f)² )

    This is equivalent to sqrt( Σ V_rms_per_bin² ) since V_rms_bin = V_peak / √2.

    Parameters
    ----------
    freqs     : (K,) float — frequency array (Hz)
    amp_accel : (K,) float — peak acceleration amplitude (m/s²)
    f_lo_hz   : float — low-frequency cutoff for velocity conversion

    Returns
    -------
    v_rms_mm_s : float — overall velocity RMS in mm/s
    """
    _, amp_vel = accel_to_velocity_rms(freqs, amp_accel, f_lo_hz)
    # amp_vel contains Hann-corrected peak amplitudes (from welch_amplitude).
    # For energy-based total RMS (Parseval's theorem) we must undo the window
    # correction: sum(amp_corr²) = CORR² · sum(amp_unc²) = CORR² · A²,
    # so v_rms = sqrt(sum(amp_corr²) / (2 · CORR²)) = sqrt(A²/2) = A/√2 ✓
    return float(np.sqrt(0.5 / HANN_AMPL_CORR ** 2 * np.sum(amp_vel ** 2)))


def velocity_at_order(
    freqs: np.ndarray,
    amp_accel: np.ndarray,
    f_order_hz: float,
    f_lo_hz: float = DEFAULT_F_LO_HZ,
    search_half_bins: int = 2,
) -> float:
    """
    Velocity amplitude (mm/s) at a specific order frequency.

    Parameters
    ----------
    freqs       : (K,) float — frequency array (Hz)
    amp_accel   : (K,) float — peak acceleration amplitude (m/s²)
    f_order_hz  : float — order frequency in Hz
    f_lo_hz     : float — low-frequency cutoff
    search_half_bins : int — search window (bins) around f_order_hz

    Returns
    -------
    v_mm_s : float — velocity amplitude at order (mm/s, peak)
    """
    if f_order_hz < f_lo_hz:
        return 0.0

    df = freqs[1] - freqs[0] if len(freqs) > 1 else 1.0
    idx = int(np.argmin(np.abs(freqs - f_order_hz)))
    lo = max(0, idx - search_half_bins)
    hi = min(len(amp_accel) - 1, idx + search_half_bins)

    local_amp = amp_accel[lo:hi + 1]
    local_f   = freqs[lo:hi + 1]
    peak_local = int(np.argmax(local_amp))

    f_peak = float(local_f[peak_local])
    a_peak = float(local_amp[peak_local])

    if f_peak < f_lo_hz:
        return 0.0

    v_m_s = a_peak / (2.0 * np.pi * f_peak + 1e-12)
    return v_m_s * 1000.0
