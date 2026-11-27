package com.harmonic.dsp

data class GateAction(
    val action: String,
    val fault: String?,
    val reason: String,
    val nextTest: String? = null,
)

data class GateResult(
    val accepted: Boolean,
    val actions: List<GateAction>,
    val correctedHypothesis: Map<String, Any?>,
    val escalateToCloud: Boolean,
)

private fun numOr(map: Map<*, *>?, key: String, fallback: Double = 0.0): Double {
    if (map == null) return fallback
    val v = map[key] ?: return fallback
    return when (v) {
        is Number -> v.toDouble()
        else -> fallback
    }
}

@Suppress("UNCHECKED_CAST")
fun physicsGate(
    hypothesis: Map<String, Any?>,
    features: Map<String, Any?>,
    coherenceMin: Double = 0.8,
    phaseInPhaseMaxDeg: Double = 45.0,
    phaseOutOfPhaseMinDeg: Double = 135.0,
    misalignmentMin2xRatio: Double = 0.5,
    loosenessMinHarmonics: Int = 3,
    phaseStabilityMaxDeg: Double = 30.0,
): GateResult {
    val actions = ArrayList<GateAction>()
    var accepted = true
    var escalate = false
    fun log(action: String, fault: String?, reason: String, next: String? = null) {
        actions.add(GateAction(action, fault, reason, next))
    }

    val rawHyps = hypothesis["hypotheses"] as? List<*> ?: emptyList<Any?>()
    val hypList = rawHyps.map { item ->
        val copy = LinkedHashMap<String, Any?>()
        (item as Map<*, *>).forEach { (k, v) -> copy[k.toString()] = v }
        copy
    }
    var severity = hypothesis["severity_zone"] as? String ?: "A"
    val evidence = (hypothesis["evidence"] as? List<*>)?.toMutableList() ?: mutableListOf()
    var nextTest = hypothesis["next_test"] as? String ?: "none"

    val orders = features["orders"] as? Map<*, *>
    val phaseDeg = features["phase_deg"] as? Map<*, *>
    val coherence = features["coherence"] as? Map<*, *>
    val dspZone = features["severity_zone"] as? String ?: "A"
    val harmonics = numOr(orders, "harmonics_count_above_floor").toInt()
    val axRad = numOr(features as Map<*, *>, "axial_to_radial_ratio")
    val phaseStab = (features["phase_stability_deg"] as? Number)?.toDouble()
    val amp1 = numOr(orders, "1x")
    val amp2 = numOr(orders, "2x")
    val amp05 = numOr(orders, "0.5x")

    if (severity != dspZone) {
        log(
            "OVERWRITE_ZONE", null,
            "Model reported zone $severity but DSP computed $dspZone; " +
                "overwriting — model must never override measured severity.",
        )
        severity = dspZone
        accepted = false
    }

    val kept = ArrayList<Map<String, Any?>>()
    for (hyp0 in hypList) {
        val fault = hyp0["fault"] as? String ?: "unknown"
        val p = (hyp0["p"] as? Number)?.toDouble() ?: 0.0
        var hyp: Map<String, Any?> = hyp0
        var keep = true

        if (fault == "static_imbalance" || fault == "couple_imbalance") {
            if (amp1 < amp2 && amp2 > 1e-6) {
                log("REJECT", fault, "Imbalance claimed but 2x ($amp2) > 1x ($amp1). Imbalance shows dominant 1x.")
                escalate = true
                keep = false
            } else if (harmonics > 3) {
                log("DOWNGRADE", fault, "Imbalance claimed but harmonics_count_above_floor=$harmonics (> 3); looseness may also be present.")
                hyp = LinkedHashMap(hyp).also { it["p"] = p * 0.6 }
            }
        }
        if (fault == "static_imbalance" && keep) {
            val phase = (phaseDeg?.get("AB_H_1x") as? Number)?.toDouble()
            val coh = (coherence?.get("AB_H_1x") as? Number)?.toDouble() ?: 0.0
            if (phase != null && coh >= coherenceMin && kotlin.math.abs(phase) > phaseInPhaseMaxDeg) {
                log(
                    "DOWNGRADE", fault,
                    "Static imbalance claimed, but DE–NDE radial phase = $phase° (expected < $phaseInPhaseMaxDeg°).",
                    "radial_phase_DE_NDE",
                )
                if (nextTest == "none") nextTest = "radial_phase_DE_NDE"
                hyp = LinkedHashMap(hyp).also { it["p"] = p * 0.7 }
            }
        }
        if (fault == "couple_imbalance" && keep) {
            val phase = (phaseDeg?.get("AB_H_1x") as? Number)?.toDouble()
            val coh = (coherence?.get("AB_H_1x") as? Number)?.toDouble() ?: 0.0
            if (phase != null && coh >= coherenceMin && kotlin.math.abs(phase) < phaseOutOfPhaseMinDeg) {
                log(
                    "DOWNGRADE", fault,
                    "Couple imbalance claimed but DE–NDE radial phase = $phase° (expected ≥ $phaseOutOfPhaseMinDeg°).",
                    "radial_phase_DE_NDE",
                )
                hyp = LinkedHashMap(hyp).also { it["p"] = p * 0.7 }
            }
        }
        if (fault == "angular_misalignment" || fault == "parallel_misalignment") {
            val twoX = amp2 / (amp1 + 1e-12)
            val axialStrong = axRad >= misalignmentMin2xRatio
            val twoStrong = twoX >= misalignmentMin2xRatio
            if (!twoStrong && !axialStrong) {
                log(
                    "REJECT", fault,
                    "Misalignment claimed but 2x/1x=$twoX and axial/radial=$axRad (both < $misalignmentMin2xRatio).",
                )
                escalate = true
                keep = false
            }
        }
        if (fault == "structural_looseness" || fault == "rotating_looseness") {
            val unstable = phaseStab != null && phaseStab > phaseStabilityMaxDeg
            val hasHarm = harmonics >= loosenessMinHarmonics
            val hasSub = amp05 > 0.1 * amp1
            if (!hasHarm && !hasSub && !unstable) {
                log("REJECT", fault, "Looseness claimed but harmonics_count=$harmonics, no 0.5x subharmonic, and phase is stable.")
                escalate = true
                keep = false
            }
        }
        if (fault == "resonance") {
            log("DOWNGRADE", fault, "Resonance cannot be confirmed without vary_speed evidence.", "vary_speed")
            if (nextTest == "none") nextTest = "vary_speed"
            hyp = LinkedHashMap(hyp).also { it["p"] = p * 0.5 }
        }
        if (fault == "healthy") {
            if (dspZone == "C" || dspZone == "D") {
                log("REJECT", fault, "'Healthy' claimed but DSP severity zone is $dspZone.")
                escalate = true
                keep = false
            } else if (harmonics > 2 || amp05 > 0.2 * amp1) {
                log("DOWNGRADE", fault, "Healthy claimed but elevated harmonics or subharmonics present.")
                hyp = LinkedHashMap(hyp).also { it["p"] = p * 0.6 }
            }
        }
        if (keep) kept.add(hyp)
    }

    if (kept.isEmpty()) {
        accepted = false
        escalate = true
        log("REJECT", null, "All hypotheses were rejected by the physics gate. Escalating to cloud.")
    } else if (actions.isNotEmpty()) {
        accepted = false
    }

    val corrected = linkedMapOf<String, Any?>(
        "hypotheses" to kept,
        "severity_zone" to severity,
        "evidence" to evidence,
        "next_test" to nextTest,
    )
    return GateResult(accepted, actions, corrected, escalate)
}
