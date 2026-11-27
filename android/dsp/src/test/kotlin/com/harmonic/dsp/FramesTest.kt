package com.harmonic.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FramesTest {
    /** Phone standing on its bottom edge: gravity on +Y, a 30 Hz tone on X. */
    private fun upright(n: Int = 2000) = Array(n) { i ->
        doubleArrayOf(0.5 * sin(2 * PI * 30.0 * i / 500.0), 9.81, 0.0)
    }

    @Test
    fun uprightPhoneFallsBackToScreenNormal() {
        val raw = upright()
        val axes = projectAxes(raw, estimateGravity(raw))
        assertEquals(9.81, abs(axes.v.average()), 1e-6)
        assertTrue(axes.a.all { abs(it) < 1e-9 }, "axial should be quiet")
        assertTrue(axes.h.maxOf { abs(it) } > 0.49, "the X tone should land in H")
    }

    @Test
    fun uprightPhoneAnalyzesWithoutThrowing() {
        val raw = upright()
        val ts = DoubleArray(raw.size) { it / 500.0 }
        val result = analyzePhone(raw, ts, 1800.0)
        assertTrue(result.velocityH > 0.0)
    }
}
