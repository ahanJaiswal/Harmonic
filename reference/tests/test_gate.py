"""
test_gate.py — Physics gate golden-vector tests (§6.6).

Each test injects a synthetic feature dict and a structured hypothesis,
then verifies the gate's accept/reject decision and action log.

Hypothesis schema (what the AI model must produce):
  {
    "hypotheses":    [{"fault": str, "p": float}, ...],
    "severity_zone": str,          # "A" | "B" | "C" | "D"
    "evidence":      [...],
    "next_test":     str,
  }

Features schema (from build_feature_vector output / §5.10):
  features["orders"]["1x"],            # f"{1.0:g}x" = "1x"
  features["orders"]["2x"],
  features["orders"]["0.5x"],
  features["orders"]["harmonics_count_above_floor"]  — int
  features["phase_deg"]["AB_H_1x"]                   — may be absent/None
  features["coherence"]["AB_H_1x"]                   — may be absent/None
  features["overall_velocity_mm_s"]["A_H"]            — mm/s
  features["severity_zone"]                           — DSP-authoritative zone
  features["axial_to_radial_ratio"]                   — float
  features["phase_stability_deg"]                     — float | None

Gate acceptance logic (result.accepted = False if any action logged):
  OVERWRITE_ZONE  — hypothesis zone != DSP zone                → accepted=False (direct)
  REJECT          — hard constraint violated                    → accepted=False (via action count)
  DOWNGRADE       — soft constraint; p reduced                 → accepted=False (via action count)
  SUGGEST_TEST    — info only; also increments action count    → accepted=False (via action count)
"""

from __future__ import annotations

import pytest
from harmonic_dsp.gate import physics_gate, GateResult


# ── Helpers ───────────────────────────────────────────────────────────────────

def _hypothesis(fault: str, confidence: float = 0.85, zone: str = "B") -> dict:
    """Build a well-formed model hypothesis dict."""
    return {
        "hypotheses":    [{"fault": fault, "p": confidence}],
        "severity_zone": zone,
        "evidence":      [],
        "next_test":     "none",
    }


def _features(
    *,
    zone: str = "B",
    amp_1x: float = 2.5,
    amp_2x: float = 0.4,
    amp_05x: float = 0.05,
    harmonics: int = 2,
    phase_ab_h: float | None = 10.0,   # AB_H_1x cross-phase
    coh_ab_h: float | None = 0.95,     # AB_H_1x coherence
    axial_ratio: float = 0.1,
    phase_stability: float | None = None,
) -> dict:
    """
    Build a feature dict matching the output of build_feature_vector.

    Defaults represent a healthy zone-B machine with clear 1× imbalance:
      1x >> 2x, in-phase (10°), high coherence, 2 harmonics, low axial ratio.
    """
    feat: dict = {
        "severity_zone": zone,
        "orders": {
            "1x":    amp_1x,
            "2x":    amp_2x,
            "0.5x":  amp_05x,
            "harmonics_count_above_floor": harmonics,
        },
        "overall_velocity_mm_s": {"A_H": 1.4},
        "axial_to_radial_ratio": axial_ratio,
    }
    if phase_ab_h is not None:
        feat["phase_deg"] = {"AB_H_1x": phase_ab_h}
    if coh_ab_h is not None:
        feat["coherence"] = {"AB_H_1x": coh_ab_h}
    if phase_stability is not None:
        feat["phase_stability_deg"] = phase_stability
    return feat


def _action_types(result: GateResult) -> list[str]:
    return [a["action"] for a in result.actions]


# ── Zone overwrite ─────────────────────────────────────────────────────────────

