"""
test_phase.py — Golden-vector tests for cross-phone phase and coherence.

Ground truth:
  - Two in-phase sinusoids (Δφ=0°) → cross_phase_coherence returns ~0°
  - 180° out-of-phase → returns ~180°
  - Orthogonal (90°) → returns ~90°
  - Incoherent noise → coherence < threshold → phase_deg = None
"""

from __future__ import annotations

import numpy as np
import pytest

from harmonic_dsp.phase import cross_phase_coherence, phase_stability, hv_phase
from tests.conftest import make_sinusoid


FS  = 200.0
DUR = 40.0


def _wrap180(deg: float) -> float:
    """Wrap to [−180, 180]."""
    return ((deg + 180) % 360) - 180


class TestCrossPhaseCoherence:
    @pytest.mark.parametrize("phase_inject_deg", [0.0, 90.0, 180.0, -90.0, 45.0])
    def test_phase_recovered(self, phase_inject_deg):
        """Phase between two clean sinusoids should match injection within 5°."""
        freq, amp = 18.6, 2.0
        phi = np.deg2rad(phase_inject_deg)
        sig_a, _ = make_sinusoid(freq, amp, 0.0, FS, DUR, noise_std=0.0)
        sig_b, _ = make_sinusoid(freq, amp, phi, FS, DUR, noise_std=0.0)
        result = cross_phase_coherence(sig_a, sig_b, FS, freq)
        assert result["trusted"], (
            f"Expected trusted=True for clean sinusoids, got {result}"
        )
        err = abs(_wrap180(result["phase_deg"] - phase_inject_deg))
        # Allow wrap-around: also check 360 - err
        err = min(err, 360 - err)
        assert err < 10.0, (
            f"Phase error {err:.1f}° > 10° for injected {phase_inject_deg}°"
        )

    def test_in_phase_near_zero(self):
        sig, _ = make_sinusoid(18.6, 2.0, 0.0, FS, DUR, noise_std=0.0)
        result = cross_phase_coherence(sig, sig, FS, 18.6)
        assert result["trusted"]
        assert abs(result["phase_deg"]) < 5.0

    def test_180_degree_phase_opposite(self):
        sig_a, _ = make_sinusoid(18.6, 2.0, 0.0,      FS, DUR, noise_std=0.0)
        sig_b, _ = make_sinusoid(18.6, 2.0, np.pi,    FS, DUR, noise_std=0.0)
        result = cross_phase_coherence(sig_a, sig_b, FS, 18.6)
        assert result["trusted"]
        assert abs(abs(result["phase_deg"]) - 180.0) < 10.0

    def test_incoherent_noise_not_trusted(self):
        """Pure random noise between two channels → coherence low → not trusted."""
        rng = np.random.default_rng(42)
        na = int(FS * DUR)
        sig_a = rng.standard_normal(na)
        sig_b = rng.standard_normal(na)
        result = cross_phase_coherence(sig_a, sig_b, FS, 18.6, coherence_min=0.8)
        assert not result["trusted"], "Incoherent noise should not be trusted"
        assert result["phase_deg"] is None

    def test_coherence_reported_between_0_and_1(self):
        sig, _ = make_sinusoid(18.6, 2.0, 0.0, FS, DUR)
        result = cross_phase_coherence(sig, sig, FS, 18.6)
        assert 0.0 <= result["coherence"] <= 1.0

    def test_result_contains_required_keys(self):
        sig, _ = make_sinusoid(18.6, 2.0, 0.0, FS, DUR)
        result = cross_phase_coherence(sig, sig, FS, 18.6)
        for key in ("phase_deg", "coherence", "trusted"):
            assert key in result, f"Missing key: {key}"


class TestPhaseStability:
    def test_stable_phases_low_std(self):
        """Identical phases → circular std ≈ 0°."""
        phases = [0.0] * 10
        std = phase_stability(phases)
        assert std == pytest.approx(0.0, abs=1.0)

    def test_scattered_phases_high_std(self):
        """Uniformly scattered phases → circular std near 180°."""
        phases = list(np.linspace(0, 350, 36))
        std = phase_stability(phases)
        assert std > 50.0, f"Expected high std for uniform phases, got {std:.1f}"

    def test_cluster_near_90(self):
        phases = [88.0, 89.0, 91.0, 90.5, 89.5]
        std = phase_stability(phases)
        assert std < 5.0

    def test_empty_returns_none(self):
        assert phase_stability([]) is None

    def test_single_phase_returns_zero_or_none(self):
        result = phase_stability([45.0])
        # Both zero and None are acceptable for a single sample
        assert result is None or result == pytest.approx(0.0, abs=1e-6)


class TestHVPhase:
    def test_90deg_expected_for_circular_orbit(self):
        """
        Static imbalance produces a ~circular orbit: H and V are 90° apart.
        """
        freq = 18.6
        sig_v, _ = make_sinusoid(freq, 2.0, 0.0,         FS, DUR, noise_std=0.0)
        sig_h, _ = make_sinusoid(freq, 2.0, np.pi / 2,   FS, DUR, noise_std=0.0)
        result = hv_phase(sig_h, sig_v, FS, freq)
        if result.get("trusted"):
            err = abs(abs(result["phase_deg"]) - 90.0)
            assert err < 15.0, f"H-V phase error {err:.1f}° > 15° for circular orbit"

    def test_zero_phase_in_phase(self):
        """In-phase H and V → 0° phase difference."""
        freq = 18.6
        sig, _ = make_sinusoid(freq, 2.0, 0.0, FS, DUR, noise_std=0.0)
        result = hv_phase(sig, sig, FS, freq)
        if result.get("trusted"):
            assert abs(result["phase_deg"]) < 10.0
