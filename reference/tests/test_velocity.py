"""
test_velocity.py — Golden-vector tests for acceleration→velocity conversion.

Ground truth for a pure on-bin sinusoid:
    a(t) = A·sin(2πf·t)              [m/s²]
    v(t) = A/(2πf)·cos(2πf·t)       [m/s]
    v_peak = A/(2πf) [m/s] × 1000   [mm/s]

We use on-bin tones (frequency exactly on an FFT bin) for precise amplitude
recovery, and looser tolerances for off-bin tones.
"""

from __future__ import annotations

import numpy as np
import pytest

from harmonic_dsp.spectrum import welch_amplitude
from harmonic_dsp.velocity import (
    accel_to_velocity_rms,
    overall_velocity_rms,
    velocity_at_order,
)
from tests.conftest import make_sinusoid


FS  = 200.0
DUR = 40.0

# On-bin frequency: bin 190 at nperseg=2048, fs=200 → 190*200/2048 ≈ 18.555 Hz
F_ON_BIN = 190 * FS / 2048


def _expected_v_peak_mm_s(amp_m_s2: float, freq_hz: float) -> float:
    """Analytically correct velocity peak amplitude for a pure sinusoid (mm/s)."""
    return amp_m_s2 / (2 * np.pi * freq_hz) * 1000.0


def _expected_v_rms_mm_s(amp_m_s2: float, freq_hz: float) -> float:
    """Analytically correct velocity RMS for a pure sinusoid (mm/s)."""
    return _expected_v_peak_mm_s(amp_m_s2, freq_hz) / np.sqrt(2)


class TestAccelToVelocityRms:
    def test_on_bin_velocity_within_10_percent(self):
        """On-bin tone: velocity amplitude within 10% of analytical."""
        amp, freq = 2.5, F_ON_BIN
        sig, _ = make_sinusoid(freq, amp, 0.0, FS, DUR, noise_std=0.0)
        freqs, amp_spec = welch_amplitude(sig, FS)
        freqs_v, v_spec = accel_to_velocity_rms(freqs, amp_spec)
        mask = (freqs_v >= freq * 0.97) & (freqs_v <= freq * 1.03)
        v_peak_measured = float(v_spec[mask].max())
        v_expected = _expected_v_peak_mm_s(amp, freq)
        rel_err = abs(v_peak_measured - v_expected) / v_expected
        assert rel_err < 0.10, (
            f"Velocity amplitude error {rel_err:.3f} > 10% at {freq:.3f} Hz "
            f"(expected {v_expected:.3f} mm/s, got {v_peak_measured:.3f} mm/s)"
        )

    def test_low_frequency_excluded(self):
        """
        accel_to_velocity_rms excludes bins below f_lo_hz.
        The returned freqs_v should start at or above f_lo_hz.
        """
        sig, _ = make_sinusoid(1.0, 5.0, 0.0, FS, DUR)
        freqs, amp_spec = welch_amplitude(sig, FS)
        freqs_v, v_spec = accel_to_velocity_rms(freqs, amp_spec, f_lo_hz=2.0)
        # All returned frequencies must be >= f_lo_hz
        assert float(freqs_v.min()) >= 2.0 - 0.2, (
            f"Returned frequencies below f_lo: min={freqs_v.min():.3f}"
        )

    def test_returned_arrays_same_length(self):
        """freqs_v and v_spec must be the same length."""
        sig, _ = make_sinusoid(F_ON_BIN, 2.5, 0.0, FS, DUR)
        freqs, amp_spec = welch_amplitude(sig, FS)
        freqs_v, v_spec = accel_to_velocity_rms(freqs, amp_spec)
        assert len(freqs_v) == len(v_spec)

    def test_velocity_increases_with_amplitude(self):
        """Higher acceleration amplitude should give higher velocity."""
        freq = F_ON_BIN
        sig1, _ = make_sinusoid(freq, 1.0, 0.0, FS, DUR)
        sig2, _ = make_sinusoid(freq, 3.0, 0.0, FS, DUR)
        freqs, amp1 = welch_amplitude(sig1, FS)
        _,     amp2 = welch_amplitude(sig2, FS)
        freqs_v, v1 = accel_to_velocity_rms(freqs, amp1)
        _,       v2 = accel_to_velocity_rms(freqs, amp2)
        mask = (freqs_v >= freq * 0.97) & (freqs_v <= freq * 1.03)
        assert v2[mask].max() > v1[mask].max() * 2.0, (
            "Tripling amplitude should more than double velocity"
        )

    def test_velocity_decreases_with_frequency(self):
        """
        a_to_v divides by 2πf, so higher frequency → smaller velocity for same acceleration.
        Compare on-bin tones at ~18.5 Hz vs ~37 Hz.
        """
        f1 = 190 * FS / 2048  # ~18.555 Hz
        f2 = 380 * FS / 2048  # ~37.109 Hz
        amp = 2.0
        sig1, _ = make_sinusoid(f1, amp, 0.0, FS, DUR)
        sig2, _ = make_sinusoid(f2, amp, 0.0, FS, DUR)
        freqs, amp1 = welch_amplitude(sig1, FS)
        _,     amp2 = welch_amplitude(sig2, FS)
        freqs_v, v1 = accel_to_velocity_rms(freqs, amp1)
        _,       v2 = accel_to_velocity_rms(freqs, amp2)
        m1 = (freqs_v >= f1 * 0.98) & (freqs_v <= f1 * 1.02)
        m2 = (freqs_v >= f2 * 0.98) & (freqs_v <= f2 * 1.02)
        assert v1[m1].max() > v2[m2].max(), (
            "Lower frequency should give higher velocity for same acceleration"
        )


