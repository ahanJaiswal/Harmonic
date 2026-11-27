package com.harmonic.dsp

import kotlin.math.atan2
import kotlin.math.sqrt

data class PhaseResult(
    val phaseDeg: Double?,
    val coherence: Double,
    val fActualHz: Double,
    val trusted: Boolean,
)

fun crossPhaseCoherence(
    signalA: DoubleArray,
    signalB: DoubleArray,
    fs: Double,
    fTargetHz: Double,
    nperseg: Int? = null,
    noverlap: Int? = null,
    coherenceMin: Double = 0.8,
): PhaseResult {
    require(signalA.size == signalB.size) {
        "signal lengths differ: ${signalA.size} vs ${signalB.size}"
    }
    val nps = (nperseg ?: SignalMath.autoNperseg(fs, signalA.size)).coerceAtMost(signalA.size)
    val nov = noverlap ?: (nps / 2)
    val (freqs, xy) = SignalMath.welchCsd(signalA, signalB, fs, nps, nov)
    val (_, xx) = SignalMath.welchPsd(signalA, fs, nps, nov)
    val (_, yy) = SignalMath.welchPsd(signalB, fs, nps, nov)
    var idx = 0
    var best = Double.POSITIVE_INFINITY
    for (i in freqs.indices) {
        val d = kotlin.math.abs(freqs[i] - fTargetHz)
        if (d < best) {
            best = d
            idx = i
        }
    }
    val fActual = freqs[idx]
    val denom = xx[idx] * yy[idx]
    if (denom < 1e-30) {
        return PhaseResult(null, 0.0, fActual, false)
    }
    val (re, im) = xy
    val mag2 = re[idx] * re[idx] + im[idx] * im[idx]
    val gamma = SignalMath.clip(mag2 / denom, 0.0, 1.0)
    val phase = Math.toDegrees(atan2(im[idx], re[idx]))
    val trusted = gamma >= coherenceMin
    return PhaseResult(if (trusted) phase else null, gamma, fActual, trusted)
}

fun phaseStability(phasesDeg: List<Double?>): Double? {
    val valid = phasesDeg.filterNotNull()
    if (valid.size < 2) return null
    var re = 0.0
    var im = 0.0
    for (p in valid) {
        val rad = Math.toRadians(p)
        re += kotlin.math.cos(rad)
        im += kotlin.math.sin(rad)
    }
    re /= valid.size
    im /= valid.size
    val r = SignalMath.clip(sqrt(re * re + im * im), 1e-6, 1.0)
    return Math.toDegrees(sqrt(-2.0 * kotlin.math.ln(r)))
}

fun hvPhase(
    signalH: DoubleArray,
    signalV: DoubleArray,
    fs: Double,
    fTargetHz: Double,
    nperseg: Int? = null,
    coherenceMin: Double = 0.8,
): PhaseResult = crossPhaseCoherence(signalH, signalV, fs, fTargetHz, nperseg, null, coherenceMin)
