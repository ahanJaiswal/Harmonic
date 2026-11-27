"""
phase.py — Cross-phone phase and magnitude-squared coherence (§5.8).

The phase at each order frequency is the most diagnostic quantity for
distinguishing fault types:
    Static imbalance:   DE–NDE radial phase ≈ 0°  (in-phase)
    Couple imbalance:   DE–NDE radial phase ≈ 180° (out-of-phase)
    Angular misalignment: axial phase ≈ 180° across coupling
    Parallel misalignment: radial 2× dominant, 180° across coupling
    Looseness:          phase unstable across repeated readings

Coherence filter
----------------
Phase estimates are only reliable where the two signals are linearly related.
The magnitude-squared coherence (MSC) at frequency f is:
    γ²(f) = |S_xy(f)|² / (S_xx(f) · S_yy(f))

Values close to 1 → high linear relationship → phase is reliable.
If γ²(f_order) < coherence_min (default 0.8), the phase is set to None.

Same-bearing horizontal vs vertical phase
------------------------------------------
Single-phone: φ(H vs V at 1×) ≈ 90° is expected for static imbalance
(the two radial directions are 90° apart by geometry; imbalance force rotates).
"""

from __future__ import annotations

import numpy as np
from scipy.signal import csd as scipy_csd, welch as scipy_welch


def coherence_nperseg(length: int) -> int:
    """
    Default Welch segment for coherence: the largest power of two that still leaves
    about seven half-overlapped segments. With a single segment, magnitude-squared
    coherence is 1.0 by construction, so phase taken from pure noise would look trusted.
    """
    n = 32
    while n * 2 <= length // 4:
        n *= 2
    return max(1, min(n, length))


def cross_phase_coherence(
    signal_a: np.ndarray,
    signal_b: np.ndarray,
    fs: float,
    f_target_hz: float,
    nperseg: int | None = None,
    noverlap: int | None = None,
    coherence_min: float = 0.8,
) -> dict:
    """
    Estimate phase and coherence between two signals at a target frequency.

    Uses Welch's cross-spectral density method for lower variance.

    Parameters
    ----------
    signal_a       : (N,) float — reference signal (phone A, DC removed)
    signal_b       : (N,) float — signal to compare (phone B, DC removed)
    fs             : float — sample rate (Hz), same for both (use common grid)
    f_target_hz    : float — frequency at which to evaluate phase/coherence
    nperseg        : int — Welch segment length (auto if None)
    noverlap       : int — Welch overlap (default nperseg // 2)
    coherence_min  : float — minimum γ² to trust the phase (default 0.8)

    Returns
    -------
    dict with keys:
        phase_deg     : float | None — phase of B relative to A in degrees
                                       [-180°, 180°]; None if coherence < threshold
        coherence     : float — magnitude-squared coherence γ² at f_target_hz
        f_actual_hz   : float — nearest bin center to f_target_hz (Hz)
        trusted       : bool  — True if coherence >= coherence_min
    """
    signal_a = np.asarray(signal_a, dtype=float)
    signal_b = np.asarray(signal_b, dtype=float)

    if len(signal_a) != len(signal_b):
        raise ValueError(
            f"signal_a length {len(signal_a)} ≠ signal_b length {len(signal_b)}"
        )

    if nperseg is None:
        nperseg = coherence_nperseg(len(signal_a))
    if noverlap is None:
        noverlap = nperseg // 2

    # Cross-spectral density S_xy(f)
    freqs, S_xy = scipy_csd(
        signal_a, signal_b,
        fs=fs, window="hann", nperseg=nperseg, noverlap=noverlap,
        return_onesided=True,
    )
    # Autospectral densities
    _, S_xx = scipy_welch(signal_a, fs=fs, window="hann", nperseg=nperseg,
                          noverlap=noverlap, return_onesided=True)
    _, S_yy = scipy_welch(signal_b, fs=fs, window="hann", nperseg=nperseg,
                          noverlap=noverlap, return_onesided=True)

    # Nearest bin to target
    idx = int(np.argmin(np.abs(freqs - f_target_hz)))
    f_actual = float(freqs[idx])

    s_xx_val = float(S_xx[idx])
    s_yy_val = float(S_yy[idx])
    denom = s_xx_val * s_yy_val

    if denom < 1e-30:
        return {
            "phase_deg":   None,
            "coherence":   0.0,
            "f_actual_hz": f_actual,
            "trusted":     False,
        }

    gamma_sq = float(np.abs(S_xy[idx]) ** 2 / denom)
    gamma_sq = float(np.clip(gamma_sq, 0.0, 1.0))

    phase_rad = float(np.angle(S_xy[idx]))  # arg(S_xy) = phase of B relative to A
    phase_deg = float(np.degrees(phase_rad))

    trusted = gamma_sq >= coherence_min

    return {
        "phase_deg":   phase_deg if trusted else None,
        "coherence":   gamma_sq,
        "f_actual_hz": f_actual,
        "trusted":     trusted,
    }


def phase_stability(
    phases_deg: list[float | None],
    coherence_min: float = 0.8,
) -> float | None:
    """
    Compute the standard deviation of phase across repeated readings.

    A stable phase (< ~30°) suggests imbalance or misalignment.
    An erratic phase (large std) suggests looseness.

    Parameters
    ----------
    phases_deg : list of phase measurements (°), None values are excluded
    coherence_min : not used here, reserved for future filtering

    Returns
    -------
    std_deg : float | None — phase standard deviation (degrees), or None if < 2 valid readings
    """
    valid = [p for p in phases_deg if p is not None]
    if len(valid) < 2:
        return None
    # Circular standard deviation (handles 0°/360° wrapping)
    angles_rad = np.radians(valid)
    r = np.abs(np.mean(np.exp(1j * angles_rad)))
    # r ∈ [0, 1]; std ≈ sqrt(-2 ln r) in radians
    r_clipped = float(np.clip(r, 1e-6, 1.0))
    circ_std_rad = float(np.sqrt(-2.0 * np.log(r_clipped)))
    return float(np.degrees(circ_std_rad))


def hv_phase(
    signal_h: np.ndarray,
    signal_v: np.ndarray,
    fs: float,
    f_target_hz: float,
    nperseg: int | None = None,
    coherence_min: float = 0.8,
) -> dict:
    """
    Phase between horizontal (H) and vertical (V) channels of the SAME phone at a frequency.

    For static imbalance, H–V phase at 1× ≈ 90° (the imbalance force rotates,
    giving sinusoidal motion in both radial directions 90° apart).

    Returns the same dict format as cross_phase_coherence.
    """
    return cross_phase_coherence(
        signal_h, signal_v, fs, f_target_hz,
        nperseg=nperseg, coherence_min=coherence_min,
    )