class TestOverallVelocityRms:
    def test_on_bin_analytical_within_15_percent(self):
        """overall_velocity_rms for one on-bin sinusoid within 15% of analytical."""
        amp, freq = 2.5, F_ON_BIN
        sig, _ = make_sinusoid(freq, amp, 0.0, FS, DUR, noise_std=0.0)
        freqs, amp_spec = welch_amplitude(sig, FS)
        v_overall = overall_velocity_rms(freqs, amp_spec)
        v_expected = _expected_v_rms_mm_s(amp, freq)
        rel_err = abs(v_overall - v_expected) / v_expected
        assert rel_err < 0.15, (
            f"overall_velocity_rms error {rel_err:.3f} > 15% "
            f"(expected {v_expected:.4f} mm/s, got {v_overall:.4f} mm/s)"
        )

    def test_two_tones_larger_than_one(self):
        """Adding a second tone should increase the overall RMS."""
        f1 = 190 * FS / 2048
        f2 = 380 * FS / 2048
        sig1, _ = make_sinusoid(f1, 2.5, 0.0, FS, DUR)
        sig2, _ = make_sinusoid(f2, 0.8, 0.0, FS, DUR)
        freqs, amp1 = welch_amplitude(sig1, FS)
        _,     amp12 = welch_amplitude(sig1 + sig2, FS)
        v_one  = overall_velocity_rms(freqs, amp1)
        v_both = overall_velocity_rms(freqs, amp12)
        assert v_both > v_one, "Adding a second tone should increase overall RMS"

    def test_returns_positive(self):
        sig, _ = make_sinusoid(F_ON_BIN, 1.0, 0.0, FS, DUR)
        freqs, amp_spec = welch_amplitude(sig, FS)
        assert overall_velocity_rms(freqs, amp_spec) > 0.0

    def test_scales_with_amplitude(self):
        """3× amplitude → ~3× overall RMS (linear)."""
        freq = F_ON_BIN
        sig1, _ = make_sinusoid(freq, 1.0, 0.0, FS, DUR)
        sig3, _ = make_sinusoid(freq, 3.0, 0.0, FS, DUR)
        freqs, amp1 = welch_amplitude(sig1, FS)
        _,     amp3 = welch_amplitude(sig3, FS)
        v1 = overall_velocity_rms(freqs, amp1)
        v3 = overall_velocity_rms(freqs, amp3)
        assert v3 == pytest.approx(3 * v1, rel=0.15)


class TestVelocityAtOrder:
    def test_at_on_bin_frequency(self):
        """velocity_at_order for on-bin tone within 10% of analytical."""
        amp, freq = 2.5, F_ON_BIN
        sig, _ = make_sinusoid(freq, amp, 0.0, FS, DUR, noise_std=0.0)
        freqs, amp_spec = welch_amplitude(sig, FS)
        v = velocity_at_order(freqs, amp_spec, freq)
        v_expected = _expected_v_peak_mm_s(amp, freq)
        rel_err = abs(v - v_expected) / v_expected
        assert rel_err < 0.10, (
            f"velocity_at_order error {rel_err:.3f} > 10% "
            f"(expected {v_expected:.3f} mm/s, got {v:.3f} mm/s)"
        )

    def test_at_silent_frequency_near_zero(self):
        """An order not injected should return near-zero velocity."""
        freq = F_ON_BIN
        sig, _ = make_sinusoid(freq, 2.5, 0.0, FS, DUR, noise_std=0.0)
        freqs, amp_spec = welch_amplitude(sig, FS)
        # Query at 3× — not injected (use on-bin equivalent)
        f_3x = 570 * FS / 2048  # on-bin ~55.7 Hz
        v = velocity_at_order(freqs, amp_spec, f_3x)
        # Should be very small (noise floor)
        assert v < 1.0, f"Expected near-zero at silent order, got {v:.3f} mm/s"

    def test_below_f_lo_returns_zero(self):
        """Frequency below f_lo_hz should return 0.0."""
        sig, _ = make_sinusoid(F_ON_BIN, 2.5, 0.0, FS, DUR)
        freqs, amp_spec = welch_amplitude(sig, FS)
        v = velocity_at_order(freqs, amp_spec, f_order_hz=1.0, f_lo_hz=2.0)
        assert v == 0.0
