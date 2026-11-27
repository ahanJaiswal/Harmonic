package com.harmonic.dsp

data class AxisSeries(
    val v: DoubleArray,
    val a: DoubleArray,
    val h: DoubleArray,
    val timestamps: DoubleArray,
    val fs: Double,
)

data class PhoneAnalysis(
    val fsHz: Double,
    val durationS: Double,
    val runningSpeedHz: Double,
    val rpmRun: Double,
    val speedConfidence: Double,
    val orders: Map<String, Double>,
    val velocityH: Double,
    val velocityV: Double,
    val velocityA: Double,
    val severityZone: String,
    val dominantAxis: String,
    val axialToRadial: Double?,
    val crestFactor: Double,
    val kurtosis: Double,
    val uniform: AxisSeries,
    val spectrumFreqs: DoubleArray,
    val spectrumAmp: DoubleArray,
)

/**
 * On-device path for one phone: gravity frame, resample, Welch, orders,
 * velocity RMS, ISO zone. [raw] is (N, 3) acceleration including gravity.
 */
fun analyzePhone(
    raw: Array<DoubleArray>,
    timestamps: DoubleArray,
    rpmNameplate: Double,
    isoClass: String = "class_I",
    fsTarget: Double? = null,
    thresholds: Map<String, ZoneThresholds>? = null,
): PhoneAnalysis {
    val keep = raw.indices.filter { i ->
        raw[i].all { it.isFinite() } && timestamps[i].isFinite()
    }
    require(keep.size >= 32) { "Not enough valid samples (${keep.size})" }
    val raw = if (keep.size == raw.size) raw else Array(keep.size) { raw[keep[it]] }
    val timestamps = if (keep.size == timestamps.size) timestamps else DoubleArray(keep.size) { timestamps[keep[it]] }
    val g = estimateGravity(raw)
    val axes = projectAxes(raw, g)
    val v = removeDc(axes.v)
    val a = removeDc(axes.a)
    val h = removeDc(axes.h)
    val rv = resampleUniform(v, timestamps, fsTarget)
    val ra = resampleUniform(a, timestamps, rv.fsOut)
    val rh = resampleUniform(h, timestamps, rv.fsOut)
    val fs = rv.fsOut
    val (fH, ampH) = welchAmplitude(rh.signal, fs)
    val (_, ampV) = welchAmplitude(rv.signal, fs)
    val (_, ampA) = welchAmplitude(ra.signal, fs)
    val vH = overallVelocityRms(fH, ampH)
    val vV = overallVelocityRms(fH, ampV)
    val vA = overallVelocityRms(fH, ampA)
    val dominantIsH = vH >= vV
    val domAmp = if (dominantIsH) ampH else ampV
    val domSig = if (dominantIsH) rh.signal else rv.signal
    val speed = if (rpmNameplate > 0) findRunningSpeed(fH, domAmp, rpmNameplate) else searchRunningSpeed(fH, domAmp, fs)
    val orders = extractOrders(fH, domAmp, speed.fRunHz)
    val ordersH = extractOrders(fH, ampH, speed.fRunHz)
    val ordersV = extractOrders(fH, ampV, speed.fRunHz)
    val ordersA = extractOrders(fH, ampA, speed.fRunHz)
    val radial = maxOf(ordersH["1x"] ?: 0.0, ordersV["1x"] ?: 0.0)
    val axialRatio = if (radial > 1e-9) (ordersA["1x"] ?: 0.0) / radial else null
    val zone = classifyZone(maxOf(vH, vV, vA), isoClass, thresholds)
    val duration = if (timestamps.size >= 2) timestamps.last() - timestamps.first() else 0.0
    return PhoneAnalysis(
        fsHz = fs,
        durationS = duration,
        runningSpeedHz = speed.fRunHz,
        rpmRun = speed.rpmRun,
        speedConfidence = speed.confidence,
        orders = orders,
        velocityH = vH,
        velocityV = vV,
        velocityA = vA,
        severityZone = zone,
        dominantAxis = if (dominantIsH) "H" else "V",
        axialToRadial = axialRatio,
        crestFactor = crestFactor(domSig),
        kurtosis = kurtosis(domSig),
        uniform = AxisSeries(rv.signal, ra.signal, rh.signal, rv.timestamps, fs),
        spectrumFreqs = fH,
        spectrumAmp = domAmp,
    )
}

/** Lowest and highest shaft speeds searched when the RPM is unknown: 480 to 3600 RPM. */
private const val UNKNOWN_RPM_LO_HZ = 8.0
private const val UNKNOWN_RPM_HI_HZ = 60.0

/**
 * Running speed with no nameplate RPM: search the whole usable band, then step down to
 * half the frequency when a strong peak sits there, so a dominant 2× is not taken for 1×.
 */
