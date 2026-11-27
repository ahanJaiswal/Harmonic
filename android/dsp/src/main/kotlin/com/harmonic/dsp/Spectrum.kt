package com.harmonic.dsp

import kotlin.math.abs
import kotlin.math.sqrt

const val HANN_COHERENT_GAIN = 0.5
const val HANN_POWER_GAIN = 3.0 / 8.0
val HANN_AMPL_CORR = sqrt(HANN_POWER_GAIN / (HANN_COHERENT_GAIN * HANN_COHERENT_GAIN))

fun frequencyResolution(fs: Double, nperseg: Int): Double = fs / nperseg

fun welchPsd(
    signal: DoubleArray,
    fs: Double,
    nperseg: Int? = null,
    noverlap: Int? = null,
): Pair<DoubleArray, DoubleArray> {
    require(signal.size >= 2) { "signal must contain at least 2 samples" }
    val nps = (nperseg ?: SignalMath.autoNperseg(fs, signal.size)).coerceAtMost(signal.size)
    val nov = noverlap ?: (nps / 2)
    return SignalMath.welchPsd(signal, fs, nps, nov)
}

fun welchAmplitude(
    signal: DoubleArray,
    fs: Double,
    nperseg: Int? = null,
    noverlap: Int? = null,
): Pair<DoubleArray, DoubleArray> {
    val (freqs, psd) = welchPsd(signal, fs, nperseg, noverlap)
    val df = if (freqs.size > 1) freqs[1] - freqs[0] else 1.0
    val corr2 = HANN_AMPL_CORR * HANN_AMPL_CORR
    val amp = DoubleArray(psd.size) { k ->
        val base = psd[k] * df * corr2
        if (k == 0) sqrt(base) else sqrt(2.0 * base)
    }
    return freqs to amp
}
