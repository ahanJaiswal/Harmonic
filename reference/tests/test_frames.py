"""test_frames.py — machine-frame projection, including an upright phone."""

from __future__ import annotations

import numpy as np
import pytest

from harmonic_dsp.frames import estimate_gravity, project_axes


def _upright_with_vibration(n: int = 2000) -> np.ndarray:
    # Phone standing on its bottom edge: gravity on device +Y, a 30 Hz tone on X.
    t = np.arange(n) / 500.0
    accel = np.zeros((n, 3))
    accel[:, 1] = 9.81
    accel[:, 0] = 0.5 * np.sin(2 * np.pi * 30.0 * t)
    return accel


def test_upright_phone_uses_screen_normal_instead_of_failing():
    accel = _upright_with_vibration()
    axes = project_axes(accel, estimate_gravity(accel))
    # Vertical carries gravity, the X tone lands in H, axial (-Z) stays quiet.
    assert abs(np.mean(axes["V"])) == pytest.approx(9.81, rel=1e-6)
    assert np.std(axes["H"]) == pytest.approx(0.5 / np.sqrt(2), rel=1e-2)
    assert np.std(axes["A"]) < 1e-9


def test_explicit_vertical_shaft_still_raises():
    accel = _upright_with_vibration()
    with pytest.raises(ValueError):
        project_axes(accel, estimate_gravity(accel), shaft_axis_device=np.array([0.0, 1.0, 0.0]))