fun searchRunningSpeed(freqs: DoubleArray, amp: DoubleArray, fs: Double): RunningSpeed {
    val hi = minOf(UNKNOWN_RPM_HI_HZ, fs * 0.4)
    val lo = minOf(UNKNOWN_RPM_LO_HZ, hi / 2.0)
    val center = (lo + hi) / 2.0
    val fraction = (hi - lo) / (hi + lo)
    val first = findRunningSpeed(freqs, amp, center * 60.0, fraction)
    val half = first.fRunHz / 2.0
    if (half >= lo) {
        val sub = findRunningSpeed(freqs, amp, half * 60.0, 0.06)
        val peakAt = { f: Double ->
            val i = freqs.indices.minByOrNull { kotlin.math.abs(freqs[it] - f) } ?: 0
            amp[i]
        }
        if (peakAt(sub.fRunHz) >= 0.5 * peakAt(first.fRunHz)) return sub
    }
    return first
}

fun crestFactor(x: DoubleArray): Double {
    val r = SignalMath.rms(x)
    if (r < 1e-12) return 0.0
    return SignalMath.maxAbs(x) / r
}

fun kurtosis(x: DoubleArray): Double {
    val m = SignalMath.mean(x)
    var c2 = 0.0
    var c4 = 0.0
    for (v in x) {
        val d = v - m
        val d2 = d * d
        c2 += d2
        c4 += d2 * d2
    }
    c2 /= x.size
    c4 /= x.size
    if (c2 < 1e-18) return 0.0
    return c4 / (c2 * c2)
}

data class Diagnosis(
    val fault: String,
    val faultDetail: String,
    val summary: String,
    val severityZone: String,
    val confidence: Double,
    val recommendedAction: String,
    val hypotheses: List<Map<String, Any?>>,
    val evidence: List<String>,
    val nextTest: String,
    val inferenceSource: String,
)

/** Deterministic screening used when the hybrid model is unavailable. */
fun physicsDiagnosis(features: Map<String, Any?>): Diagnosis {
    val orders = features["orders"] as? Map<*, *> ?: emptyMap<String, Any?>()
    fun o(key: String) = (orders[key] as? Number)?.toDouble() ?: 0.0
    val amp1 = o("1x")
    val amp2 = o("2x")
    val amp05 = o("0.5x")
    val harmonics = o("harmonics_count_above_floor")
    val axial = (features["axial_to_radial_ratio"] as? Number)?.toDouble() ?: 0.0
    val kurt = (features["kurtosis"] as? Number)?.toDouble() ?: 0.0
    val zone = features["severity_zone"] as? String ?: "A"
    val phase = ((features["phase_deg"] as? Map<*, *>)?.get("AB_H_1x") as? Number)?.toDouble()
    val twoRatio = if (amp1 > 1e-9) amp2 / amp1 else if (amp2 > 0) 99.0 else 0.0

    val hyps = ArrayList<MutableMap<String, Any?>>()
    fun add(fault: String, p: Double) {
        hyps.add(linkedMapOf("fault" to fault, "p" to p))
    }
    if (twoRatio >= 0.5 || axial >= 0.5) {
        val detail = if (axial >= twoRatio) "angular_misalignment" else "parallel_misalignment"
        add(detail, (0.55 + 0.35 * kotlin.math.min(1.0, maxOf(twoRatio, axial))).coerceAtMost(0.95))
    }
    if (harmonics >= 3 || amp05 > 0.1 * amp1) {
        add("structural_looseness", (0.5 + 0.08 * harmonics).coerceAtMost(0.9))
    }
    if (amp1 >= amp2 && amp1 > 0) {
        val detail = when {
            phase != null && kotlin.math.abs(phase) >= 135.0 -> "couple_imbalance"
            else -> "static_imbalance"
        }
        val dominance = if (amp1 + amp2 > 0) amp1 / (amp1 + amp2) else 0.5
        add(detail, (0.45 + 0.45 * dominance).coerceAtMost(0.92))
    }
    if (kurt >= 5.0) add("unknown", 0.35)
    if (hyps.isEmpty() || (zone == "A" || zone == "B") && twoRatio < 0.3 && harmonics < 2) {
        add("healthy", if (zone == "A" || zone == "B") 0.7 else 0.25)
    }
    if (zone == "A") {
        // Zone A means the overall level is low; ratios between noise-level peaks are
        // not evidence of a fault, so lean towards healthy.
        for (h in hyps) if (h["fault"] != "healthy") h["p"] = (h["p"] as Number).toDouble() * 0.6
        if (hyps.none { it["fault"] == "healthy" }) add("healthy", 0.7)
    }
    hyps.sortByDescending { (it["p"] as Number).toDouble() }
    val top = hyps.first()
    val detail = top["fault"] as String
    val family = faultFamily(detail, kurt)
    val conf = (top["p"] as Number).toDouble()
    val action = recommendedAction(family, zone)
    val shortZone = when (zone) {
        "A" -> "good"
        "B" -> "acceptable"
        "C" -> "unsatisfactory"
        "D" -> "damage likely"
        else -> zone
    }
    val summary = buildString {
        append(plainFault(family).replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() })
        append(". ISO zone ")
        append(zone)
        append(" — ")
        append(shortZone)
        append(".")
    }
    return Diagnosis(
        fault = family,
        faultDetail = if (family == "bearing_wear") "unknown" else detail,
        summary = summary,
        severityZone = zone,
        confidence = conf,
        recommendedAction = action,
        hypotheses = if (family == "bearing_wear") listOf(linkedMapOf("fault" to "unknown", "p" to conf)) else hyps,
        evidence = listOf("1x=${"%.4f".format(amp1)}", "2x=${"%.4f".format(amp2)}", "harmonics=${harmonics.toInt()}"),
        nextTest = "none",
        inferenceSource = "physics_fallback",
    )
}

