"""
gate.py — Physics gate: deterministic consistency checks on AI model output (§6.6).

The model produces a structured JSON hypothesis; the gate checks every claim
against the DSP-computed features. Any inconsistency is flagged, and the
hypothesis is rejected or downgraded before it reaches the user.

This is analogous to outlier rejection in an estimator: the physics-based
expected feature distribution provides the "nominal model," and the gate
rejects hypotheses that lie outside the feasible region.

Gate actions (logged to Firestore inference_logs):
    ACCEPT         — hypothesis is consistent with features
    REJECT         — hypothesis violates a hard constraint → escalate to cloud
    DOWNGRADE      — reduce probability of a hypothesis; keep but flag
    OVERWRITE_ZONE — severity zone from model must be replaced by DSP value
    SUGGEST_TEST   — add a next_test recommendation

Logging
-------
Every gate action is recorded. The "hallucinations caught" metric is the
count of REJECT + OVERWRITE_ZONE actions across all sessions.
"""

from __future__ import annotations

from dataclasses import dataclass, field
import math
from typing import Any, Literal


GateAction = Literal["ACCEPT", "REJECT", "DOWNGRADE", "OVERWRITE_ZONE", "SUGGEST_TEST"]

FAULT_ENUM = {
    "healthy", "static_imbalance", "couple_imbalance",
    "angular_misalignment", "parallel_misalignment",
    "structural_looseness", "rotating_looseness",
    "bent_shaft", "resonance", "unknown",
}


@dataclass
class GateResult:
    accepted: bool
    actions: list[dict[str, Any]] = field(default_factory=list)
    corrected_hypothesis: dict[str, Any] | None = None  # modified copy if changes made
    escalate_to_cloud: bool = False

    def log_action(
        self,
        action: GateAction,
        fault: str | None,
        reason: str,
        new_next_test: str | None = None,
    ) -> None:
        self.actions.append({
            "action":       action,
            "fault":        fault,
            "reason":       reason,
            "next_test":    new_next_test,
        })


def _get_max_radial(velocity_mm_s: dict[str, float | None], phone: str = "A") -> float:
    """Return max of H and V velocity for a given phone."""
    h = velocity_mm_s.get(f"{phone}_H") or 0.0
    v = velocity_mm_s.get(f"{phone}_V") or 0.0
    return max(float(h), float(v))


def _get_axial(velocity_mm_s: dict[str, float | None], phone: str = "A") -> float:
    return float(velocity_mm_s.get(f"{phone}_A") or 0.0)


# fault_detail values allowed by prompts/hypothesis_schema.json.
KNOWN_FAULT_DETAILS = {
    "static_imbalance", "couple_imbalance", "angular_misalignment", "parallel_misalignment",
    "structural_looseness", "rotating_looseness", "bent_shaft", "resonance", "healthy", "unknown",
}


