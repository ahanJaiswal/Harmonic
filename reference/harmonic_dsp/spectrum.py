"""
spectrum.py — Welch PSD and amplitude spectrum (§5.6).

Welch's method gives a lower-variance spectral estimate by averaging
overlapping, windowed FFT segments.

Design choices
--------------
- Window: Hann (good sidelobe suppression; correct with coherent power gain = 0.5)
- Overlap: 50% (standard; maximizes independence vs. cost)
- Segment length: balance frequency resolution vs. number of averages.
  - At fs = 200 Hz, N_seg = 2048 → Δf ≈ 0.098 Hz, 30 s → ~29 segments (good averaging)
  - At fs = 100 Hz, N_seg = 1024 → Δf ≈ 0.098 Hz, same resolution

Amplitude spectrum
------------------
The PSD (units²/Hz) is converted to a one-sided amplitude spectrum (units peak)
for picking order amplitudes.

For a windowed Welch PSD (one-sided), the correct peak-amplitude formula is:

    A(f) = sqrt(2 · PSD(f) · Δf · PG / CG²)   [one-sided, f > 0]
    A(0) = sqrt(PSD(0) · Δf · PG / CG²)         [DC term, not doubled]

where:
    CG = coherent gain   = sum(w) / N   = 0.5    for Hann
    PG = power gain      = sum(w²) / N  = 3/8    for Hann
    PG / CG²                            = 3/2    for Hann

The naive formula sqrt(2·PSD·Δf) under-recovers amplitude by sqrt(CG²/PG) =
sqrt(2/3) ≈ 0.817 for the Hann window; the correction factor sqrt(PG/CG²) =
sqrt(3/2) ≈ 1.225 restores exact peak amplitude for on-bin tones.

Derivation:
  For x[n] = A·cos(2πf₀n/fs) exactly on bin k₀, the Welch PSD (density scaling)
  satisfies 2·PSD(f₀)·Δf = A²·CG²/PG, so A = sqrt(2·PSD·Δf) · sqrt(PG/CG²).
"""

from __future__ import annotations

import numpy as np
from scipy.signal import welch as scipy_welch


# ── Constants ─────────────────────────────────────────────────────────────────

HANN_COHERENT_GAIN = 0.5           # CG = sum(w)/N for Hann window
HANN_POWER_GAIN    = 3.0 / 8.0    # PG = sum(w²)/N for Hann window

# Correction factor to recover peak amplitude from Welch PSD:
#   sqrt(2·PSD·Δf) gives A·sqrt(CG²/PG); multiply by HANN_AMPL_CORR to get A.
HANN_AMPL_CORR = (HANN_POWER_GAIN / HANN_COHERENT_GAIN**2) ** 0.5  # = sqrt(3/2)


# ── Public API ────────────────────────────────────────────────────────────────

def welch_psd(
    signal: np.ndarray,
    fs: float,
    nperseg: int | None = None,
    noverlap: int | None = None,
    detrend: str | bool = "constant",
) -> tuple[np.ndarray, np.ndarray]:
    """
    One-sided Welch PSD of a 1-D signal.

    Parameters
    ----------
    signal   : (N,) float — uniformly sampled signal (DC removed upstream recommended)
    fs       : float — sample rate (Hz)
    nperseg  : int — FFT segment length. Auto-chosen as power-of-2 giving
                 Δf ≈ 0.1 Hz if None.
    noverlap : int — overlap between segments (default = nperseg // 2)
    detrend  : detrend mode passed to scipy.signal.welch

    Returns
    -------
    freqs : (K,) float — frequency array (Hz), one-sided (0 to fs/2)
    psd   : (K,) float — power spectral density (units²/Hz)
    """
    signal = np.asarray(signal, dtype=float)
    if signal.ndim != 1:
        raise ValueError(f"signal must be 1-D, got shape {signal.shape}")

    if nperseg is None:
        # Choose power-of-2 segment giving Δf ≈ 0.1 Hz
        nperseg = int(2 ** np.ceil(np.log2(fs / 0.1)))
        nperseg = min(nperseg, len(signal))

    if noverlap is None:
        noverlap = nperseg // 2

    freqs, psd = scipy_welch(
        signal,
        fs=fs,
        window="hann",
        nperseg=nperseg,
        noverlap=noverlap,
        detrend=detrend,
        scaling="density",
        return_onesided=True,
    )
    return freqs, psd


def welch_amplitude(
    signal: np.ndarray,
    fs: float,
    nperseg: int | None = None,
    noverlap: int | None = None,
) -> tuple[np.ndarray, np.ndarray]:
    """
    One-sided amplitude spectrum (peak amplitude per frequency bin) via Welch.

    Converts the Welch PSD to peak amplitude:
        A(f) = sqrt(2 · PSD(f) · Δf)   for f > 0
        A(0) = sqrt(PSD(0) · Δf)        for DC

    The Hann window coherent gain (0.5) is already baked into scipy's
    density scaling, so the amplitude estimates are correct for sinusoidal inputs.

    Parameters
    ----------
    signal   : (N,) float — uniformly sampled signal
    fs       : float — sample rate (Hz)
    nperseg, noverlap : passed to welch_psd

    Returns
    -------
    freqs : (K,) float — frequency array (Hz)
    amp   : (K,) float — peak amplitude spectrum (same units as signal)

    Example: for a pure sinusoid of amplitude A, the peak at its frequency
    will read approximately A (within ±Δf/2 interpolation error).
    """
    freqs, psd = welch_psd(signal, fs, nperseg=nperseg, noverlap=noverlap)
    df = freqs[1] - freqs[0] if len(freqs) > 1 else 1.0

    # Apply window amplitude correction: sqrt(PG/CG²) = sqrt(3/2) for Hann
    amp = np.sqrt(2.0 * psd * df * HANN_AMPL_CORR**2)
    amp[0] = np.sqrt(psd[0] * df * HANN_AMPL_CORR**2)  # DC term: not doubled
    return freqs, amp


def frequency_resolution(fs: float, nperseg: int) -> float:
    """Return Δf = fs / nperseg (Hz)."""
    return fs / nperseg