fun faultFamily(detail: String, kurtosis: Double = 0.0): String = when (detail) {
    "static_imbalance", "couple_imbalance", "imbalance" -> "imbalance"
    "angular_misalignment", "parallel_misalignment", "misalignment", "bent_shaft" -> "misalignment"
    "structural_looseness", "rotating_looseness", "looseness" -> "looseness"
    "bearing_wear" -> "bearing_wear"
    "healthy" -> "healthy"
    else -> if (kurtosis >= 5.0) "bearing_wear" else "unknown"
}

fun plainFault(family: String): String = when (family) {
    "imbalance" -> "imbalance"
    "misalignment" -> "misalignment"
    "looseness" -> "looseness"
    "bearing_wear" -> "possible bearing wear (screening only — phones cannot see high-frequency bearing tones)"
    "healthy" -> "no strong fault signature"
    else -> "unclear"
}

fun recommendedAction(family: String, zone: String): String {
    val urgency = when (zone) {
        "D" -> "Stop and inspect before the next run. "
        "C" -> "Plan maintenance at the next outage. "
        "B" -> "Keep running and trend the next reading. "
        else -> "Keep in service. "
    }
    val specific = when (family) {
        "imbalance" -> "Check for missing mass, buildup, or a lost balance weight."
        "misalignment" -> "Check coupling gap, offset, and soft foot."
        "looseness" -> "Check hold-down bolts, base grout, and bearing fit."
        "bearing_wear" -> "Kurtosis is high — confirm with a proper bearing measurement."
        "healthy" -> "Use this as the baseline next time."
        else -> "Repeat with phones on both bearings; relative phase separates these faults."
    }
    return urgency + specific
}

fun hypothesisFromModel(parsed: Map<String, Any?>, features: Map<String, Any?>): Map<String, Any?> {
    val zone = features["severity_zone"] as? String ?: "A"
    val hyps = parsed["hypotheses"]
    val out = LinkedHashMap<String, Any?>()
    if (hyps is List<*> && hyps.isNotEmpty()) {
        out["hypotheses"] = hyps.map { h ->
            val m = h as? Map<*, *> ?: return@map h
            val copy = LinkedHashMap<String, Any?>()
            m.forEach { (k, v) -> copy[k.toString()] = v }
            val f = copy["fault"] as? String
            if (f != null && f !in KNOWN_FAULT_DETAILS) copy["fault"] = mapFamilyToDetail(f, passUnknown = true)
            copy
        }
    } else {
        val detail = (parsed["fault_detail"] as? String) ?: mapFamilyToDetail(parsed["fault"] as? String)
        val p = (parsed["confidence"] as? Number)?.toDouble() ?: 0.5
        out["hypotheses"] = listOf(linkedMapOf("fault" to detail, "p" to p))
    }
    out["severity_zone"] = (parsed["severity_zone"] as? String) ?: zone
    out["evidence"] = (parsed["evidence"] as? List<*>) ?: emptyList<String>()
    out["next_test"] = (parsed["next_test"] as? String) ?: "none"
    return out
}

private fun mapFamilyToDetail(family: String?, passUnknown: Boolean = false): String = when (family) {
    "imbalance" -> "static_imbalance"
    "misalignment" -> "parallel_misalignment"
    "looseness" -> "structural_looseness"
    "bearing_wear" -> "unknown"
    "healthy" -> "healthy"
    // Unrecognized names reach the gate as-is so it can reject them explicitly.
    else -> if (passUnknown && family != null) family else "unknown"
}

data class GateSettings(
    val coherenceMin: Double = 0.8,
    val misalignmentMin2xRatio: Double = 0.5,
    val ambiguityDeltaP: Double = 0.2,
    val escalateZones: Set<String> = setOf("C", "D"),
)

