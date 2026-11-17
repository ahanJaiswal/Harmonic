"""
conftest.py — Shared fixtures for harmonic_dsp tests.

Synthetic signal generators for golden-vector tests.
All signals are analytically known, so expected outputs can be computed exactly.
"""

from __future__ import annotations

import numpy as np
import pytest


# ── Signal generators ─────────────────────────────────────────────────────────

def make_sinusoid(
    freq_hz: float,
    amplitude: float,
    phase_rad: float,
    fs: float,
    duration_s: float,
    noise_std: float = 0.0,
    seed: int = 42,
) -> tuple[np.ndarray, np.ndarray]:
    """Return (signal, timestamps) for a pure sinusoid + optional white noise."""
    rng = np.random.default_rng(seed)
    n = int(fs * duration_s)
    t = np.arange(n) / fs
    sig = amplitude * np.cos(2 * np.pi * freq_hz * t + phase_rad)
    if noise_std > 0:
        sig += rng.normal(0, noise_std, size=n)
    return sig, t


def make_nonuniform_timestamps(
    fs_nominal: float,
    duration_s: float,
    jitter_std_s: float = 0.002,
    seed: int = 7,
) -> np.ndarray:
    """
    Simulate non-uniform timestamps: nominal grid + Gaussian jitter.
    Jitter_std_s = 2 ms is realistic for Android accelerometer delivery.
    """
    rng = np.random.default_rng(seed)
    n = int(fs_nominal * duration_s) + 1
    t_uniform = np.arange(n) / fs_nominal
    jitter = rng.normal(0, jitter_std_s, size=n)
    # Ensure monotonic: sort after adding jitter
    t_jittered = np.sort(t_uniform + jitter)
    return t_jittered


def make_tap_impulse(
    t: np.ndarray,
    t_impact: float,
    amplitude: float = 10.0,
    width_samples: int = 3,
) -> np.ndarray:
    """
    Inject a tap impulse into a time array.
    Models the sharp transient when phones are tapped together.
    """
    idx = int(np.argmin(np.abs(t - t_impact)))
    impulse = np.zeros(len(t))
    for k in range(-(width_samples // 2), width_samples // 2 + 1):
        i = idx + k
        if 0 <= i < len(impulse):
            # Triangle pulse
            impulse[i] += amplitude * (1.0 - abs(k) / (width_samples // 2 + 1))
    return impulse


# ── Fixtures ──────────────────────────────────────────────────────────────────

@pytest.fixture
def fs_android():
    return 200.0

@pytest.fixture
def fs_iphone():
    return 100.0

@pytest.fixture
def duration_s():
    return 40.0

@pytest.fixture
def f_run():
    """Running speed: 18.6 Hz (≈ 1116 RPM — a typical box fan)."""
    return 18.6

@pytest.fixture
def rpm_nameplate(f_run):
    return f_run * 60.0