class TestZoneOverwrite:
    def test_zone_mismatch_overwrites(self):
        """Model claims zone A, DSP computed zone B → OVERWRITE_ZONE, accepted=False."""
        hyp = _hypothesis("static_imbalance", zone="A")
        feat = _features(zone="B")
        result = physics_gate(hyp, feat)
        assert not result.accepted
        assert "OVERWRITE_ZONE" in _action_types(result)
        assert result.corrected_hypothesis["severity_zone"] == "B"

    def test_zone_match_no_overwrite(self):
        """When hypothesis and DSP zone agree, no OVERWRITE_ZONE is logged."""
        hyp = _hypothesis("static_imbalance", zone="B")
        feat = _features(zone="B", phase_ab_h=10.0, coh_ab_h=0.95)
        result = physics_gate(hyp, feat)
        assert "OVERWRITE_ZONE" not in _action_types(result)


# ── Static imbalance ──────────────────────────────────────────────────────────

class TestStaticImbalance:
    def test_clean_accept(self):
        """
        Static imbalance with:
          1× >> 2×, harmonics ≤ 3, in-phase (< 45°), high coherence, zones match
        → accepted=True, no actions.
        """
        hyp  = _hypothesis("static_imbalance", zone="B")
        feat = _features(zone="B", amp_1x=2.5, amp_2x=0.4,
                         harmonics=2, phase_ab_h=10.0, coh_ab_h=0.95)
        result = physics_gate(hyp, feat)
        assert result.accepted, f"Expected accepted=True, actions={result.actions}"
        assert result.actions == []

    def test_reject_when_2x_dominates(self):
        """2× > 1× with imbalance claim → REJECT (mismatched signature)."""
        hyp  = _hypothesis("static_imbalance", zone="B")
        feat = _features(zone="B", amp_1x=1.0, amp_2x=2.5)
        result = physics_gate(hyp, feat)
        assert not result.accepted
        assert "REJECT" in _action_types(result)
        assert result.escalate_to_cloud

    def test_downgrade_when_too_many_harmonics(self):
        """harmonics_count > 3 → DOWNGRADE (looseness may coexist)."""
        hyp  = _hypothesis("static_imbalance", zone="B")
        feat = _features(zone="B", amp_1x=2.5, amp_2x=0.4, harmonics=5)
        result = physics_gate(hyp, feat)
        assert not result.accepted
        assert "DOWNGRADE" in _action_types(result)
        # Probability should be reduced
        h = result.corrected_hypothesis["hypotheses"][0]
        assert h["p"] < 0.85

    def test_downgrade_out_of_phase(self):
        """DE–NDE phase > 45° with high coherence → DOWNGRADE for static imbalance."""
        hyp  = _hypothesis("static_imbalance", zone="B")
        feat = _features(zone="B", phase_ab_h=90.0, coh_ab_h=0.95)
        result = physics_gate(hyp, feat)
        assert not result.accepted
        assert "DOWNGRADE" in _action_types(result)

    def test_no_downgrade_for_phase_when_low_coherence(self):
        """Low coherence → phase measurement is not trusted; no phase-based DOWNGRADE."""
        hyp  = _hypothesis("static_imbalance", zone="B")
        feat = _features(zone="B", phase_ab_h=90.0, coh_ab_h=0.3)  # low coh
        result = physics_gate(hyp, feat)
        # DOWNGRADE may still happen from other checks, but not from phase
        phase_actions = [
            a for a in result.actions
            if "phase" in a.get("reason", "").lower()
        ]
        assert len(phase_actions) == 0, "Phase downgrade must not fire when coherence < 0.8"

    def test_no_downgrade_phase_absent(self):
        """No phase data → no phase-based DOWNGRADE."""
        hyp  = _hypothesis("static_imbalance", zone="B")
        feat = _features(zone="B", phase_ab_h=None, coh_ab_h=None)
        result = physics_gate(hyp, feat)
        # Shouldn't see any phase-related downgrade
        phase_actions = [a for a in result.actions if "phase" in a.get("reason", "").lower()]
        assert len(phase_actions) == 0


# ── Couple imbalance ──────────────────────────────────────────────────────────

