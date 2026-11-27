package com.harmonic.dsp

import kotlin.math.abs
import kotlin.math.min

data class ClockOffset(
    val offsetS: Double,
    val driftPpm: Double,
    val snrTap1: Double,
    val snrTap2: Double,
    val tTap1Ref: Double,
    val tTap2Ref: Double,
)

fun correctClock(
    tsB: DoubleArray,
    offsetS: Double,
    driftPpm: Double,
    tRefStart: Double,
): DoubleArray {
    val epsilon = driftPpm * 1e-6
    return DoubleArray(tsB.size) { i ->
        val t = tsB[i]
        t - (offsetS + epsilon * (t - tRefStart))
    }
}

fun estimateOffset(
    accelRef: Array<DoubleArray>,
    tsRef: DoubleArray,
    accelB: Array<DoubleArray>,
    tsB: DoubleArray,
    tap1Window: Pair<Double, Double>,
    tap2Window: Pair<Double, Double>,
    fsRef: Double? = null,
    fsB: Double? = null,
    hpCutoffHz: Double = 5.0,
): ClockOffset {
    val fsA = fsRef ?: measureFs(tsRef)
    val fsOther = fsB ?: measureFs(tsB)
    val magRef = highpassMagnitude(accelRef, fsA, hpCutoffHz)
    val magB = highpassMagnitude(accelB, fsOther, hpCutoffHz)
    val (t1Ref, snr1Ref) = findImpulse(magRef, tsRef, tap1Window.first, tap1Window.second)
    val (t1B, snr1B) = findImpulse(magB, tsB, tap1Window.first, tap1Window.second)
    val (t2Ref, snr2Ref) = findImpulse(magRef, tsRef, tap2Window.first, tap2Window.second)
    val (t2B, snr2B) = findImpulse(magB, tsB, tap2Window.first, tap2Window.second)
    val delta1 = t1Ref - t1B
    val delta2 = t2Ref - t2B
    val span = t2Ref - t1Ref
    val drift = if (abs(span) > 1.0) (delta2 - delta1) / span else 0.0
    return ClockOffset(
        offsetS = delta1,
        driftPpm = drift * 1e6,
        snrTap1 = min(snr1Ref, snr1B),
        snrTap2 = min(snr2Ref, snr2B),
        tTap1Ref = t1Ref,
        tTap2Ref = t2Ref,
    )
}

private fun highpassMagnitude(accel: Array<DoubleArray>, fs: Double, cutoffHz: Double): DoubleArray {
    val (b, a) = SignalMath.butterHighpass(4, cutoffHz, fs)
    val n = accel.size
    val axes = Array(3) { axis ->
        val col = DoubleArray(n) { accel[it][axis] }
        SignalMath.filtfilt(b, a, col)
    }
    return DoubleArray(n) { i ->
        val x = axes[0][i]
        val y = axes[1][i]
        val z = axes[2][i]
        kotlin.math.sqrt(x * x + y * y + z * z)
    }
}

private fun parabolicPeak(signal: DoubleArray, peakIdx: Int): Double {
    if (peakIdx <= 0 || peakIdx >= signal.size - 1) return peakIdx.toDouble()
    val alpha = signal[peakIdx - 1]
    val beta = signal[peakIdx]
    val gamma = signal[peakIdx + 1]
    val denom = alpha - 2 * beta + gamma
    if (abs(denom) < 1e-12) return peakIdx.toDouble()
    return peakIdx + 0.5 * (alpha - gamma) / denom
}

private fun findImpulse(
    mag: DoubleArray,
    timestamps: DoubleArray,
    searchStart: Double,
    searchEnd: Double,
): Pair<Double, Double> {
    val idx = ArrayList<Int>()
    for (i in timestamps.indices) {
        if (timestamps[i] >= searchStart && timestamps[i] <= searchEnd) idx.add(i)
    }
    require(idx.size >= 3) { "Search window has fewer than 3 samples" }
    var coarseLocal = 0
    var best = Double.NEGATIVE_INFINITY
    var sumSq = 0.0
    for (j in idx.indices) {
        val v = mag[idx[j]]
        sumSq += v * v
        if (v > best) {
            best = v
            coarseLocal = j
        }
    }
    val globalCoarse = idx[coarseLocal]
    val sub = parabolicPeak(mag, globalCoarse)
    val lo = kotlin.math.floor(sub).toInt().coerceIn(0, timestamps.lastIndex)
    val hi = minOf(lo + 1, timestamps.lastIndex)
    val frac = sub - lo
    val tPeak = timestamps[lo] * (1 - frac) + timestamps[hi] * frac
    val rms = kotlin.math.sqrt(sumSq / idx.size)
    val snr = mag[globalCoarse] / (rms + 1e-12)
    return tPeak to snr
}
