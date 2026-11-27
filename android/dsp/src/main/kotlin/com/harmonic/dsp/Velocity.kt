package com.harmonic.dsp

import kotlin.math.abs
import kotlin.math.sqrt

const val DEFAULT_F_LO_HZ = 2.0

fun accelToVelocity(
    freqs: DoubleArray,
    ampAccel: DoubleArray,
    fLoHz: Double = DEFAULT_F_LO_HZ,
): Pair<DoubleArray, DoubleArray> {
    val keep = ArrayList<Int>()
    for (i in freqs.indices) if (freqs[i] >= fLoHz) keep.add(i)
    val f = DoubleArray(keep.size)
    val v = DoubleArray(keep.size)
    for (j in keep.indices) {
        val i = keep[j]
        f[j] = freqs[i]
        val mps = ampAccel[i] / (2.0 * kotlin.math.PI * f[j] + 1e-12)
        v[j] = mps * 1000.0
    }
    return f to v
}

fun overallVelocityRms(
    freqs: DoubleArray,
    ampAccel: DoubleArray,
    fLoHz: Double = DEFAULT_F_LO_HZ,
): Double {
    val (_, ampVel) = accelToVelocity(freqs, ampAccel, fLoHz)
    var sum = 0.0
    for (v in ampVel) sum += v * v
    return sqrt(0.5 / (HANN_AMPL_CORR * HANN_AMPL_CORR) * sum)
}

fun velocityAtOrder(
    freqs: DoubleArray,
    ampAccel: DoubleArray,
    fOrderHz: Double,
    fLoHz: Double = DEFAULT_F_LO_HZ,
    searchHalfBins: Int = 2,
): Double {
    if (fOrderHz < fLoHz || freqs.isEmpty()) return 0.0
    var idx = 0
    var best = Double.POSITIVE_INFINITY
    for (i in freqs.indices) {
        val d = abs(freqs[i] - fOrderHz)
        if (d < best) {
            best = d
            idx = i
        }
    }
    val lo = maxOf(0, idx - searchHalfBins)
    val hi = minOf(ampAccel.size - 1, idx + searchHalfBins)
    val peak = SignalMath.argMax(ampAccel, lo, hi + 1)
    val fPeak = freqs[peak]
    if (fPeak < fLoHz) return 0.0
    return ampAccel[peak] / (2.0 * kotlin.math.PI * fPeak + 1e-12) * 1000.0
}
