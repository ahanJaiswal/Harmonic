"""
harmonic_dsp — Reference DSP pipeline for Harmonic.

This package is the **source of truth** for all platform ports (Kotlin, Swift, TypeScript).
Every public function is validated against golden vectors in reference/golden/.

Modules
-------
sync        Tap-sync, clock-offset estimation, linear drift correction.
frames      Gravity-based coordinate frame (V/A/H axes).
resample    Non-uniform timestamp → uniform time grid (cubic interpolation).
spectrum    Welch PSD, amplitude spectrum, Hann windowing.
orders      Order-axis extraction, peak search, parabolic interpolation.
velocity    Acceleration → velocity RMS, band-limited overall, per-order.
severity    ISO 10816/20816 zone classification (A/B/C/D).
phase       Cross-phone phase and magnitude-squared coherence (Welch).
features    Assemble the full feature JSON sent to the AI model.
gate        Physics gate — deterministic consistency checks on model output.
"""

from .sync import estimate_offset, correct_clock
from .frames import estimate_gravity, project_axes
from .resample import resample_uniform
from .spectrum import welch_amplitude, welch_psd
from .orders import extract_orders, find_running_speed
from .velocity import accel_to_velocity_rms, overall_velocity_rms
from .severity import classify_zone, ZONE_THRESHOLDS
from .phase import cross_phase_coherence
from .features import build_feature_vector
from .gate import physics_gate

__all__ = [
    "estimate_offset", "correct_clock",
    "estimate_gravity", "project_axes",
    "resample_uniform",
    "welch_amplitude", "welch_psd",
    "extract_orders", "find_running_speed",
    "accel_to_velocity_rms", "overall_velocity_rms",
    "classify_zone", "ZONE_THRESHOLDS",
    "cross_phase_coherence",
    "build_feature_vector",
    "physics_gate",
]
