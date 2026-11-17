"""
test_severity.py — Golden-vector tests for ISO 10816/20816 zone classification.

Zone boundaries (from §5.8 and ZONE_THRESHOLDS):
    class_I         : A<0.71, B<1.8, C<4.5, D≥4.5  mm/s RMS
    group2_rigid    : A<1.4,  B<2.8, C<4.5, D≥4.5
    group2_flexible : A<2.3,  B<4.5, C<7.1, D≥7.1
"""

from __future__ import annotations

import json
import pytest

from harmonic_dsp.severity import classify_zone, ZONE_THRESHOLDS, parse_remote_config_thresholds


class TestClassifyZone:
    # ── class_I ──────────────────────────────────────────────────────────────
    @pytest.mark.parametrize("v_rms, expected_zone", [
        (0.1,  "A"),
        (0.70, "A"),
        (0.72, "B"),
        (1.79, "B"),
        (1.81, "C"),
        (4.49, "C"),
        (4.51, "D"),
        (10.0, "D"),
    ])
    def test_class_I_boundaries(self, v_rms, expected_zone):
        assert classify_zone(v_rms, "class_I") == expected_zone, (
            f"class_I v={v_rms}: expected {expected_zone}, "
            f"got {classify_zone(v_rms, 'class_I')}"
        )

    # ── group2_rigid ─────────────────────────────────────────────────────────
    @pytest.mark.parametrize("v_rms, expected_zone", [
        (0.5,  "A"),
        (1.39, "A"),
        (1.41, "B"),
        (2.79, "B"),
        (2.81, "C"),
        (4.49, "C"),
        (4.51, "D"),
    ])
    def test_group2_rigid_boundaries(self, v_rms, expected_zone):
        assert classify_zone(v_rms, "group2_rigid") == expected_zone

    # ── group2_flexible ───────────────────────────────────────────────────────
    @pytest.mark.parametrize("v_rms, expected_zone", [
        (1.0,  "A"),
        (2.29, "A"),
        (2.31, "B"),
        (4.49, "B"),
        (4.51, "C"),
        (7.09, "C"),
        (7.11, "D"),
    ])
    def test_group2_flexible_boundaries(self, v_rms, expected_zone):
        assert classify_zone(v_rms, "group2_flexible") == expected_zone

    def test_exactly_at_a_boundary_is_zone_a(self):
        """class_I boundary is 0.71 → v=0.71 should be A (≤ condition)."""
        result = classify_zone(0.71, "class_I")
        assert result == "A", f"0.71 for class_I should be A (≤), got {result}"

    def test_just_above_boundary_is_next_zone(self):
        result = classify_zone(0.711, "class_I")
        assert result == "B"

    def test_zero_velocity_is_zone_A(self):
        assert classify_zone(0.0, "class_I") == "A"

    def test_invalid_class_falls_back_or_raises(self):
        """Unknown machine class either raises or falls back to class_I defaults."""
        try:
            result = classify_zone(0.5, "class_nonexistent")
            # If it doesn't raise, it should still return a valid zone
            assert result in ("A", "B", "C", "D")
        except (KeyError, ValueError):
            pass  # Also acceptable

    def test_custom_thresholds_as_tuple(self):
        """Custom thresholds tuple (a_max, b_max, c_max) overrides built-in."""
        custom_dict = {"my_machine": (1.0, 2.0, 5.0)}
        assert classify_zone(0.5,  "my_machine", thresholds=custom_dict) == "A"
        assert classify_zone(1.5,  "my_machine", thresholds=custom_dict) == "B"
        assert classify_zone(3.0,  "my_machine", thresholds=custom_dict) == "C"
        assert classify_zone(6.0,  "my_machine", thresholds=custom_dict) == "D"

    def test_zone_values_are_strings(self):
        for machine_class in ZONE_THRESHOLDS:
            for v in (0.1, 1.0, 3.0, 8.0):
                zone = classify_zone(v, machine_class)
                assert isinstance(zone, str)
                assert zone in ("A", "B", "C", "D")


class TestParseRemoteConfigThresholds:
    def test_round_trip_object_format(self):
        """Parse JSON in {"A": x, "B": y, "C": z} format → dict of threshold tuples."""
        thresholds_json = json.dumps({
            "class_I":         {"A": 0.71, "B": 1.8,  "C": 4.5},
            "group2_rigid":    {"A": 1.4,  "B": 2.8,  "C": 4.5},
            "group2_flexible": {"A": 2.3,  "B": 4.5,  "C": 7.1},
        })
        parsed = parse_remote_config_thresholds(thresholds_json)
        for key, (a, b, c) in ZONE_THRESHOLDS.items():
            assert parsed[key][0] == pytest.approx(a, rel=1e-6)
            assert parsed[key][1] == pytest.approx(b, rel=1e-6)
            assert parsed[key][2] == pytest.approx(c, rel=1e-6)

    def test_classify_with_parsed_thresholds(self):
        """classify_zone accepts thresholds returned by parse_remote_config_thresholds."""
        json_str = json.dumps({
            "class_I": {"A": 0.71, "B": 1.8, "C": 4.5}
        })
        parsed = parse_remote_config_thresholds(json_str)
        # Zone A: 0.5 < 0.71
        assert classify_zone(0.5,  "class_I", thresholds=parsed) == "A"
        # Zone C: 2.0 > 1.8, ≤ 4.5
        assert classify_zone(2.0,  "class_I", thresholds=parsed) == "C"
