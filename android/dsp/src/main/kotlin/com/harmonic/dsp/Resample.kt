package com.harmonic.dsp

data class ResampleResult(val signal: DoubleArray, val timestamps: DoubleArray, val fsOut: Double)

fun measureFs(timestamps: DoubleArray): Double {
    val dts = SignalMath.diff(timestamps)
    require(dts.isNotEmpty()) { "Need at least 2 timestamps" }
    val median = SignalMath.median(dts)
    require(median > 0) { "Median dt is non-positive" }
    return 1.0 / median
}

fun resampleUniform(
    signal: DoubleArray,
    timestamps: DoubleArray,
    fsTarget: Double? = null,
    fsSource: Double? = null,
): ResampleResult {
    require(signal.size == timestamps.size)
    val source = fsSource ?: measureFs(timestamps)
    val target = fsTarget ?: maxOf(50.0, kotlin.math.round(source / 50.0) * 50.0)
    val tStart = timestamps.first()
    val tEnd = timestamps.last()
    val nOut = kotlin.math.floor((tEnd - tStart) * target).toInt() + 1
    val ts = SignalMath.linspace(tStart, tEnd, nOut)
    val uniform = SignalMath.cubicSpline(timestamps, signal, ts)
    return ResampleResult(uniform, ts, target)
}

data class CommonGrid(val signals: List<DoubleArray>, val timestamps: DoubleArray, val fsOut: Double)

fun resampleToCommonGrid(
    signals: List<DoubleArray>,
    timestampsList: List<DoubleArray>,
    fsTarget: Double? = null,
): CommonGrid {
    require(signals.size == timestampsList.size && signals.isNotEmpty())
    var tStart = Double.NEGATIVE_INFINITY
    var tEnd = Double.POSITIVE_INFINITY
    for (ts in timestampsList) {
        if (ts.first() > tStart) tStart = ts.first()
        if (ts.last() < tEnd) tEnd = ts.last()
    }
    require(tStart < tEnd) { "No overlap between recording windows" }
    val target = fsTarget ?: run {
        val measured = timestampsList.minOf { measureFs(it) }
        maxOf(50.0, kotlin.math.round(measured / 50.0) * 50.0)
    }
    val nOut = kotlin.math.floor((tEnd - tStart) * target).toInt() + 1
    val ts = SignalMath.linspace(tStart, tEnd, nOut)
    val uniform = signals.zip(timestampsList).map { (sig, t) ->
        SignalMath.cubicSpline(t, sig, ts)
    }
    return CommonGrid(uniform, ts, target)
}