/**
 * Turn a model reply (or a null reply) into the diagnosis shown to the user.
 * The physics gate always overwrites the severity zone with the DSP value.
 */
fun finalizeDiagnosis(
    modelText: String?,
    features: Map<String, Any?>,
    inferenceSource: String,
    settings: GateSettings = GateSettings(),
): Pair<Diagnosis, GateResult> {
    val parsed = modelText?.let { extractJsonObject(it) }?.let {
        runCatching { Json.obj(Json.parse(it)) }.getOrNull()
    }
    val fallback = physicsDiagnosis(features)
    val hypothesis = if (parsed != null) hypothesisFromModel(parsed, features) else {
        linkedMapOf(
            "hypotheses" to fallback.hypotheses,
            "severity_zone" to fallback.severityZone,
            "evidence" to fallback.evidence,
            "next_test" to fallback.nextTest,
        )
    }
    val gate = physicsGate(
        hypothesis,
        features,
        coherenceMin = settings.coherenceMin,
        misalignmentMin2xRatio = settings.misalignmentMin2xRatio,
    )
    val corrected = gate.correctedHypothesis
    @Suppress("UNCHECKED_CAST")
    val hyps = (corrected["hypotheses"] as? List<Map<String, Any?>>).orEmpty()
    val top = hyps.maxByOrNull { (it["p"] as? Number)?.toDouble() ?: 0.0 }
    val detail = top?.get("fault") as? String ?: fallback.faultDetail
    val kurt = (features["kurtosis"] as? Number)?.toDouble() ?: 0.0
    val family = if (parsed == null) fallback.fault else faultFamily(detail, kurt)
    val zone = corrected["severity_zone"] as? String ?: fallback.severityZone
    // The model's summary, action and confidence describe ITS top fault. If the gate
    // rejected that fault, showing them next to a different fault contradicts itself.
    @Suppress("UNCHECKED_CAST")
    val modelHyps = (hypothesis["hypotheses"] as? List<Map<String, Any?>>).orEmpty()
    val modelTop = modelHyps.maxByOrNull { (it["p"] as? Number)?.toDouble() ?: 0.0 }?.get("fault") as? String
    val overruled = parsed != null && (top == null || modelTop == null ||
        faultFamily(modelTop, kurt) != family)
    val summary = (parsed?.get("summary") as? String)?.takeIf { it.isNotBlank() && !overruled } ?: fallback.summary
    val action = (parsed?.get("recommended_action") as? String)?.takeIf { it.isNotBlank() && !overruled }
        ?: recommendedAction(family, zone)
    val confidence = if (top == null) fallback.confidence else (top["p"] as? Number)?.toDouble()
        ?: (parsed?.get("confidence") as? Number)?.toDouble()
        ?: fallback.confidence
    val evidence = ((parsed?.get("evidence") as? List<*>)?.map { it.toString() } ?: fallback.evidence)
    val diagnosis = Diagnosis(
        fault = family,
        faultDetail = detail,
        summary = summary,
        severityZone = zone,
        confidence = confidence.coerceIn(0.0, 1.0),
        recommendedAction = action,
        hypotheses = hyps.ifEmpty { fallback.hypotheses },
        evidence = evidence,
        nextTest = corrected["next_test"] as? String ?: "none",
        inferenceSource = if (parsed == null) "physics_fallback" else inferenceSource,
    )
    return diagnosis to gate
}

fun needsCloudRefinement(diagnosis: Diagnosis, gate: GateResult, settings: GateSettings): Boolean {
    if (diagnosis.inferenceSource == "physics_fallback") return false
    if (gate.escalateToCloud) return true
    if (diagnosis.severityZone in settings.escalateZones) return true
    val ps = diagnosis.hypotheses.map { (it["p"] as? Number)?.toDouble() ?: 0.0 }.sortedDescending()
    if (ps.size >= 2 && (ps[0] - ps[1]) < settings.ambiguityDeltaP) return true
    return false
}

/** Pull the first JSON object out of a model reply, ignoring markdown fences. */
fun extractJsonObject(text: String): String? {
    val cleaned = text.replace("```json", "```").replace("```", "")
    val start = cleaned.indexOf('{')
    if (start < 0) return null
    var depth = 0
    var inStr = false
    var esc = false
    for (i in start until cleaned.length) {
        val c = cleaned[i]
        if (inStr) {
            if (esc) esc = false
            else if (c == '\\') esc = true
            else if (c == '"') inStr = false
            continue
        }
        when (c) {
            '"' -> inStr = true
            '{' -> depth++
            '}' -> {
                depth--
                if (depth == 0) return cleaned.substring(start, i + 1)
            }
        }
    }
    return null
}