class TestCoupleImbalance:
    def test_clean_accept_out_of_phase(self):
        """Couple imbalance: 1× >> 2×, out-of-phase (≥ 135°), high coherence → accepted."""
        hyp  = _hypothesis("couple_imbalance", zone="B")
        feat = _features(zone="B", amp_1x=2.5, amp_2x=0.4,
                         harmonics=2, phase_ab_h=170.0, coh_ab_h=0.95)
        result = physics_gate(hyp, feat)
        assert result.accepted, f"Expected accepted=True, actions={result.actions}"

    def test_downgrade_when_in_phase(self):
        """Phase < 135° with high coherence for couple imbalance → DOWNGRADE."""
        hyp  = _hypothesis("couple_imbalance", zone="B")
        feat = _features(zone="B", phase_ab_h=30.0, coh_ab_h=0.95)
        result = physics_gate(hyp, feat)
        assert not result.accepted
        assert "DOWNGRADE" in _action_types(result)


# ── Misalignment ──────────────────────────────────────────────────────────────

class TestMisalignment:
    def test_angular_accept_via_2x(self):
        """2×/1× ≥ 0.5 → misalignment signature present, accepted."""
        hyp  = _hypothesis("angular_misalignment", zone="B")
        feat = _features(zone="B", amp_1x=1.0, amp_2x=0.8,
                         phase_ab_h=None, coh_ab_h=None)
        result = physics_gate(hyp, feat)
        assert result.accepted, f"Expected accepted=True, actions={result.actions}"

    def test_parallel_accept_via_axial(self):
        """Axial/radial ≥ 0.5 alone is enough for misalignment acceptance."""
        hyp  = _hypothesis("parallel_misalignment", zone="B")
        feat = _features(zone="B", amp_1x=2.0, amp_2x=0.5,
                         axial_ratio=0.6,
                         phase_ab_h=None, coh_ab_h=None)
        result = physics_gate(hyp, feat)
        assert result.accepted, f"Expected accepted=True, actions={result.actions}"

    def test_reject_no_misalignment_signature(self):
        """Neither 2×/1× nor axial/radial ≥ 0.5 → REJECT misalignment claim."""
        hyp  = _hypothesis("angular_misalignment", zone="B")
        feat = _features(zone="B", amp_1x=2.5, amp_2x=0.4,
                         axial_ratio=0.1,
                         phase_ab_h=None, coh_ab_h=None)
        result = physics_gate(hyp, feat)
        assert not result.accepted
        assert "REJECT" in _action_types(result)
        assert result.escalate_to_cloud


# ── Looseness ─────────────────────────────────────────────────────────────────

class TestLooseness:
    def test_structural_looseness_accept_many_harmonics(self):
        """harmonics_count ≥ 3 → looseness signature confirmed, accepted."""
        hyp  = _hypothesis("structural_looseness", zone="B")
        feat = _features(zone="B", harmonics=5, amp_1x=1.5, amp_2x=0.8,
                         phase_ab_h=None, coh_ab_h=None)
        result = physics_gate(hyp, feat)
        assert result.accepted, f"Expected accepted=True, actions={result.actions}"

    def test_structural_looseness_accept_subharmonic(self):
        """0.5× > 10% of 1× → subharmonic present, looseness accepted."""
        hyp  = _hypothesis("structural_looseness", zone="B")
        feat = _features(zone="B", harmonics=1, amp_1x=2.0, amp_05x=0.3,
                         phase_ab_h=None, coh_ab_h=None)
        result = physics_gate(hyp, feat)
        assert result.accepted, f"Expected accepted=True, actions={result.actions}"

    def test_rotating_looseness_accept_unstable_phase(self):
        """phase_stability_deg > 30° → unstable, looseness accepted."""
        hyp  = _hypothesis("rotating_looseness", zone="B")
        feat = _features(zone="B", harmonics=1, amp_1x=2.0, amp_05x=0.0,
                         phase_ab_h=None, coh_ab_h=None,
                         phase_stability=45.0)
        result = physics_gate(hyp, feat)
        assert result.accepted, f"Expected accepted=True, actions={result.actions}"

    def test_reject_no_looseness_signature(self):
        """No harmonics, no subharmonic, stable phase → REJECT looseness."""
        hyp  = _hypothesis("structural_looseness", zone="B")
        feat = _features(zone="B", harmonics=1, amp_1x=2.0,
                         amp_05x=0.0, phase_stability=5.0,
                         phase_ab_h=None, coh_ab_h=None)
        result = physics_gate(hyp, feat)
        assert not result.accepted
        assert "REJECT" in _action_types(result)


