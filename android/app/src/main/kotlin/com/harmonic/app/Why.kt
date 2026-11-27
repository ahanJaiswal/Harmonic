package com.harmonic.app

import kotlin.math.abs

/** How two phones moved relative to each other at running speed. */
enum class Motion { Together, Opposite, OutOfStep, Unclear }

data class Beat(val key: String, val label: String, val value: Double)

/**
 * Plain-language evidence behind a result, built from the same feature JSON the model sees.
 * [phaseDeg] is phone B relative to phone A at 1× (0 = together, ±180 = opposite).
 */
data class Why(
    val motion: Motion?,
    val phaseDeg: Double?,
    val beats: List<Beat>,
    val strongest: String?,
    val lines: List<String>,
)

/** Same cut-offs as the physics gate (Gate.kt): in phase below 45°, out of phase above 135°. */
private const val IN_PHASE_MAX = 45.0
private const val OUT_OF_PHASE_MIN = 135.0

/** Radial axes first: they carry the imbalance and misalignment signature. */
private val PHASE_KEYS = listOf("AB_H_1x", "AB_V_1x", "AB_A_1x")

@Suppress("UNCHECKED_CAST")
fun explain(features: Map<String, Any?>, fault: String): Why? {
    val orders = features["orders"] as? Map<String, Any?>
    val phase = features["phase_deg"] as? Map<String, Any?>
    val coherence = features["coherence"] as? Map<String, Any?>
    if (orders == null && phase == null && coherence == null) return null

    // Phase is only reported when the two phones shared a steady signal, so its
    // presence already means the coherence passed. Pick the best-matched axis.
    val pick = PHASE_KEYS
        .mapNotNull { k -> (phase?.get(k) as? Number)?.toDouble()?.let { k to it } }
        .maxByOrNull { (k, _) -> (coherence?.get(k) as? Number)?.toDouble() ?: 0.0 }
    val phaseDeg = pick?.second?.let(::wrap180)
    val twoPhones = phase != null || coherence != null
    val motion = when {
        phaseDeg != null && abs(phaseDeg) <= IN_PHASE_MAX -> Motion.Together
        phaseDeg != null && abs(phaseDeg) >= OUT_OF_PHASE_MIN -> Motion.Opposite
        phaseDeg != null -> Motion.OutOfStep
        twoPhones -> Motion.Unclear
        else -> null
    }

    fun order(k: String) = (orders?.get(k) as? Number)?.toDouble() ?: 0.0
    val beats = listOf(
        Beat("1x", "Once", order("1x")),
        Beat("2x", "Twice", order("2x")),
        Beat("3x", "3 times", order("3x")),
    )
    val top = beats.maxByOrNull { it.value }?.takeIf { it.value > 0.0 }
    val harmonics = (orders?.get("harmonics_count_above_floor") as? Number)?.toInt() ?: 0
    val axial = (features["axial_to_radial_ratio"] as? Number)?.toDouble()

    val lines = buildList {
        when (motion) {
            Motion.Together -> add("Both phones moved the same way at the same moment. The whole machine is pushed around once per turn, which is how an out-of-balance part behaves.")
            Motion.Opposite -> add("The phones moved in opposite directions at the same moment, so the two ends rock against each other. Shafts that aren't lined up do this, and so does a part that is heavy on opposite sides at each end.")
            Motion.OutOfStep -> add("The phones moved out of step, partly together and partly against each other. That points to a mix of causes.")
            Motion.Unclear -> add("The two phones didn't share a steady rhythm, so they couldn't settle the cause. Try again with the phones closer to the bearings.")
            null -> Unit
        }
        val one = order("1x")
        val two = order("2x")
        when {
            fault == "healthy" || top == null -> add("No beat stands out.")
            harmonics >= 4 -> add("Many repeating beats show up, which often means something is loose.")
            two >= 0.5 * one && two > 0 -> add("There's a strong twice-per-turn beat, a common sign of shafts that aren't lined up.")
            top.key == "1x" -> add("Most of the shaking comes once per turn, the usual sign of an out-of-balance part.")
            else -> add("The strongest beat comes ${top.label.lowercase()} per turn.")
        }
        if (axial != null && axial >= 0.5 && fault != "healthy") {
            add("There's a lot of shaking along the shaft, which also points to shafts that aren't lined up.")
        }
    }
    return Why(motion, phaseDeg, beats, top?.key, lines)
}

private fun wrap180(deg: Double): Double {
    var d = deg % 360.0
    if (d > 180.0) d -= 360.0
    if (d < -180.0) d += 360.0
    return d
}

/** Feature fields a joining phone needs to show the same explanation as the host. */
val WHY_FEATURE_KEYS = listOf("orders", "phase_deg", "coherence", "axial_to_radial_ratio")
