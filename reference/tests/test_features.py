"""
test_features.py — Tests for feature vector assembly (§5.10).

Verifies schema completeness, JSON round-trip, None omission, rounding,
and token-count estimation heuristic.

Output structure (nested):
  fv["machine"]["type"]              — machine metadata nested under "machine"
  fv["sampling"]["fs_hz"]            — sampling info nested under "sampling"
  fv["running_speed_hz"]             — top-level float
  fv["overall_velocity_mm_s"]["A_H"] — velocity per phone/axis (top-level dict)
  fv["severity_zone"]                — top-level string
  fv["orders"]["1x"]                 — top-level dict (order keys preserved from input)
  fv["phase_deg"]                    — top-level dict (omitted if all None)
  fv["coherence"]                    — top-level dict (omitted if all None)
  fv["crest_factor"]                 — top-level float (omitted if None)
  fv["kurtosis"]                     — top-level float (omitted if None)
  fv["phase_stability_deg"]          — top-level float (omitted if None)
  fv["baseline_delta"]               — top-level dict (omitted if None/empty)
"""

from __future__ import annotations

import json
import pytest

from harmonic_dsp.features import (
    build_feature_vector,
    features_to_json,
    estimate_token_count,
)


# ── Minimal valid kwargs for build_feature_vector ────────────────────────────
# velocity_mm_s, phase_deg, coherence are DICTS (per phone/axis)
# fs_hz is a LIST of floats
# baseline_delta is a DICT or None

MINIMAL_KWARGS = dict(
    machine_type="fan",
    rpm_nameplate=1200,
    blades=4,
    mounting="rigid",
    iso_class="I",
    fs_hz=[200.0],                        # list, not float
    duration_s=40.0,
    sync_confidence=0.95,
    band_limit_hz=100.0,
    running_speed_hz=18.6,
    velocity_mm_s={"A_H": 1.4},          # dict, not float
    severity_zone="B",
    orders={
        "0.5x": 0.05,
        "1x":   2.5,                      # note: f"{1.0:g}x" = "1x"
        "2x":   0.4,                      # note: f"{2.0:g}x" = "2x"
        "3x":   0.1,
        "harmonics_count_above_floor": 3,
    },
    axial_to_radial_ratio=0.1,
    phase_deg={"AB_H_1x": 5.0},          # dict, not float
    coherence={"AB_H_1x": 0.95},         # dict, not float
)

FULL_KWARGS = {
    **MINIMAL_KWARGS,
    "velocity_mm_s": {"A_H": 1.4, "A_V": 1.1, "B_H": 1.6},
    "phase_deg": {"AB_H_1x": 5.0, "AB_V_1x": 8.0},
    "coherence": {"AB_H_1x": 0.95, "AB_V_1x": 0.88},
    "crest_factor": 3.2,
    "kurtosis": 2.9,
    "phase_stability_deg": 8.5,
    "baseline_delta": {"A_H": 0.3},      # dict, not float
}


