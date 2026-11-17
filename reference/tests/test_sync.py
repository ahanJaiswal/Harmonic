"""
test_sync.py — Golden-vector tests for tap synchronization.

Synthetic test cases:
1. Zero offset, zero drift → recovered offset ≈ 0 ms.
2. Known offset 37.3 ms, zero drift → recovered within tolerance.
3. Known offset 10 ms, known drift 80 ppm → both recovered within tolerance.
4. correct_clock maps timestamps correctly for known parameters.
"""

from __future__ import annotations

import numpy as np
import pytest

from harmonic_dsp.sync import estimate_offset, correct_clock
from tests.conftest import make_tap_impulse, make_nonuniform_timestamps


FS = 200.0
DURATION = 65.0  # long enough for two taps 60 s apart


def _make_two_phone_data(
    offset_s: float,
    drift_ppm: float,
    fs: float = FS,
    duration: float = DURATION,
    tap1_time: float = 2.0,
    tap2_time: float = 62.0,
    seed: int = 42,
) -> tuple:
    """
    Build synthetic 3-axis accelerometer data for phones A and B.

    Phone B has a clock that starts offset_s seconds behind A and drifts
    at drift_ppm parts per million relative to A.

    Returns: accel_a, ts_a, accel_b, ts_b, tap1_window, tap2_window
    """
    rng = np.random.default_rng(seed)
    n = int(fs * duration)
    ts_a = np.arange(n) / fs  # Reference clock

    # Build 3-axis background vibration (phone A and B)
    base_a = rng.normal(0, 0.05, (n, 3))
    base_b = rng.normal(0, 0.05, (n, 3))

    # Add tap impulses to both phones at the same physical time
    for t_impact in [tap1_time, tap2_time]:
        imp = make_tap_impulse(ts_a, t_impact, amplitude=15.0)
        base_a[:, 0] += imp
        base_b[:, 0] += imp  # same impulse in device frame

    # Phone B timestamps: start offset_s behind, drift at drift_ppm
    epsilon = drift_ppm * 1e-6
    ts_b_raw = ts_a - offset_s  # start shifted
    ts_b = ts_b_raw / (1.0 + epsilon)  # drift: B ticks slower/faster

    tap1_window = (tap1_time - 0.5, tap1_time + 0.5)
    tap2_window = (tap2_time - 0.5, tap2_time + 0.5)
    return base_a, ts_a, base_b, ts_b, tap1_window, tap2_window


class TestEstimateOffset:
    def test_zero_offset_zero_drift(self):
        accel_a, ts_a, accel_b, ts_b, w1, w2 = _make_two_phone_data(
            offset_s=0.0, drift_ppm=0.0
        )
        result = estimate_offset(accel_a, ts_a, accel_b, ts_b, w1, w2)
        assert abs(result["offset_s"]) < 0.003, (
            f"Expected offset ≈ 0, got {result['offset_s']*1000:.2f} ms"
        )
        assert abs(result["drift_ppm"]) < 5.0, (
            f"Expected drift ≈ 0 ppm, got {result['drift_ppm']:.1f} ppm"
        )
        assert result["snr_tap1"] > 5.0, "SNR too low — tap not detected"

    def test_known_offset_37ms(self):
        offset_true = 0.0373  # 37.3 ms
        accel_a, ts_a, accel_b, ts_b, w1, w2 = _make_two_phone_data(
            offset_s=offset_true, drift_ppm=0.0
        )
        result = estimate_offset(accel_a, ts_a, accel_b, ts_b, w1, w2)
        err_ms = abs(result["offset_s"] - offset_true) * 1000
        assert err_ms < 2.0, (
            f"Offset error {err_ms:.2f} ms exceeds 2 ms tolerance "
            f"(true={offset_true*1000:.1f} ms, got={result['offset_s']*1000:.2f} ms)"
        )

    def test_known_offset_and_drift(self):
        offset_true = 0.010  # 10 ms
        drift_true  = 80.0   # 80 ppm
        accel_a, ts_a, accel_b, ts_b, w1, w2 = _make_two_phone_data(
            offset_s=offset_true, drift_ppm=drift_true
        )
        result = estimate_offset(accel_a, ts_a, accel_b, ts_b, w1, w2)
        err_ms  = abs(result["offset_s"] - offset_true) * 1000
        err_ppm = abs(result["drift_ppm"] - drift_true)
        # Drift measured over 60 s at 200 Hz: each tap ≈ 5 ms resolution → ~1.6 ms drift
        assert err_ms  < 3.0, f"Offset error {err_ms:.2f} ms > 3 ms"
        assert err_ppm < 20.0, f"Drift error {err_ppm:.1f} ppm > 20 ppm"

    def test_snr_quality_metrics_reported(self):
        accel_a, ts_a, accel_b, ts_b, w1, w2 = _make_two_phone_data(
            offset_s=0.005, drift_ppm=0.0
        )
        result = estimate_offset(accel_a, ts_a, accel_b, ts_b, w1, w2)
        assert "snr_tap1" in result
        assert "snr_tap2" in result
        assert result["snr_tap1"] > 0
        assert result["snr_tap2"] > 0


class TestCorrectClock:
    def test_zero_correction(self):
        ts = np.linspace(0, 10, 2001)
        corrected = correct_clock(ts, offset_s=0.0, drift_ppm=0.0, t_ref_start=0.0)
        np.testing.assert_allclose(corrected, ts, atol=1e-12)

    def test_offset_only(self):
        ts = np.linspace(2.0, 12.0, 2001)
        offset = 0.037
        corrected = correct_clock(ts, offset_s=offset, drift_ppm=0.0, t_ref_start=2.0)
        np.testing.assert_allclose(corrected, ts - offset, atol=1e-12)

    def test_drift_correction(self):
        ts = np.linspace(0, 60, 12001)
        drift_ppm = 100.0
        offset    = 0.010
        corrected = correct_clock(ts, offset_s=offset, drift_ppm=drift_ppm, t_ref_start=0.0)
        # At t=60 s, drift correction = 60 × 100e-6 = 6 ms
        expected_correction_at_end = offset + 60.0 * drift_ppm * 1e-6
        actual_correction_at_end   = ts[-1] - corrected[-1]
        assert abs(actual_correction_at_end - expected_correction_at_end) < 1e-9
