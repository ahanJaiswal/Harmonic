"""
test_orders.py — Golden-vector tests for running speed detection and order extraction.

Ground truth: synthetic sinusoids at known multiples of a known running speed.

Order key naming: extract_orders uses f"{order:g}x" format:
  0.5 → "0.5x", 1.0 → "1x", 2.0 → "2x", 1.5 → "1.5x" etc.
"""

from __future__ import annotations

import numpy as np
import pytest

from harmonic_dsp.spectrum import welch_amplitude
from harmonic_dsp.orders import find_running_speed, extract_orders, STANDARD_ORDERS
from tests.conftest import make_sinusoid


FS   = 200.0       # Android sample rate
DUR  = 40.0        # 40-second record
# On-bin running speed: bin 190 at nperseg=2048
F_RUN = 190 * FS / 2048  # ~18.555 Hz
RPM_NP = 1200             # nameplate (true speed ≈ 93% of nameplate, within ±20% window)


def _make_imbalance_spectrum(a1=2.5, a2=0.4, noise=0.0):
    """Pure 1× + 2× sinusoid (on-bin frequencies), returns (freqs, amp)."""
    s1, _ = make_sinusoid(F_RUN,     a1, 0.0, FS, DUR, noise_std=noise)
    s2, _ = make_sinusoid(F_RUN * 2, a2, 0.0, FS, DUR, noise_std=0.0)
    return welch_amplitude(s1 + s2, FS)


def _order_key(order: float) -> str:
    """Format order key the same way extract_orders does."""
    return f"{order:g}x"


class TestFindRunningSpeed:
    def test_exact_speed_no_noise(self):
        freqs, amp = _make_imbalance_spectrum()
        result = find_running_speed(freqs, amp, RPM_NP)
        assert result["f_run_hz"] == pytest.approx(F_RUN, abs=0.15), (
            f"Expected ~{F_RUN:.3f} Hz, got {result['f_run_hz']:.3f}"
        )
        assert result["rpm_run"] == pytest.approx(F_RUN * 60, abs=10.0)

    def test_speed_with_light_noise(self):
        """SNR ~20 dB (noise_std = 0.1 × amplitude). Still finds 1× within 0.5 Hz."""
        s1, _ = make_sinusoid(F_RUN, 2.5, 0.0, FS, DUR, noise_std=0.25)
        freqs, amp = welch_amplitude(s1, FS)
        result = find_running_speed(freqs, amp, RPM_NP)
        assert result["f_run_hz"] == pytest.approx(F_RUN, abs=0.5)

    def test_confidence_high_for_strong_tone(self):
        freqs, amp = _make_imbalance_spectrum(a1=3.0)
        result = find_running_speed(freqs, amp, RPM_NP)
        assert result["confidence"] > 0.8, (
            f"Expected high confidence, got {result['confidence']:.3f}"
        )

    def test_amp_1x_matches_input(self):
        """amp_1x reported should be close to the injected amplitude."""
        freqs, amp = _make_imbalance_spectrum(a1=2.5)
        result = find_running_speed(freqs, amp, RPM_NP)
        # On-bin tone: should recover within 10%
        assert result["amp_1x"] == pytest.approx(2.5, rel=0.10)

    def test_returns_required_keys(self):
        freqs, amp = _make_imbalance_spectrum()
        result = find_running_speed(freqs, amp, RPM_NP)
        for key in ("f_run_hz", "rpm_run", "amp_1x", "confidence", "f_run_prior"):
            assert key in result, f"Missing key: {key}"


class TestExtractOrders:
    def test_1x_dominates_imbalance_signature(self):
        """1× >> 2× for imbalance. Use on-bin tones for reliable amplitude recovery."""
        freqs, amp = _make_imbalance_spectrum(a1=2.5, a2=0.4)
        orders = extract_orders(freqs, amp, F_RUN)
        k1, k2 = _order_key(1.0), _order_key(2.0)
        assert orders[k1] > orders[k2] * 2.0, (
            f"1× ({orders[k1]:.3f}) should be much larger than 2× ({orders[k2]:.3f})"
        )

    def test_2x_dominates_misalignment(self):
        """Misalignment: 2× > 1× when injected that way."""
        s1, _ = make_sinusoid(F_RUN,     1.0, 0.0, FS, DUR)
        s2, _ = make_sinusoid(F_RUN * 2, 2.5, 0.0, FS, DUR)
        freqs, amp = welch_amplitude(s1 + s2, FS)
        orders = extract_orders(freqs, amp, F_RUN)
        k1, k2 = _order_key(1.0), _order_key(2.0)
        assert orders[k2] > orders[k1], "2× should dominate for misalignment"

    def test_half_order_detected_looseness(self):
        """Looseness: 0.5× sub-synchronous should be non-zero."""
        s05, _ = make_sinusoid(F_RUN * 0.5, 0.5, 0.0, FS, DUR)
        s1,  _ = make_sinusoid(F_RUN,       1.0, 0.0, FS, DUR)
        freqs, amp = welch_amplitude(s05 + s1, FS)
        orders = extract_orders(freqs, amp, F_RUN)
        k05 = _order_key(0.5)
        assert orders[k05] > 0.05, f"Expected 0.5× > 0.05, got {orders[k05]:.4f}"

    def test_many_harmonics_looseness(self):
        """Inject 1× through 8× of equal amplitude; harmonics_count_above_floor ≥ 5."""
        sig = np.zeros(int(FS * DUR))
        for n in range(1, 9):
            s, _ = make_sinusoid(F_RUN * n, 0.5, 0.0, FS, DUR)
            sig += s
        freqs, amp = welch_amplitude(sig, FS)
        orders = extract_orders(freqs, amp, F_RUN)
        assert int(orders["harmonics_count_above_floor"]) >= 5, (
            f"Expected ≥5 harmonics above floor, got {orders['harmonics_count_above_floor']}"
        )

    def test_all_standard_orders_present_as_keys(self):
        """All STANDARD_ORDERS should appear as keys in the output."""
        freqs, amp = _make_imbalance_spectrum()
        orders = extract_orders(freqs, amp, F_RUN)
        for o in STANDARD_ORDERS:
            key = _order_key(o)
            assert key in orders, f"Standard order '{key}' not in result keys: {list(orders.keys())}"

    def test_values_non_negative(self):
        freqs, amp = _make_imbalance_spectrum()
        orders = extract_orders(freqs, amp, F_RUN)
        for k, v in orders.items():
            if k != "harmonics_count_above_floor":
                assert float(v) >= 0, f"Negative amplitude for order {k}: {v}"

    def test_harmonics_count_key_present(self):
        freqs, amp = _make_imbalance_spectrum()
        orders = extract_orders(freqs, amp, F_RUN)
        assert "harmonics_count_above_floor" in orders
        assert isinstance(orders["harmonics_count_above_floor"], (int, float))