class TestBuildFeatureVector:
    def test_minimal_produces_dict(self):
        fv = build_feature_vector(**MINIMAL_KWARGS)
        assert isinstance(fv, dict)
        assert len(fv) > 0

    def test_none_values_omitted(self):
        """Optional top-level fields that are None must not appear in the output dict."""
        fv = build_feature_vector(**MINIMAL_KWARGS)
        # These are None by default and must be absent from the top-level dict
        for absent in ("crest_factor", "kurtosis", "phase_stability_deg", "baseline_delta"):
            assert absent not in fv, f"None-valued field '{absent}' should be omitted"

    def test_none_values_included_when_provided(self):
        """Optional fields that are explicitly provided must appear."""
        fv = build_feature_vector(**FULL_KWARGS)
        for present in ("crest_factor", "kurtosis", "phase_stability_deg", "baseline_delta"):
            assert present in fv, f"Provided field '{present}' missing from output"

    def test_velocity_rounded(self):
        """Float velocity values must be rounded (to 2 dp per implementation)."""
        kwargs = {**MINIMAL_KWARGS, "velocity_mm_s": {"A_H": 1.23456789}}
        fv = build_feature_vector(**kwargs)
        v = fv["overall_velocity_mm_s"]["A_H"]
        assert isinstance(v, float)
        # rounded to 2 decimal places per _round_opt(v, 2)
        assert v == pytest.approx(1.23, abs=0.005)

    def test_machine_metadata_nested(self):
        """Machine metadata is nested under 'machine' key."""
        fv = build_feature_vector(**MINIMAL_KWARGS)
        assert "machine" in fv
        assert fv["machine"]["type"] == "fan"
        assert fv["machine"]["rpm_nameplate"] == pytest.approx(1200, rel=0.01)
        assert fv["machine"]["iso_class"] == "I"

    def test_sampling_nested(self):
        """Sampling info is nested under 'sampling' key."""
        fv = build_feature_vector(**MINIMAL_KWARGS)
        assert "sampling" in fv
        assert isinstance(fv["sampling"]["fs_hz"], list)
        assert fv["sampling"]["duration_s"] == pytest.approx(40.0, rel=0.01)
        assert "band_limit_hz" in fv["sampling"]

    def test_running_speed_top_level(self):
        fv = build_feature_vector(**MINIMAL_KWARGS)
        assert "running_speed_hz" in fv
        assert fv["running_speed_hz"] == pytest.approx(18.6, rel=0.001)

    def test_overall_velocity_top_level_dict(self):
        """velocity_mm_s input → overall_velocity_mm_s dict in output."""
        fv = build_feature_vector(**MINIMAL_KWARGS)
        assert "overall_velocity_mm_s" in fv
        assert isinstance(fv["overall_velocity_mm_s"], dict)
        assert "A_H" in fv["overall_velocity_mm_s"]
        assert fv["overall_velocity_mm_s"]["A_H"] == pytest.approx(1.4, rel=0.01)

    def test_severity_zone_string(self):
        """severity_zone must be a string zone letter in the output."""
        fv = build_feature_vector(**MINIMAL_KWARGS)
        assert "severity_zone" in fv
        assert isinstance(fv["severity_zone"], str)
        assert fv["severity_zone"] in ("A", "B", "C", "D")

    def test_orders_dict_at_top_level(self):
        """Order amplitudes are stored in top-level 'orders' dict with same keys."""
        fv = build_feature_vector(**MINIMAL_KWARGS)
        assert "orders" in fv
        assert fv["orders"]["1x"] == pytest.approx(2.5, rel=0.01)
        assert fv["orders"]["2x"] == pytest.approx(0.4, rel=0.01)

    def test_harmonics_count_in_orders(self):
        """harmonics_count_above_floor is kept as int in orders dict."""
        fv = build_feature_vector(**MINIMAL_KWARGS)
        assert "harmonics_count_above_floor" in fv["orders"]
        assert isinstance(fv["orders"]["harmonics_count_above_floor"], int)

    def test_phase_top_level(self):
        """phase_deg dict appears at top level when non-empty."""
        fv = build_feature_vector(**MINIMAL_KWARGS)
        assert "phase_deg" in fv
        assert isinstance(fv["phase_deg"], dict)

    def test_coherence_top_level(self):
        """coherence dict appears at top level when non-empty."""
        fv = build_feature_vector(**MINIMAL_KWARGS)
        assert "coherence" in fv
        assert isinstance(fv["coherence"], dict)

    def test_axial_ratio_top_level(self):
        fv = build_feature_vector(**MINIMAL_KWARGS)
        assert "axial_to_radial_ratio" in fv
        assert fv["axial_to_radial_ratio"] == pytest.approx(0.1, rel=0.01)

    def test_band_limit_in_sampling(self):
        """band_limit_hz appears nested under 'sampling'."""
        fv = build_feature_vector(**MINIMAL_KWARGS)
        assert fv["sampling"]["band_limit_hz"] == pytest.approx(100.0, rel=0.01)

    def test_full_kwargs_no_error(self):
        """Full kwargs (all optional fields populated) should not raise."""
        fv = build_feature_vector(**FULL_KWARGS)
        assert isinstance(fv, dict)
        assert "crest_factor" in fv
        assert "kurtosis" in fv
        assert "baseline_delta" in fv


class TestFeaturesToJson:
    def test_valid_json(self):
        fv = build_feature_vector(**MINIMAL_KWARGS)
        js = features_to_json(fv)
        parsed = json.loads(js)
        assert isinstance(parsed, dict)

    def test_round_trip_machine_type(self):
        fv = build_feature_vector(**MINIMAL_KWARGS)
        js = features_to_json(fv)
        parsed = json.loads(js)
        assert parsed["machine"]["type"] == fv["machine"]["type"]
        assert parsed["severity_zone"] == fv["severity_zone"]

    def test_compact_no_indent(self):
        fv = build_feature_vector(**MINIMAL_KWARGS)
        js_compact = features_to_json(fv, indent=None)
        assert "\n" not in js_compact, "Compact JSON should have no newlines"

    def test_indented(self):
        fv = build_feature_vector(**MINIMAL_KWARGS)
        js_indented = features_to_json(fv, indent=2)
        assert "\n" in js_indented, "Indented JSON should have newlines"


class TestEstimateTokenCount:
    def test_typical_feature_vector_under_nano4_limit(self):
        """A realistic feature vector must stay well under Nano 4's 4000-token limit."""
        fv = build_feature_vector(**FULL_KWARGS)
        js = features_to_json(fv)
        tokens = estimate_token_count(js)
        assert tokens < 500, (
            f"Feature JSON uses {tokens} estimated tokens (expected < 500 for JSON alone)"
        )

    def test_heuristic_scales_with_length(self):
        """Longer JSON → more tokens (monotone)."""
        fv_small = build_feature_vector(**MINIMAL_KWARGS)
        fv_large = build_feature_vector(**FULL_KWARGS)
        js_small = features_to_json(fv_small)
        js_large = features_to_json(fv_large)
        assert estimate_token_count(js_large) >= estimate_token_count(js_small)

    def test_returns_int(self):
        fv = build_feature_vector(**MINIMAL_KWARGS)
        js = features_to_json(fv)
        assert isinstance(estimate_token_count(js), int)
