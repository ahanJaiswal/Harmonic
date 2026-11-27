"""Generate golden vectors from the Python DSP spec.

Writes the same document to:
  reference/golden/vectors.json
  android/dsp/src/test/resources/golden/vectors.json
  ios/Packages/HarmonicDSP/Tests/HarmonicDSPTests/Golden/vectors.json

Run from the repo root:
  reference\\.venv\\Scripts\\python.exe reference/scripts/generate_golden.py
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np
from scipy.signal import butter

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "reference"))

from harmonic_dsp.features import build_feature_vector
from harmonic_dsp.frames import estimate_gravity, project_axes, remove_dc
from harmonic_dsp.gate import physics_gate
from harmonic_dsp.orders import extract_orders, find_running_speed
from harmonic_dsp.phase import cross_phase_coherence
from harmonic_dsp.resample import resample_uniform
from harmonic_dsp.severity import classify_zone
from harmonic_dsp.spectrum import welch_amplitude
from harmonic_dsp.sync import correct_clock, estimate_offset
from harmonic_dsp.velocity import overall_velocity_rms, velocity_at_order


def _tolist(a) -> list:
    return np.asarray(a, dtype=float).reshape(-1).tolist()


def main() -> None:
    cases: list[dict] = []

    ts = np.linspace(0.0, 60.0, 12001)
    corrected = correct_clock(ts, offset_s=0.010, drift_ppm=100.0, t_ref_start=0.0)
    cases.append({
        "kind": "clock",
        "ts": _tolist(ts[::20]),  # 601 samples — still checks the linear model
        "offset_s": 0.010,
        "drift_ppm": 100.0,
        "t_ref": 0.0,
        "expected": _tolist(corrected[::20]),
    })

    b, a = butter(4, 5.0 / (200.0 / 2.0), btype="high")
    cases.append({
        "kind": "butter",
        "fs": 200.0,
        "cutoff": 5.0,
        "order": 4,
        "b": _tolist(b),
        "a": _tolist(a),
    })

    rng = np.random.default_rng(1)
    n = 64
    raw = np.zeros((n, 3))
    raw[:, 2] = 9.81
    raw[:, 0] = 0.4 * np.sin(np.linspace(0, 8, n))
    raw += rng.normal(0, 0.01, raw.shape)
    g = estimate_gravity(raw)
    proj = project_axes(raw, g)
    cases.append({
        "kind": "frames",
        "accel": raw.tolist(),
        "g": _tolist(g),
        "V": _tolist(proj["V"]),
        "A": _tolist(proj["A"]),
        "H": _tolist(proj["H"]),
        "dc_removed": _tolist(remove_dc(proj["H"])),
    })

    t_j = np.sort(np.linspace(0, 1.0, 40) + rng.normal(0, 0.001, 40))
    sig = np.sin(2 * np.pi * 8 * t_j)
    uni, ts_u, fs_out = resample_uniform(sig, t_j, fs_target=50.0)
    cases.append({
        "kind": "resample",
        "signal": _tolist(sig),
        "timestamps": _tolist(t_j),
        "fs_target": 50.0,
        "signal_out": _tolist(uni),
        "ts_out": _tolist(ts_u),
        "fs_out": float(fs_out),
    })

    fs = 200.0
    nperseg = 256
    n_spec = 2048
    t = np.arange(n_spec) / fs
    f0 = 16 * fs / nperseg  # exactly on a bin
    tone = 2.5 * np.cos(2 * np.pi * f0 * t)
    freqs, amp = welch_amplitude(tone, fs, nperseg=nperseg)
    cases.append({
        "kind": "welch",
        "signal": _tolist(tone),
        "fs": fs,
        "nperseg": nperseg,
        "freqs": _tolist(freqs),
        "amp": _tolist(amp),
    })

    speed = find_running_speed(freqs, amp, rpm_nameplate=1200.0)
    orders = extract_orders(freqs, amp, speed["f_run_hz"])
    cases.append({
        "kind": "orders",
        "freqs": _tolist(freqs),
        "amp": _tolist(amp),
        "rpm_nameplate": 1200.0,
        "f_run_hz": speed["f_run_hz"],
        "rpm_run": speed["rpm_run"],
        "amp_1x": speed["amp_1x"],
        "confidence": speed["confidence"],
        "orders": {k: float(v) for k, v in orders.items()},
    })

    cases.append({
        "kind": "velocity",
        "freqs": _tolist(freqs),
        "amp": _tolist(amp),
        "f_order": float(f0),
        "overall": float(overall_velocity_rms(freqs, amp)),
        "at_order": float(velocity_at_order(freqs, amp, f0)),
    })

    phase = 0.7
    sig_b = 2.5 * np.cos(2 * np.pi * f0 * t + phase)
    ph = cross_phase_coherence(tone, sig_b, fs, f0, nperseg=nperseg)
    cases.append({
        "kind": "phase",
        "a": _tolist(tone),
        "b": _tolist(sig_b),
        "fs": fs,
        "f_target": float(f0),
        "nperseg": nperseg,
        "phase_deg": ph["phase_deg"],
        "coherence": ph["coherence"],
        "trusted": ph["trusted"],
        "f_actual": ph["f_actual_hz"],
    })

    noise_a = rng.standard_normal(n_spec)
    noise_b = rng.standard_normal(n_spec)
    phn = cross_phase_coherence(noise_a, noise_b, fs, f0, nperseg=nperseg, coherence_min=0.8)
    cases.append({
        "kind": "phase",
        "a": _tolist(noise_a),
        "b": _tolist(noise_b),
        "fs": fs,
        "f_target": float(f0),
        "nperseg": nperseg,
        "phase_deg": phn["phase_deg"],
        "coherence": phn["coherence"],
        "trusted": phn["trusted"],
        "f_actual": phn["f_actual_hz"],
    })

    zones = []
    for v, cls in [(0.71, "class_I"), (0.72, "class_I"), (4.5, "class_I"), (4.51, "class_I"),
                   (1.4, "group2_rigid"), (2.3, "group2_flexible"), (7.2, "group2_flexible"),
                   (0.5, "not_a_class")]:
        zones.append({"v": v, "class": cls, "zone": classify_zone(v, cls)})
    cases.append({"kind": "severity", "rows": zones})

    features = build_feature_vector(
        machine_type="fan",
        rpm_nameplate=1116.0,
        blades=5,
        mounting="rigid",
        iso_class="class_I",
        fs_hz=[200.0, 100.0],
        duration_s=12.0,
        sync_confidence=0.8421,
        band_limit_hz=50.0,
        running_speed_hz=18.5546875,
        velocity_mm_s={"A_H": 1.23456, "A_V": 0.987, "A_A": None, "B_H": 1.1},
        severity_zone="B",
        orders={"1x": 2.51234, "2x": 0.40119, "0.5x": 0.05, "harmonics_count_above_floor": 2},
        axial_to_radial_ratio=0.22222,
        phase_deg={"AB_H_1x": 12.345, "AB_V_1x": None},
        coherence={"AB_H_1x": 0.91234},
        crest_factor=1.41421,
        kurtosis=3.14159,
        phase_stability_deg=None,
        baseline_delta={"1x": 12.345, "2x": None},
    )
    cases.append({"kind": "features", "expected": features})

    hyp_ok = {
        "hypotheses": [{"fault": "static_imbalance", "p": 0.8}],
        "severity_zone": "B",
        "evidence": ["1x dominant"],
        "next_test": "none",
    }
    feat_ok = {
        "orders": {"1x": 2.5, "2x": 0.3, "0.5x": 0.01, "harmonics_count_above_floor": 1},
        "phase_deg": {"AB_H_1x": 8.0},
        "coherence": {"AB_H_1x": 0.95},
        "overall_velocity_mm_s": {"A_H": 1.2, "A_V": 1.1, "A_A": 0.2},
        "severity_zone": "B",
        "axial_to_radial_ratio": 0.15,
    }
    gated = physics_gate(hyp_ok, feat_ok)
    cases.append({
        "kind": "gate",
        "hypothesis": hyp_ok,
        "features": feat_ok,
        "accepted": gated.accepted,
        "escalate": gated.escalate_to_cloud,
        "actions": [a["action"] for a in gated.actions],
        "severity_zone": gated.corrected_hypothesis["severity_zone"],
        "next_test": gated.corrected_hypothesis["next_test"],
        "p": [h["p"] for h in gated.corrected_hypothesis["hypotheses"]],
    })

    hyp_bad = {
        "hypotheses": [{"fault": "parallel_misalignment", "p": 0.9}],
        "severity_zone": "A",
        "evidence": [],
        "next_test": "none",
    }
    gated_bad = physics_gate(hyp_bad, feat_ok)
    cases.append({
        "kind": "gate",
        "hypothesis": hyp_bad,
        "features": feat_ok,
        "accepted": gated_bad.accepted,
        "escalate": gated_bad.escalate_to_cloud,
        "actions": [a["action"] for a in gated_bad.actions],
        "severity_zone": gated_bad.corrected_hypothesis["severity_zone"],
        "next_test": gated_bad.corrected_hypothesis["next_test"],
        "p": [h["p"] for h in gated_bad.corrected_hypothesis["hypotheses"]],
    })

    # Short two-phone tap. Same construction as the Python tests, scaled down.
    fs_s = 200.0
    dur = 6.0
    n_s = int(fs_s * dur)
    ts_a = np.arange(n_s) / fs_s
    base_a = rng.normal(0, 0.05, (n_s, 3))
    base_b = rng.normal(0, 0.05, (n_s, 3))
    offset_true = 0.012
    for t_impact, amp_imp in ((1.0, 15.0), (5.0, 15.0)):
        idx = int(np.argmin(np.abs(ts_a - t_impact)))
        for k, scale in ((-1, 0.5), (0, 1.0), (1, 0.5)):
            i = idx + k
            if 0 <= i < n_s:
                base_a[i, 0] += amp_imp * scale
                base_b[i, 0] += amp_imp * scale
    ts_b = ts_a - offset_true
    w1 = (0.5, 1.5)
    w2 = (4.5, 5.5)
    sync = estimate_offset(base_a, ts_a, base_b, ts_b, w1, w2)
    cases.append({
        "kind": "sync",
        "accel_a": base_a.tolist(),
        "ts_a": _tolist(ts_a),
        "accel_b": base_b.tolist(),
        "ts_b": _tolist(ts_b),
        "w1": list(w1),
        "w2": list(w2),
        "offset_s": sync["offset_s"],
        "drift_ppm": sync["drift_ppm"],
        "snr_tap1": sync["snr_tap1"],
    })

    doc = {"version": 1, "cases": cases}
    targets = [
        ROOT / "reference" / "golden" / "vectors.json",
        ROOT / "android" / "dsp" / "src" / "test" / "resources" / "golden" / "vectors.json",
        ROOT / "ios" / "Packages" / "HarmonicDSP" / "Tests" / "HarmonicDSPTests" / "Golden" / "vectors.json",
    ]
    payload = json.dumps(doc, separators=(",", ":"))
    for path in targets:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(payload, encoding="utf-8")
        print(f"wrote {path} ({path.stat().st_size} bytes, {len(cases)} cases)")


if __name__ == "__main__":
    main()
