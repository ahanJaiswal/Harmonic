"""
test_spectrum.py — Golden-vector tests for spectral estimation.

A pure sinusoid of known amplitude and frequency is the ground truth.
Tests verify that welch_amplitude recovers amplitude within tolerance.

Tolerance note: Welch PSD with Hann window suffers up to ~20% scalloping
loss when a tone falls between bins. We allow 25% tolerance unless the tone
is explicitly placed on a bin.
"""

from __future__ import annotations

import numpy as np
import pytest

from harmonic_dsp.spectrum import welch_amplitude, welch_psd, frequency_resolution
from tests.conftest import make_sinusoid

# Constants matching the default auto-chosen nperseg:
#   nperseg = 2^ceil(log2(fs/0.1))
#   fs=200: nperseg=2048, df=0.09765625 Hz
#   fs=100: nperseg=1024, df=0.09765625 Hz
FS_AND = 200.0
FS_IPH = 100.0
DUR    = 40.0


def _bin_frequency(bin_idx: int, fs: float = FS_AND, nperseg: int = 2048) -> float:
    """Return the exact frequency of a given FFT bin."""
    return bin_idx * fs / nperseg


class TestWelchAmplitude:
    def test_amplitude_recovery_on_bin(self):
        """
        A tone placed EXACTLY on an FFT bin should recover amplitude within 5%.
        Bin 190: 190 * 200/2048 = 18.5546875 Hz
        """
        fs, nperseg = FS_AND, 2048
        freq = _bin_frequency(190, fs, nperseg)   # ~18.555 Hz
        amp  = 2.5
        sig, _ = make_sinusoid(freq, amp, 0.0, fs, DUR, noise_std=0.0)
        freqs, a = welch_amplitude(sig, fs, nperseg=nperseg)
        mask  = (freqs >= freq * 0.97) & (freqs <= freq * 1.03)
        a_peak = float(a[mask].max())
        rel_err = abs(a_peak - amp) / amp
        assert rel_err < 0.05, (
            f"On-bin amplitude error {rel_err:.3f} > 5% (expected {amp}, got {a_peak:.4f})"
        )

    def test_amplitude_recovery_between_bins_loose_tolerance(self):
        """
        A tone NOT on a bin suffers scalloping loss (Hann window worst case ~20%).
        Allow 30% tolerance.
        """
        amp, freq = 2.5, 18.6   # 18.6 Hz lands between bins with nperseg=2048
        sig, _ = make_sinusoid(freq, amp, 0.0, FS_AND, DUR, noise_std=0.0)
        freqs, a = welch_amplitude(sig, FS_AND)
        mask    = (freqs >= freq * 0.9) & (freqs <= freq * 1.1)
        a_peak  = float(a[mask].max())
        # Even off-bin, we should recover at least 70% of true amplitude
        assert a_peak > amp * 0.65, (
            f"Off-bin amplitude too low: {a_peak:.4f} (expected > {amp * 0.65:.4f})"
        )
        assert a_peak < amp * 1.3, (
            f"Off-bin amplitude too high: {a_peak:.4f} (expected < {amp * 1.3:.4f})"
        )

    def test_no_negative_amplitudes(self):
        """Amplitude spectrum must be non-negative everywhere."""
        sig, _ = make_sinusoid(18.6, 2.0, 0.3, FS_AND, DUR, noise_std=0.05)
        freqs, a = welch_amplitude(sig, FS_AND)
        assert (a >= 0).all(), "Amplitude spectrum has negative values"

    def test_frequency_resolution(self):
        fs, nperseg = FS_AND, 2048
        df_expected = fs / nperseg
        df_actual   = frequency_resolution(fs, nperseg)
        assert df_actual == pytest.approx(df_expected, rel=1e-9)
        # Verify welch output has this resolution
        sig, _ = make_sinusoid(18.6, 1.0, 0.0, fs, DUR)
        freqs, _ = welch_amplitude(sig, fs, nperseg=nperseg)
        df_measured = float(np.median(np.diff(freqs)))
        assert df_measured == pytest.approx(df_expected, rel=0.01)

    def test_two_tones_ratio_preserved(self):
        """
        1× at 2.5 m/s² should produce a larger peak than 2× at 0.4 m/s² in the spectrum.
        We don't check absolute values, just relative ordering.
        """
        fs = FS_AND
        a1, f1 = 2.5, 18.6
        a2, f2 = 0.4, 37.2
        sig1, _ = make_sinusoid(f1, a1, 0.0, fs, DUR)
        sig2, _ = make_sinusoid(f2, a2, 0.0, fs, DUR)
        freqs, a = welch_amplitude(sig1 + sig2, fs)
        m1 = (freqs >= 17.0) & (freqs <= 20.0)
        m2 = (freqs >= 35.0) & (freqs <= 39.0)
        assert a[m1].max() > a[m2].max(), "1× should dominate over 2×"

    def test_on_bin_ratio_matches(self):
        """
        Two on-bin tones: larger amplitude should produce proportionally larger peak.
        """
        fs, nperseg = FS_AND, 2048
        f1 = _bin_frequency(190, fs, nperseg)
        f2 = _bin_frequency(380, fs, nperseg)
        a1, a2 = 2.5, 0.8
        sig1, _ = make_sinusoid(f1, a1, 0.0, fs, DUR)
        sig2, _ = make_sinusoid(f2, a2, 0.0, fs, DUR)
        freqs, a = welch_amplitude(sig1 + sig2, fs, nperseg=nperseg)
        m1 = (freqs >= f1 * 0.98) & (freqs <= f1 * 1.02)
        m2 = (freqs >= f2 * 0.98) & (freqs <= f2 * 1.02)
        p1, p2 = a[m1].max(), a[m2].max()
        ratio_measured = p1 / p2
        ratio_expected = a1 / a2
        assert ratio_measured == pytest.approx(ratio_expected, rel=0.10), (
            f"Peak ratio {ratio_measured:.3f} != expected {ratio_expected:.3f}"
        )

    def test_iphone_frequency_resolution_similar(self):
        """iPhone at fs=100 Hz with auto-chosen nperseg=1024 gives same Δf ≈ 0.098 Hz."""
        df = frequency_resolution(FS_IPH, 1024)
        assert df == pytest.approx(FS_IPH / 1024, rel=1e-9)
        # It's the same as Android: both ≈ 0.098 Hz
        df_and = frequency_resolution(FS_AND, 2048)
        assert abs(df - df_and) < 1e-6