def physics_gate(
    hypothesis: dict[str, Any],
    features: dict[str, Any],
    # Remote Config values (use defaults if not provided)
    coherence_min: float = 0.8,
    phase_in_phase_max_deg: float = 45.0,    # for static imbalance acceptance
    phase_out_of_phase_min_deg: float = 135.0, # for couple imbalance acceptance
    misalignment_min_2x_ratio: float = 0.5,  # 2x/1x OR axial/radial
    looseness_min_harmonics: int = 3,
    phase_stability_max_deg: float = 30.0,
) -> GateResult:
    """
    Run all physics gate checks on a model-produced hypothesis.

    Parameters
    ----------
    hypothesis : dict — structured output from the AI model (§6.5 schema):
        {
          "hypotheses":  [{"fault": str, "p": float}, ...],
          "severity_zone": str,
          "evidence":    [...],
          "next_test":   str,
        }
    features   : dict — feature vector from build_feature_vector (§5.10)
    ... thresholds from Remote Config

    Returns
    -------
    GateResult — with actions log, accepted flag, corrected hypothesis, escalate flag
    """
    result = GateResult(accepted=True)
    # Malformed model output must not crash the gate: skip non-objects, clamp p to [0, 1].
    clean_hyps = []
    for h in hypothesis.get("hypotheses") or []:
        if not isinstance(h, dict):
            continue
        h = dict(h)
        try:
            p_val = float(h.get("p", 0.0))
        except (TypeError, ValueError):
            p_val = 0.0
        if not math.isfinite(p_val):
            p_val = 0.0
        h["p"] = min(1.0, max(0.0, p_val))
        if not isinstance(h.get("fault"), str):
            h["fault"] = "unknown"
        clean_hyps.append(h)
    corrected = {
        "hypotheses":    clean_hyps,
        "severity_zone": hypothesis.get("severity_zone", "A"),
        "evidence":      list(hypothesis.get("evidence", [])),
        "next_test":     hypothesis.get("next_test", "none"),
    }

    orders       = features.get("orders", {})
    phase_deg    = features.get("phase_deg", {})
    coherence    = features.get("coherence", {})
    vel          = features.get("overall_velocity_mm_s", {})
    dsp_zone     = features.get("severity_zone", "A")
    harmonics    = int(orders.get("harmonics_count_above_floor", 0))
    ax_rad_ratio = float(features.get("axial_to_radial_ratio") or 0.0)
    phase_stab   = features.get("phase_stability_deg")

    amp_1x  = float(orders.get("1x",  0.0))
    amp_2x  = float(orders.get("2x",  0.0))
    amp_05x = float(orders.get("0.5x", 0.0))

    # ── 1. Severity zone: always overwrite with DSP value ─────────────────────
    if corrected["severity_zone"] != dsp_zone:
        result.log_action(
            "OVERWRITE_ZONE", None,
            f"Model reported zone {corrected['severity_zone']} but DSP computed {dsp_zone}; "
            "overwriting — model must never override measured severity."
        )
        corrected["severity_zone"] = dsp_zone
        result.accepted = False  # Flag that model was wrong here

    # ── 2. Per-fault checks ───────────────────────────────────────────────────
    new_hypotheses = []
    for hyp in corrected["hypotheses"]:
        fault = hyp.get("fault", "unknown")
        p     = float(hyp.get("p", 0.0))

        if fault not in KNOWN_FAULT_DETAILS:
            result.log_action("REJECT", fault, f"'{fault}' is not one of the fault names the schema allows.")
            result.escalate_to_cloud = True
            continue

        keep = True

        # ── Imbalance family ──────────────────────────────────────────────────
        if fault in ("static_imbalance", "couple_imbalance"):
            # 1× must be the dominant order for imbalance
            if amp_1x < amp_2x and amp_2x > 1e-6:
                result.log_action(
                    "REJECT", fault,
                    f"Imbalance claimed but 2x ({amp_2x:.4f}) > 1x ({amp_1x:.4f}). "
                    "Imbalance shows dominant 1x."
                )
                result.escalate_to_cloud = True
                keep = False
            elif harmonics > 3:
                result.log_action(
                    "DOWNGRADE", fault,
                    f"Imbalance claimed but harmonics_count_above_floor={harmonics} (> 3); "
                    "looseness may also be present."
                )
                hyp = dict(hyp, p=p * 0.6)

        if fault == "static_imbalance" and keep:
            # DE–NDE radial phase should be ≈ 0° (in-phase)
            phase_ab_h = phase_deg.get("AB_H_1x")
            coh_ab_h   = coherence.get("AB_H_1x", 0.0)
            if (phase_ab_h is not None
                    and float(coh_ab_h) >= coherence_min
                    and abs(float(phase_ab_h)) > phase_in_phase_max_deg):
                result.log_action(
                    "DOWNGRADE", fault,
                    f"Static imbalance claimed, but DE–NDE radial phase = "
                    f"{phase_ab_h:.1f}° (expected < {phase_in_phase_max_deg}°). "
                    "May be couple imbalance or misalignment.",
                    new_next_test="radial_phase_DE_NDE",
                )
                if corrected["next_test"] == "none":
                    corrected["next_test"] = "radial_phase_DE_NDE"
                hyp = dict(hyp, p=p * 0.7)

        if fault == "couple_imbalance" and keep:
            phase_ab_h = phase_deg.get("AB_H_1x")
            coh_ab_h   = coherence.get("AB_H_1x", 0.0)
            if (phase_ab_h is not None
                    and float(coh_ab_h) >= coherence_min
                    and abs(float(phase_ab_h)) < phase_out_of_phase_min_deg):
                result.log_action(
                    "DOWNGRADE", fault,
                    f"Couple imbalance claimed but DE–NDE radial phase = "
                    f"{phase_ab_h:.1f}° (expected ≥ {phase_out_of_phase_min_deg}°).",
                    new_next_test="radial_phase_DE_NDE",
                )
                hyp = dict(hyp, p=p * 0.7)

        # ── Misalignment family ───────────────────────────────────────────────
        if fault in ("angular_misalignment", "parallel_misalignment"):
            two_x_ratio = amp_2x / (amp_1x + 1e-12)
            is_axial_strong = ax_rad_ratio >= misalignment_min_2x_ratio
            is_2x_strong    = two_x_ratio  >= misalignment_min_2x_ratio
            if not (is_2x_strong or is_axial_strong):
                result.log_action(
                    "REJECT", fault,
                    f"Misalignment claimed but 2x/1x={two_x_ratio:.2f} and "
                    f"axial/radial={ax_rad_ratio:.2f} (both < {misalignment_min_2x_ratio}). "
                    "No misalignment signature in features."
                )
                result.escalate_to_cloud = True
                keep = False

        # ── Looseness family ──────────────────────────────────────────────────
        if fault in ("structural_looseness", "rotating_looseness"):
            is_unstable = (
                phase_stab is not None and float(phase_stab) > phase_stability_max_deg
            )
            has_harmonics = harmonics >= looseness_min_harmonics
            has_subharmonic = amp_05x > 0.1 * amp_1x  # 0.5× at >10% of 1×
            if not (has_harmonics or has_subharmonic or is_unstable):
                result.log_action(
                    "REJECT", fault,
                    f"Looseness claimed but harmonics_count={harmonics} < "
                    f"{looseness_min_harmonics}, no 0.5× subharmonic, and phase is stable. "
                    "No looseness signature in features."
                )
                result.escalate_to_cloud = True
                keep = False

        # ── Resonance requires speed-sweep evidence ───────────────────────────
        if fault == "resonance":
            result.log_action(
                "DOWNGRADE", fault,
                "Resonance cannot be confirmed without vary_speed evidence (amplitude "
                "should change sharply with speed). Flagging as preliminary.",
                new_next_test="vary_speed",
            )
            if corrected["next_test"] == "none":
                corrected["next_test"] = "vary_speed"
            hyp = dict(hyp, p=p * 0.5)

        # ── Healthy requires zone A/B and no fault signature ──────────────────
        if fault == "healthy":
            if dsp_zone in ("C", "D"):
                result.log_action(
                    "REJECT", fault,
                    f"'Healthy' claimed but DSP severity zone is {dsp_zone}. "
                    "Healthy only valid in zone A or B."
                )
                result.escalate_to_cloud = True
                keep = False
            elif harmonics > 2 or amp_05x > 0.2 * amp_1x:
                result.log_action(
                    "DOWNGRADE", fault,
                    "Healthy claimed but elevated harmonics or subharmonics present."
                )
                hyp = dict(hyp, p=p * 0.6)

        if keep:
            new_hypotheses.append(hyp)

    corrected["hypotheses"] = new_hypotheses

    # Mark as rejected if no hypotheses survived
    if not new_hypotheses:
        result.accepted = False
        result.escalate_to_cloud = True
        result.log_action(
            "REJECT", None,
            "All hypotheses were rejected by the physics gate. Escalating to cloud."
        )
    elif len(result.actions) > 0:
        result.accepted = False  # Was modified

    result.corrected_hypothesis = corrected
    return result
