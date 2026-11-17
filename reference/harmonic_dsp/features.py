"""
features.py — Assemble the full feature vector sent to the AI model (§5.10).

The feature JSON is the ONLY input the AI sees. It never sees raw samples.
DSP computes all numbers; the model reasons over structured features.

Schema: see §5.10 of HARMONIC_PROJECT.md and /prompts/hypothesis_schema.json.
The assembler trims optional fields if they are None (phone not available,
measurement not taken) so the JSON stays compact within on-device token limits.

Token budget (Android Nano 4): 4000 total; features ≈ 300–600 tokens.
Keep the JSON minimal — only the keys the model actually uses.
"""

from __future__ import annotations

import json
from typing import Any


def _round_opt(value: float | None, ndigits: int = 3) -> float | None:
    """Round a float to ndigits, returning None if value is None."""
    return round(float(value), ndigits) if value is not None else None


def build_feature_vector(
    # Machine metadata
    machine_type: str,                    # "fan" | "pump" | "motor" | "compressor" | "other"
    rpm_nameplate: float,
    blades: int | None,
    mounting: str,                        # "rigid" | "flexible"
    iso_class: str,                       # "I" | "II" | "group2_rigid" | ...

    # Recording metadata
    fs_hz: list[float],                   # actual sample rates [phone_A, phone_B, ...]
    duration_s: float,
    sync_confidence: float,               # from estimate_offset SNR → [0, 1]
    band_limit_hz: float,                 # fs_min / 2

    # Running speed
    running_speed_hz: float,

    # Per-phone overall velocity RMS (mm/s) for each axis
    # Keys: "A_H", "A_V", "A_A", "B_H", "B_V", "B_A"  (phone letter, axis letter)
    velocity_mm_s: dict[str, float | None],

    # ISO severity zone (DSP-computed, authoritative)
    severity_zone: str,                   # "A" | "B" | "C" | "D"

    # Order amplitudes (m/s² peak) from extract_orders on dominant phone
    orders: dict[str, float],             # "0.5x", "1x", "2x", ...

    # Axial-to-radial ratio (axial / radial of dominant phone, at 1×)
    axial_to_radial_ratio: float | None,

    # Cross-phone phase/coherence (from cross_phase_coherence)
    # Keys: "AB_H_1x", "AB_V_1x", "A_HV_1x", "AB_A_1x", ...
    phase_deg: dict[str, float | None],
    coherence: dict[str, float | None],

    # Time-domain statistics (dominant phone, radial channel)
    crest_factor: float | None = None,
    kurtosis: float | None = None,

    # Phase stability across repeated readings (°)
    phase_stability_deg: float | None = None,

    # Baseline comparison (% change from last healthy session)
    baseline_delta: dict[str, float | None] | None = None,

) -> dict[str, Any]:
    """
    Assemble the feature JSON dict ready to be sent to the AI model.

    Parameters match §5.10 of the project spec. See module docstring for token budget.

    Returns
    -------
    features : dict — structured feature vector (JSON-serializable)
                       with None values omitted to save tokens.
    """
    def omit_none(d: dict) -> dict:
        return {k: v for k, v in d.items() if v is not None}

    features: dict[str, Any] = {
        "machine": omit_none({
            "type":         machine_type,
            "rpm_nameplate": round(rpm_nameplate, 1),
            "blades":       blades,
            "mounting":     mounting,
            "iso_class":    iso_class,
        }),
        "sampling": {
            "fs_hz":        [round(f, 1) for f in fs_hz],
            "duration_s":   round(duration_s, 1),
            "sync_conf":    round(sync_confidence, 3),
            "band_limit_hz": round(band_limit_hz, 1),
        },
        "running_speed_hz": round(running_speed_hz, 3),
        "overall_velocity_mm_s": omit_none({k: _round_opt(v, 2) for k, v in velocity_mm_s.items()}),
        "severity_zone":    severity_zone,
        "orders": {
            k: round(float(v), 4) for k, v in orders.items()
            if k != "harmonics_count_above_floor" and v is not None
        } | {
            "harmonics_count_above_floor": int(orders.get("harmonics_count_above_floor", 0))
        },
    }

    if axial_to_radial_ratio is not None:
        features["axial_to_radial_ratio"] = round(axial_to_radial_ratio, 3)

    phase_clean = omit_none({k: _round_opt(v, 1) for k, v in phase_deg.items()})
    if phase_clean:
        features["phase_deg"] = phase_clean

    coh_clean = omit_none({k: _round_opt(v, 3) for k, v in coherence.items()})
    if coh_clean:
        features["coherence"] = coh_clean

    if crest_factor is not None:
        features["crest_factor"] = round(crest_factor, 2)
    if kurtosis is not None:
        features["kurtosis"] = round(kurtosis, 2)
    if phase_stability_deg is not None:
        features["phase_stability_deg"] = round(phase_stability_deg, 1)
    if baseline_delta:
        bd_clean = omit_none({k: _round_opt(v, 1) for k, v in baseline_delta.items()})
        if bd_clean:
            features["baseline_delta"] = bd_clean

    return features


def features_to_json(features: dict[str, Any], indent: int | None = None) -> str:
    """Serialize features dict to a compact JSON string."""
    return json.dumps(features, separators=(",", ":") if indent is None else None, indent=indent)


def estimate_token_count(features_json: str) -> int:
    """
    Rough token count estimate for a JSON string.
    Uses ~4 chars/token heuristic — good enough for budget checks.
    """
    return max(1, len(features_json) // 4)
