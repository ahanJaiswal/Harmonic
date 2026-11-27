package com.harmonic.dsp

import kotlin.math.sqrt

fun estimateGravity(accelRaw: Array<DoubleArray>): DoubleArray {
    require(accelRaw.isNotEmpty() && accelRaw[0].size == 3)
    val g = DoubleArray(3)
    for (row in accelRaw) {
        g[0] += row[0]; g[1] += row[1]; g[2] += row[2]
    }
    val n = accelRaw.size.toDouble()
    g[0] /= n; g[1] /= n; g[2] /= n
    return g
}

data class ProjectedAxes(val v: DoubleArray, val a: DoubleArray, val h: DoubleArray)

fun projectAxes(
    accelRaw: Array<DoubleArray>,
    gVec: DoubleArray,
    shaftAxisDevice: DoubleArray? = null,
): ProjectedAxes {
    require(accelRaw.isNotEmpty() && accelRaw[0].size == 3)
    val gNorm = norm(gVec) + 1e-12
    val vHat = doubleArrayOf(-gVec[0] / gNorm, -gVec[1] / gNorm, -gVec[2] / gNorm)
    val shaft = shaftAxisDevice ?: doubleArrayOf(0.0, -1.0, 0.0)
    val dot = shaft[0] * vHat[0] + shaft[1] * vHat[1] + shaft[2] * vHat[2]
    val aOrth = doubleArrayOf(
        shaft[0] - dot * vHat[0],
        shaft[1] - dot * vHat[1],
        shaft[2] - dot * vHat[2],
    )
    val aNorm = norm(aOrth)
    if (aNorm < 1e-6) {
        throw IllegalArgumentException("shaft axis is nearly parallel to gravity")
    }
    val aHat = doubleArrayOf(aOrth[0] / aNorm, aOrth[1] / aNorm, aOrth[2] / aNorm)
    val hRaw = cross(vHat, aHat)
    val hNorm = norm(hRaw) + 1e-12
    val hHat = doubleArrayOf(hRaw[0] / hNorm, hRaw[1] / hNorm, hRaw[2] / hNorm)
    val n = accelRaw.size
    val v = DoubleArray(n)
    val a = DoubleArray(n)
    val h = DoubleArray(n)
    for (i in 0 until n) {
        val r = accelRaw[i]
        v[i] = r[0] * vHat[0] + r[1] * vHat[1] + r[2] * vHat[2]
        a[i] = r[0] * aHat[0] + r[1] * aHat[1] + r[2] * aHat[2]
        h[i] = r[0] * hHat[0] + r[1] * hHat[1] + r[2] * hHat[2]
    }
    return ProjectedAxes(v, a, h)
}

fun removeDc(signal: DoubleArray): DoubleArray {
    val m = SignalMath.mean(signal)
    return DoubleArray(signal.size) { signal[it] - m }
}

private fun norm(v: DoubleArray): Double = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])

private fun cross(u: DoubleArray, v: DoubleArray) = doubleArrayOf(
    u[1] * v[2] - u[2] * v[1],
    u[2] * v[0] - u[0] * v[2],
    u[0] * v[1] - u[1] * v[0],
)
