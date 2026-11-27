"""Regressions for issues found in the Nov 27 stress test (commit 648589c)."""

from __future__ import annotations

import math

import numpy as np
import pytest

from harmonic_dsp.gate import physics_gate
from harmonic_dsp.phase import coherence_nperseg, cross_phase_coherence
from harmonic_dsp.severity import classify_zone

FS = 100.0
N = int(15 * FS)  # a 15 s phone recording


def test_default_coherence_uses_several_segments():
    # One segment makes coherence 1.0 by construction.
    nps = coherence_nperseg(N)
    segments = (N - nps // 2) // (nps // 2)
    assert segments >= 6


def test_independent_noise_is_not_trusted_by_default():
    rng = np.random.default_rng(7)
    a = rng.standard_normal(N)
    b = rng.standard_normal(N)
    r = cross_phase_coherence(a, b, FS, 25.0)
    assert r["coherence"] < 0.8
    assert r["phase_deg"] is None and not r["trusted"]


def test_shared_tone_keeps_its_phase_by_default():
    rng = np.random.default_rng(3)
    t = np.arange(N) / FS
    a = np.sin(2 * np.pi * 25.0 * t) + 0.05 * rng.standard_normal(N)
    b = np.sin(2 * np.pi * 25.0 * t + np.pi) + 0.05 * rng.standard_normal(N)
    r = cross_phase_coherence(a, b, FS, 25.0)
    assert r["trusted"]
    assert abs(abs(r["phase_deg"]) - 180.0) < 5.0


@pytest.mark.parametrize("bad", [math.nan, math.inf, -1.0])
def test_non_finite_velocity_is_refused_not_zone_d(bad):
    with pytest.raises(ValueError):
        classify_zone(bad)


def _features():
    return {"orders": {"1x": 1.0, "2x": 0.1, "harmonics_count_above_floor": 0}, "severity_zone": "B"}


def test_gate_rejects_unknown_fault_names():
    hyp = {"hypotheses": [{"fault": "gremlins", "p": 0.9}], "severity_zone": "B"}
    r = physics_gate(hyp, _features())
    assert r.corrected_hypothesis["hypotheses"] == []
    assert r.escalate_to_cloud


def test_gate_clamps_probability_and_survives_bad_types():
    hyp = {
        "hypotheses": [
            {"fault": "static_imbalance", "p": 7.5},
            "not a dict",
            {"fault": 42, "p": "high"},
        ],
        "severity_zone": "B",
    }
    r = physics_gate(hyp, _features())
    ps = [h["p"] for h in r.corrected_hypothesis["hypotheses"]]
    assert all(0.0 <= p <= 1.0 for p in ps)
    assert ps[0] == 1.0
