package com.harmonic.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Regressions for issues found in the Nov 27 stress test (commit 648589c). */
class StressRegressionTest {
    private val fs = 100.0
    private val n = (15 * fs).toInt()

    private fun gaussian(seed: Int) = Random(seed).let { r ->
        DoubleArray(n) {
            // Box-Muller
            val u = r.nextDouble().coerceAtLeast(1e-12)
            val v = r.nextDouble()
            kotlin.math.sqrt(-2 * kotlin.math.ln(u)) * cos(2 * PI * v)
        }
    }

    // H1
    @Test
    fun independentNoiseIsNotTrustedByDefault() {
        val r = crossPhaseCoherence(gaussian(1), gaussian(2), fs, 25.0)
        assertTrue(r.coherence < 0.8, "coherence ${r.coherence}")
        assertNull(r.phaseDeg)
    }

    @Test
    fun sharedToneKeepsItsPhaseByDefault() {
        val noise = gaussian(3)
        val a = DoubleArray(n) { sin(2 * PI * 25.0 * it / fs) + 0.05 * noise[it] }
        val b = DoubleArray(n) { sin(2 * PI * 25.0 * it / fs + PI) + 0.05 * noise[(it + 17) % n] }
        val r = crossPhaseCoherence(a, b, fs, 25.0)
        assertTrue(r.trusted)
        assertTrue(abs(abs(r.phaseDeg!!) - 180.0) < 5.0, "phase ${r.phaseDeg}")
    }

    // M6
    @Test
    fun bluesteinMatchesTheDirectDft() {
        val len = 750
        val x = DoubleArray(len) { sin(0.37 * it) + 0.3 * cos(1.9 * it) + (it % 7) * 0.01 }
        val (re, im) = SignalMath.rfft(x)
        for (k in listOf(0, 1, 13, 100, 374, 375)) {
            var sr = 0.0
            var si = 0.0
            for (t in 0 until len) {
                val ang = -2.0 * PI * k * t / len
                sr += x[t] * cos(ang)
                si += x[t] * sin(ang)
            }
            assertEquals(sr, re[k], 1e-7)
            assertEquals(si, im[k], 1e-7)
        }
    }

    // L1
    @Test
    fun nonFiniteVelocityIsRefusedNotZoneD() {
        assertFailsWith<IllegalArgumentException> { classifyZone(Double.NaN) }
        assertFailsWith<IllegalArgumentException> { classifyZone(Double.POSITIVE_INFINITY) }
    }

    private fun recording(fRun: Double, a1: Double, a2: Double, axialOnly: Boolean = false, nanAt: Int = -1) =
        Array(n) { i ->
            val t = i / fs
            val s = a1 * sin(2 * PI * fRun * t) + a2 * sin(2 * PI * 2 * fRun * t)
            // Phone flat, screen up: gravity on +Z; device X is horizontal radial, -Y axial.
            val row = if (axialOnly) doubleArrayOf(0.0, s, 9.81) else doubleArrayOf(s, 0.0, 9.81)
            if (i == nanAt) doubleArrayOf(Double.NaN, 0.0, 9.81) else row
        } to DoubleArray(n) { it / fs }

    @Test
    fun nanSamplesAreDroppedNotPropagated() {
        val (raw, ts) = recording(25.0, 0.5, 0.0, nanAt = 200)
        val r = analyzePhone(raw, ts, 1500.0)
        assertTrue(r.velocityH.isFinite())
    }

    // H2
    @Test
    fun blankRpmFindsSpeedsFarFromTheOldDefault() {
        for (fRun in listOf(10.0, 15.0, 24.0, 40.0)) {
            val (raw, ts) = recording(fRun, 0.6, 0.15)
            val r = analyzePhone(raw, ts, 0.0)
            assertEquals(fRun, r.runningSpeedHz, 0.5, "true speed $fRun Hz")
        }
    }

    @Test
    fun blankRpmDoesNotMistakeADominantTwoXForOneX() {
        val (raw, ts) = recording(15.0, 0.4, 0.6)
        val r = analyzePhone(raw, ts, 0.0)
        assertEquals(15.0, r.runningSpeedHz, 0.5)
    }

    // H5
    @Test
    fun severityCountsTheAxialAxis() {
        val (raw, ts) = recording(25.0, 3.0, 0.0, axialOnly = true)
        val r = analyzePhone(raw, ts, 1500.0)
        assertTrue(r.velocityA > r.velocityH)
        assertEquals(classifyZone(maxOf(r.velocityH, r.velocityV, r.velocityA)), r.severityZone)
        assertNotEquals("A", r.severityZone)
    }

    private fun features(zone: String = "C", one: Double = 1.0, two: Double = 0.8, axial: Double = 0.7) =
        mapOf<String, Any?>(
            "severity_zone" to zone,
            "orders" to mapOf("1x" to one, "2x" to two, "0.5x" to 0.0, "harmonics_count_above_floor" to 1),
            "axial_to_radial_ratio" to axial,
        )

    // H3
    @Test
    fun familyNamesFromTheModelKeepTheirMeaning() {
        val reply = """{"fault":"misalignment","severity_zone":"C","confidence":0.8,
            "summary":"s","recommended_action":"Check the coupling.",
            "hypotheses":[{"fault":"misalignment","p":0.8},{"fault":"imbalance","p":0.2}]}"""
        val (dx, _) = finalizeDiagnosis(reply, features(), "cloud")
        assertEquals("misalignment", dx.fault)
        assertEquals("Check the coupling.", dx.recommendedAction)
    }

    // H4
    @Test
    fun whenTheGateOverrulesTheModelItsActionIsNotShown() {
        // Model says imbalance, but 2x > 1x, so the gate rejects it.
        val reply = """{"fault":"imbalance","severity_zone":"C","confidence":0.95,
            "summary":"Out of balance.","recommended_action":"Add a balance weight.",
            "hypotheses":[{"fault":"static_imbalance","p":0.95}]}"""
        val (dx, gate) = finalizeDiagnosis(reply, features(one = 0.3, two = 0.9), "cloud")
        assertTrue(gate.actions.any { it.action == "REJECT" })
        assertNotEquals("Add a balance weight.", dx.recommendedAction)
        assertNotEquals("Out of balance.", dx.summary)
        assertTrue(dx.confidence < 0.95)
    }

    // L2
    @Test
    fun gateRejectsUnknownNamesAndClampsP() {
        val hyp = mapOf<String, Any?>(
            "hypotheses" to listOf(mapOf("fault" to "gremlins", "p" to 0.9), "junk", mapOf("fault" to "healthy", "p" to 4.0)),
            "severity_zone" to "B",
        )
        val g = physicsGate(hyp, features(zone = "B", two = 0.1, axial = 0.1))
        @Suppress("UNCHECKED_CAST")
        val kept = g.correctedHypothesis["hypotheses"] as List<Map<String, Any?>>
        assertEquals(listOf("healthy"), kept.map { it["fault"] })
        assertEquals(1.0, (kept.single()["p"] as Number).toDouble())
    }

    // H6
    @Test
    fun zoneANoiseIsNotCalledMisalignmentOffline() {
        val dx = physicsDiagnosis(features(zone = "A", one = 0.01, two = 0.009, axial = 0.95))
        assertEquals("healthy", dx.fault)
    }
}
