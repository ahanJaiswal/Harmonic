"""
frames.py — Gravity-based coordinate frame estimation (§5.4).

The phone is mounted on a bearing housing and held approximately still during recording.
Gravity provides the vertical (down) direction without needing a magnetometer
(which is unreliable near motors due to strong magnetic fields).

Coordinate convention
---------------------
  V  = vertical (opposite gravity direction, i.e., "up")
  A  = axial (along shaft; user aligns phone long edge with shaft)
  H  = horizontal radial = V × A  (right-hand, perpendicular to both)

The app shows a placement diagram so all phones share the same orientation convention
(screen up, top edge toward the shaft drive end). After rotating to the V/A/H frame,
phone-to-phone axis comparisons are meaningful.

Notes
-----
- Do NOT use the magnetometer — the magnetic field near motors is noisy and biased.
- Gravity estimate uses the MEAN of raw acceleration (not user acceleration);
  the phone is static during measurement.
- For a perfectly flat, screen-up phone with top toward shaft: V ≈ device Z (up),
  A ≈ device -Y (top edge), H ≈ device X. The rotation still corrects small tilt.
"""

from __future__ import annotations

import numpy as np


def estimate_gravity(accel_raw: np.ndarray) -> np.ndarray:
    """
    Estimate the gravity vector from raw (gravity-included) acceleration.

    Parameters
    ----------
    accel_raw : (N, 3) float — raw acceleration in device frame, any unit (m/s² or g)
                               Must include gravity (not "user acceleration").

    Returns
    -------
    g_vec : (3,) float — mean gravity vector in device frame, same units as input.
                         Points downward (toward Earth's center).
                         Its magnitude approximates g (9.81 m/s² or 1 g).
    """
    if accel_raw.ndim != 2 or accel_raw.shape[1] != 3:
        raise ValueError(f"accel_raw must be (N, 3), got {accel_raw.shape}")
    return accel_raw.mean(axis=0)


def project_axes(
    accel_raw: np.ndarray,
    g_vec: np.ndarray,
    shaft_axis_device: np.ndarray | None = None,
) -> dict[str, np.ndarray]:
    """
    Project raw acceleration into the V/A/H vibration frame.

    Parameters
    ----------
    accel_raw         : (N, 3) float — raw acceleration (gravity included) in device frame
    g_vec             : (3,) float   — gravity vector from estimate_gravity()
    shaft_axis_device : (3,) float   — unit vector along the shaft in device frame.
                                        If None, assumed to be the device -Y axis
                                        (phone long edge pointing toward shaft drive end,
                                        top of phone toward DE).

    Returns
    -------
    dict with keys:
        "V" : (N,) float — vertical radial (opposite gravity)
        "A" : (N,) float — axial (along shaft)
        "H" : (N,) float — horizontal radial = V × A

    Notes
    -----
    The raw signal includes gravity (a DC offset in V). Remove the mean of each
    projected channel before computing spectra — the DSP pipeline does this downstream.
    """
    if accel_raw.ndim != 2 or accel_raw.shape[1] != 3:
        raise ValueError(f"accel_raw must be (N, 3), got {accel_raw.shape}")

    # Vertical = opposite gravity direction (points "up")
    g_norm = g_vec / (np.linalg.norm(g_vec) + 1e-12)
    v_hat = -g_norm  # up

    # Axial = along shaft direction
    if shaft_axis_device is None:
        # Default: phone long edge toward shaft → device -Y axis (top of phone)
        shaft_axis_device = np.array([0.0, -1.0, 0.0])
    a_raw = np.asarray(shaft_axis_device, dtype=float)
    # Orthogonalize against V (Gram-Schmidt) to handle slight tilt
    a_hat = a_raw - np.dot(a_raw, v_hat) * v_hat
    a_norm = np.linalg.norm(a_hat)
    if a_norm < 1e-6:
        raise ValueError(
            "shaft_axis_device is nearly parallel to gravity — cannot define axial direction."
        )
    a_hat = a_hat / a_norm

    # Horizontal radial: V × A (right-hand)
    h_hat = np.cross(v_hat, a_hat)
    h_hat = h_hat / (np.linalg.norm(h_hat) + 1e-12)

    return {
        "V": accel_raw @ v_hat,
        "A": accel_raw @ a_hat,
        "H": accel_raw @ h_hat,
    }


def remove_dc(signal: np.ndarray) -> np.ndarray:
    """Remove the DC component (mean) from a 1-D signal."""
    return signal - signal.mean()
