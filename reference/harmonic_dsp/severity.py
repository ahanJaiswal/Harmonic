"""
severity.py — ISO 10816/20816 severity zone classification (§5.7).

Zones (A/B/C/D) based on overall velocity RMS (mm/s):
    A — Newly commissioned / acceptable (≤ zone_B_lower)
    B — Acceptable for long-term operation
    C — Unsatisfactory; plan maintenance
    D — Damage likely; immediate action

Thresholds vary by machine class/group. The thresholds here are defaults
from ISO 10816; they should be tuned via Remote Config in production.

ISO 10816-1 Class I  (small machines < 15 kW):
    A < 0.71 | B < 1.8 | C < 4.5 | D ≥ 4.5  (mm/s RMS)

ISO 10816-3 Group 2, rigid support:
    A < 1.4 | B < 2.8 | C < 4.5 | D ≥ 4.5

ISO 10816-3 Group 2, flexible support:
    A < 2.3 | B < 4.5 | C < 7.1 | D ≥ 7.1

Note: ISO 20816 supersedes ISO 10816 for some machine categories;
thresholds are similar but check the specific standard for the machine type.
Our measurements are band-limited (phone fs/2 < 1000 Hz) — always label as
"estimated (band-limited)" since the standard band is 10–1000 Hz.
"""

from __future__ import annotations

import math
from typing import Literal

# Zone boundary type alias: (A_max, B_max, C_max) — values > C_max → zone D
ThresholdTuple = tuple[float, float, float]

# Default thresholds in mm/s RMS (Remote Config overwrites these at runtime)
ZONE_THRESHOLDS: dict[str, ThresholdTuple] = {
    "class_I":          (0.71, 1.8,  4.5),   # ISO 10816-1 Class I small machines
    "group2_rigid":     (1.4,  2.8,  4.5),   # ISO 10816-3 Group 2, rigid support
    "group2_flexible":  (2.3,  4.5,  7.1),   # ISO 10816-3 Group 2, flexible support
}

Zone = Literal["A", "B", "C", "D"]


def classify_zone(
    velocity_rms_mm_s: float,
    machine_class: str = "class_I",
    thresholds: dict[str, ThresholdTuple] | None = None,
) -> Zone:
    """
    Classify overall velocity RMS into an ISO severity zone.

    Parameters
    ----------
    velocity_rms_mm_s : float — overall velocity RMS in mm/s
    machine_class     : str   — key into thresholds dict (default "class_I")
    thresholds        : dict  — override default ZONE_THRESHOLDS (e.g., from Remote Config)

    Returns
    -------
    zone : "A" | "B" | "C" | "D"
    """
    v_check = float(velocity_rms_mm_s)
    if not math.isfinite(v_check) or v_check < 0:
        raise ValueError(f"velocity_rms_mm_s must be finite and non-negative, got {velocity_rms_mm_s!r}")
    t = (thresholds or ZONE_THRESHOLDS).get(machine_class, ZONE_THRESHOLDS["class_I"])
    a_max, b_max, c_max = t

    v = velocity_rms_mm_s
    if v <= a_max:
        return "A"
    elif v <= b_max:
        return "B"
    elif v <= c_max:
        return "C"
    else:
        return "D"


def zone_description(zone: Zone) -> str:
    """Human-readable description of the severity zone."""
    return {
        "A": "Good — newly commissioned or well-maintained",
        "B": "Acceptable for long-term continuous operation",
        "C": "Unsatisfactory — plan maintenance action",
        "D": "Damage likely — take immediate action",
    }[zone]


def parse_remote_config_thresholds(json_str: str) -> dict[str, ThresholdTuple]:
    """
    Parse the Remote Config 'severity_thresholds' JSON string into a thresholds dict.

    Expected JSON format:
        {
          "class_I":         {"A": 0.71, "B": 1.8,  "C": 4.5},
          "group2_rigid":    {"A": 1.4,  "B": 2.8,  "C": 4.5},
          "group2_flexible": {"A": 2.3,  "B": 4.5,  "C": 7.1}
        }
    """
    import json
    raw = json.loads(json_str)
    result: dict[str, ThresholdTuple] = {}
    for key, vals in raw.items():
        result[key] = (float(vals["A"]), float(vals["B"]), float(vals["C"]))
    return result
