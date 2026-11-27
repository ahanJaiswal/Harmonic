package com.harmonic.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WhyTest {
    private fun features(
        phase: Map<String, Double>? = null,
        coherence: Map<String, Double>? = null,
        one: Double = 1.0,
        two: Double = 0.1,
        harmonics: Int = 1,
        axial: Double? = null,
    ) = buildMap<String, Any?> {
        put("orders", mapOf("1x" to one, "2x" to two, "3x" to 0.05, "harmonics_count_above_floor" to harmonics))
        if (phase != null) put("phase_deg", phase)
        if (coherence != null) put("coherence", coherence)
        if (axial != null) put("axial_to_radial_ratio", axial)
    }

    @Test
    fun noEvidenceGivesNothing() {
        assertNull(explain(emptyMap(), "unknown"))
    }

    @Test
    fun singlePhoneHasNoMotion() {
        val why = explain(features(), "imbalance")!!
        assertNull(why.motion)
        assertEquals("1x", why.strongest)
        assertTrue(why.lines.single().contains("once per turn"))
    }

    @Test
    fun inPhaseReadsAsTogether() {
        val why = explain(features(mapOf("AB_H_1x" to 12.0), mapOf("AB_H_1x" to 0.9)), "imbalance")!!
        assertEquals(Motion.Together, why.motion)
        assertTrue(why.lines.first().startsWith("Both phones moved the same way"))
    }

    @Test
    fun nearOneEightyReadsAsOpposite() {
        // -170 and 190 are the same angle; both must read as opposite.
        listOf(-170.0, 190.0).forEach { p ->
            val why = explain(features(mapOf("AB_H_1x" to p), mapOf("AB_H_1x" to 0.9)), "misalignment")!!
            assertEquals(Motion.Opposite, why.motion, "phase $p")
        }
    }

    @Test
    fun picksTheBestMatchedAxis() {
        val why = explain(
            features(
                mapOf("AB_H_1x" to 90.0, "AB_V_1x" to 175.0),
                mapOf("AB_H_1x" to 0.81, "AB_V_1x" to 0.95),
            ),
            "misalignment",
        )!!
        assertEquals(Motion.Opposite, why.motion)
        assertEquals(175.0, why.phaseDeg)
    }

    @Test
    fun coherenceWithoutPhaseIsUnclear() {
        val why = explain(features(coherence = mapOf("AB_H_1x" to 0.4)), "unknown")!!
        assertEquals(Motion.Unclear, why.motion)
    }

    @Test
    fun strongTwiceAndAxialPointToMisalignment() {
        val why = explain(features(two = 0.8, axial = 0.7), "misalignment")!!
        assertTrue(why.lines.any { it.contains("twice-per-turn") })
        assertTrue(why.lines.any { it.contains("along the shaft") })
    }

    @Test
    fun manyHarmonicsPointToLooseness() {
        val why = explain(features(harmonics = 6), "looseness")!!
        assertTrue(why.lines.any { it.contains("loose") })
    }

    @Test
    fun healthyHasNoStandoutBeat() {
        val why = explain(features(axial = 0.9), "healthy")!!
        assertEquals(listOf("No beat stands out."), why.lines)
    }
}
