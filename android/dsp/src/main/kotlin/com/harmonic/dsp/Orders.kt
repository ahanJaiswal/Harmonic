package com.harmonic.dsp

import kotlin.math.abs

val STANDARD_ORDERS = doubleArrayOf(0.5, 1.0, 1.5, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0)

fun formatOrderKey(order: Double): String {
    val nearest = kotlin.math.round(order).toInt()
    val body = if (abs(order - nearest) < 1e-9) {
        nearest.toString()
    } else {
        String.format(java.util.Locale.US, "%.4f", order).trimEnd('0').trimEnd('.')
    }
    return body + "x"
}

data class RunningSpeed(
    val fRunHz: Double,
    val rpmRun: Double,
    val amp1x: Double,
    val confidence: Double,
    val fRunPrior: Double,
)

private fun parabolicInterp(freqs: DoubleArray, amp: DoubleArray, peakIdx: Int): Pair<Double, Double> {
    val n = amp.size
    if (peakIdx <= 0 || peakIdx >= n - 1) return freqs[peakIdx] to amp[peakIdx]
    val alpha = amp[peakIdx - 1]
    val beta = amp[peakIdx]
    val gamma = amp[peakIdx + 1]
    val denom = alpha - 2 * beta + gamma
    if (abs(denom) < 1e-15) return freqs[peakIdx] to beta
    val p = 0.5 * (alpha - gamma) / denom
    val df = freqs[1] - freqs[0]
    val fRefined = freqs[peakIdx] + p * df
    val aRefined = beta - 0.25 * (alpha - gamma) * p
    return fRefined to kotlin.math.max(aRefined, 0.0)
}

fun findRunningSpeed(
    freqs: DoubleArray,
    amp: DoubleArray,
    rpmNameplate: Double,
    searchFraction: Double = 0.20,
): RunningSpeed {
    val fPrior = rpmNameplate / 60.0
    val fLo = fPrior * (1.0 - searchFraction)
    val fHi = fPrior * (1.0 + searchFraction)
    val indices = ArrayList<Int>()
    for (i in freqs.indices) if (freqs[i] >= fLo && freqs[i] <= fHi) indices.add(i)
    if (indices.size < 3) {
        return RunningSpeed(fPrior, rpmNameplate, 0.0, 0.0, fPrior)
    }
    var bestLocal = 0
    var bestVal = Double.NEGATIVE_INFINITY
    var sum = 0.0
    for (j in indices.indices) {
        val v = amp[indices[j]]
        sum += v
        if (v > bestVal) {
            bestVal = v
            bestLocal = j
        }
    }
    val globalPeak = indices[bestLocal]
    val (fRun, amp1x) = parabolicInterp(freqs, amp, globalPeak)
    val noiseMean = sum / indices.size
    val snr = amp1x / (noiseMean + 1e-12)
    val confidence = SignalMath.clip((snr - 1.0) / 9.0, 0.0, 1.0)
    return RunningSpeed(fRun, fRun * 60.0, amp1x, confidence, fPrior)
}

fun extractOrders(
    freqs: DoubleArray,
    amp: DoubleArray,
    fRunHz: Double,
    orders: DoubleArray = STANDARD_ORDERS,
    searchHalfBins: Int = 3,
): LinkedHashMap<String, Double> {
    val df = if (freqs.size > 1) freqs[1] - freqs[0] else 1.0
    val result = LinkedHashMap<String, Double>()
    for (order in orders) {
        val fNominal = order * fRunHz
        val key = formatOrderKey(order)
        if (fNominal <= 0 || fNominal > freqs.last()) {
            result[key] = 0.0
            continue
        }
        var idxNominal = 0
        var best = Double.POSITIVE_INFINITY
        for (i in freqs.indices) {
            val d = abs(freqs[i] - fNominal)
            if (d < best) {
                best = d
                idxNominal = i
            }
        }
        val lo = maxOf(0, idxNominal - searchHalfBins)
        val hi = minOf(amp.size - 1, idxNominal + searchHalfBins)
        val local = SignalMath.argMax(amp, lo, hi + 1)
        val (_, aRefined) = parabolicInterp(freqs, amp, local)
        result[key] = aRefined
    }
    val noiseFloor = SignalMath.median(amp) * 2.0
    var harmonicsAbove = 0
    for (k in 1..10) {
        if ((result["${k}x"] ?: 0.0) > noiseFloor) harmonicsAbove++
    }
    result["harmonics_count_above_floor"] = harmonicsAbove.toDouble()
    return result
}
