"""
orders.py — Running-speed estimation and order-axis extraction (§5.5, §5.6).

Orders are multiples of the shaft running speed:
    order k → frequency f_k = k · f_run

We extract amplitude at orders 0.5×, 1×, 1.5×, 2×, 3× … 10× using
parabolic interpolation around the nearest bin for sub-bin accuracy.

Running speed estimation
------------------------
1. Apply a prior from the nameplate RPM (± 20% search window for slip/tolerance).
2. Find the dominant spectral peak in that window.
3. Refine with parabolic interpolation → f_run in Hz.
4. Optionally cross-check with blade-pass frequency from the microphone (P2).

ISO slip note: induction motors run below synchronous speed by ~1–3%.
  Synchronous: f_sync = f_mains / (pole_pairs) e.g., 60 Hz / 2 = 30 Hz for 4-pole
  Running:     f_run  ≈ 0.97–0.99 × f_sync
"""

from __future__ import annotations

import numpy as np

# Standard orders to extract
STANDARD_ORDERS = [0.5, 1.0, 1.5, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0]


def _parabolic_interp(freqs: np.ndarray, amp: np.ndarray, peak_idx: int) -> tuple[float, float]:
    """
    Sub-bin frequency and amplitude via parabolic interpolation.

    Returns
    -------
    f_refined : float — refined peak frequency (Hz)
    a_refined : float — refined peak amplitude
    """
    n = len(amp)
    if peak_idx <= 0 or peak_idx >= n - 1:
        return float(freqs[peak_idx]), float(amp[peak_idx])
    alpha = amp[peak_idx - 1]
    beta  = amp[peak_idx]
    gamma = amp[peak_idx + 1]
    denom = alpha - 2 * beta + gamma
    if abs(denom) < 1e-15:
        return float(freqs[peak_idx]), float(beta)
    # Fractional bin offset
    p = 0.5 * (alpha - gamma) / denom
    df = freqs[1] - freqs[0]
    f_refined = freqs[peak_idx] + p * df
    # Refined amplitude (parabola maximum)
    a_refined = beta - 0.25 * (alpha - gamma) * p
    return float(f_refined), float(max(a_refined, 0.0))


def find_running_speed(
    freqs: np.ndarray,
    amp: np.ndarray,
    rpm_nameplate: float,
    search_fraction: float = 0.20,
) -> dict:
    """
    Estimate the shaft running speed from the amplitude spectrum.

    Searches within ± search_fraction of the nameplate frequency for the
    highest peak, then refines with parabolic interpolation.

    Parameters
    ----------
    freqs         : (K,) float — frequency array from welch_amplitude (Hz)
    amp           : (K,) float — amplitude spectrum (m/s² or g)
    rpm_nameplate : float — nameplate (rated) shaft speed in RPM
    search_fraction : float — fractional search window around nameplate
                      (default 0.20 = ±20%, covers induction motor slip + tolerance)

    Returns
    -------
    dict with keys:
        f_run_hz    : float — estimated running speed (Hz)
        rpm_run     : float — estimated running speed (RPM)
        amp_1x      : float — amplitude at 1× (m/s² or g)
        confidence  : float — rough confidence in [0, 1]:
                       SNR of the found peak vs. mean noise floor in the window
        f_run_prior : float — nameplate speed in Hz (for reference)
    """
    f_prior = rpm_nameplate / 60.0
    f_lo = f_prior * (1.0 - search_fraction)
    f_hi = f_prior * (1.0 + search_fraction)

    mask = (freqs >= f_lo) & (freqs <= f_hi)
    if mask.sum() < 3:
        return {
            "f_run_hz": f_prior,
            "rpm_run": rpm_nameplate,
            "amp_1x": 0.0,
            "confidence": 0.0,
            "f_run_prior": f_prior,
        }

    window_amp = amp[mask]
    idx_in_window = int(np.argmax(window_amp))
    global_indices = np.where(mask)[0]
    global_peak = global_indices[idx_in_window]

    f_run, amp_1x = _parabolic_interp(freqs, amp, global_peak)

    # Confidence: peak / mean noise in window
    noise_mean = float(np.mean(window_amp))
    snr = amp_1x / (noise_mean + 1e-12)
    confidence = float(np.clip((snr - 1.0) / 9.0, 0.0, 1.0))  # 1–10× SNR → 0–1

    return {
        "f_run_hz":    f_run,
        "rpm_run":     f_run * 60.0,
        "amp_1x":      amp_1x,
        "confidence":  confidence,
        "f_run_prior": f_prior,
    }


def extract_orders(
    freqs: np.ndarray,
    amp: np.ndarray,
    f_run_hz: float,
    orders: list[float] | None = None,
    search_half_bins: int = 3,
) -> dict[str, float]:
    """
    Extract amplitude at each order multiple of the running speed.

    For each order k, search within ± search_half_bins of the nominal frequency
    k·f_run, pick the peak, and refine with parabolic interpolation.

    Parameters
    ----------
    freqs            : (K,) float — frequency array (Hz)
    amp              : (K,) float — amplitude spectrum
    f_run_hz         : float — running speed (Hz)
    orders           : list of float — order multiples to extract (default STANDARD_ORDERS)
    search_half_bins : int — ±bins around nominal order frequency to search

    Returns
    -------
    dict mapping e.g. "0.5x" → float amplitude, "1x" → float, ..., "10x" → float
    Also includes:
        "harmonics_count_above_floor" : int — number of integer orders (1×–10×)
                                              with amplitude > 2× mean spectral floor
    """
    if orders is None:
        orders = STANDARD_ORDERS

    df = freqs[1] - freqs[0] if len(freqs) > 1 else 1.0
    result: dict[str, float] = {}

    for order in orders:
        f_nominal = order * f_run_hz
        if f_nominal <= 0 or f_nominal > freqs[-1]:
            key = f"{order:g}x"
            result[key] = 0.0
            continue

        # Nearest bin index
        idx_nominal = int(np.argmin(np.abs(freqs - f_nominal)))
        lo = max(0, idx_nominal - search_half_bins)
        hi = min(len(amp) - 1, idx_nominal + search_half_bins)

        window_amp = amp[lo:hi + 1]
        idx_local_peak = int(np.argmax(window_amp))
        global_peak = lo + idx_local_peak
        _, a_refined = _parabolic_interp(freqs, amp, global_peak)

        key = f"{order:g}x"
        result[key] = a_refined

    # Count harmonics above noise floor
    # Noise floor estimate: median of whole spectrum
    noise_floor = float(np.median(amp)) * 2.0
    harmonics_above = sum(
        1 for k in range(1, 11)
        if result.get(f"{k}x", 0.0) > noise_floor
    )
    result["harmonics_count_above_floor"] = float(harmonics_above)

    return result