# ── Resonance ─────────────────────────────────────────────────────────────────

class TestResonance:
    def test_resonance_always_downgraded(self):
        """Resonance requires speed-sweep evidence; gate always DOWNGRADEs and suggests test."""
        hyp  = _hypothesis("resonance", zone="B")
        feat = _features(zone="B", phase_ab_h=None, coh_ab_h=None)
        result = physics_gate(hyp, feat)
        assert not result.accepted
        assert "DOWNGRADE" in _action_types(result)
        assert result.corrected_hypothesis["next_test"] == "vary_speed"

    def test_resonance_p_reduced(self):
        hyp  = _hypothesis("resonance", zone="B")
        feat = _features(zone="B", phase_ab_h=None, coh_ab_h=None)
        result = physics_gate(hyp, feat)
        h = result.corrected_hypothesis["hypotheses"][0]
        assert h["p"] < 0.85 * 0.6  # p *= 0.5


# ── Healthy ───────────────────────────────────────────────────────────────────

class TestHealthy:
    def test_healthy_accepted_zone_a(self):
        """'Healthy' claim with DSP zone A and no fault signature → accepted."""
        hyp  = _hypothesis("healthy", zone="A")
        feat = _features(zone="A", harmonics=0, amp_05x=0.0,
                         phase_ab_h=None, coh_ab_h=None)
        result = physics_gate(hyp, feat)
        assert result.accepted, f"Expected accepted=True, actions={result.actions}"

    def test_healthy_rejected_zone_c(self):
        """'Healthy' in zone C → REJECT."""
        hyp  = _hypothesis("healthy", zone="C")
        feat = _features(zone="C", harmonics=0, amp_05x=0.0,
                         phase_ab_h=None, coh_ab_h=None)
        result = physics_gate(hyp, feat)
        assert not result.accepted
        assert "REJECT" in _action_types(result)

    def test_healthy_downgraded_elevated_harmonics(self):
        """'Healthy' but harmonics > 2 → DOWNGRADE."""
        hyp  = _hypothesis("healthy", zone="A")
        feat = _features(zone="A", harmonics=4, amp_05x=0.0,
                         phase_ab_h=None, coh_ab_h=None)
        result = physics_gate(hyp, feat)
        assert not result.accepted
        assert "DOWNGRADE" in _action_types(result)


# ── GateResult dataclass ──────────────────────────────────────────────────────

class TestGateResult:
    def test_all_rejected_escalates(self):
        """If all hypotheses are rejected, escalate_to_cloud must be True."""
        hyp  = _hypothesis("angular_misalignment", zone="B")
        feat = _features(zone="B", amp_1x=2.5, amp_2x=0.1,
                         axial_ratio=0.05,
                         phase_ab_h=None, coh_ab_h=None)
        result = physics_gate(hyp, feat)
        assert result.escalate_to_cloud

    def test_corrected_hypothesis_always_present(self):
        """corrected_hypothesis must be populated regardless of outcome."""
        hyp  = _hypothesis("static_imbalance", zone="B")
        feat = _features(zone="B")
        result = physics_gate(hyp, feat)
        assert result.corrected_hypothesis is not None
        assert "hypotheses" in result.corrected_hypothesis
        assert "severity_zone" in result.corrected_hypothesis

    def test_accepted_true_returns_non_empty_hypotheses(self):
        """When accepted=True, corrected_hypothesis must have at least one hypothesis."""
        hyp  = _hypothesis("static_imbalance", zone="B")
        feat = _features(zone="B", phase_ab_h=10.0, coh_ab_h=0.95)
        result = physics_gate(hyp, feat)
        assert result.accepted
        assert len(result.corrected_hypothesis["hypotheses"]) > 0
