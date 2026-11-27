package com.harmonic.dsp

data class FeatureInput(
    val machineType: String,
    val rpmNameplate: Double,
    val blades: Int?,
    val mounting: String,
    val isoClass: String,
    val fsHz: List<Double>,
    val durationS: Double,
    val syncConfidence: Double,
    val bandLimitHz: Double,
    val runningSpeedHz: Double,
    val velocityMmS: Map<String, Double?>,
    val severityZone: String,
    val orders: Map<String, Double>,
    val axialToRadialRatio: Double?,
    val phaseDeg: Map<String, Double?>,
    val coherence: Map<String, Double?>,
    val crestFactor: Double? = null,
    val kurtosis: Double? = null,
    val phaseStabilityDeg: Double? = null,
    val baselineDelta: Map<String, Double?>? = null,
)

fun buildFeatureVector(input: FeatureInput): LinkedHashMap<String, Any?> {
    val features = LinkedHashMap<String, Any?>()
    val machine = LinkedHashMap<String, Any?>()
    machine["type"] = input.machineType
    machine["rpm_nameplate"] = SignalMath.roundPy(input.rpmNameplate, 1)
    if (input.blades != null) machine["blades"] = input.blades
    machine["mounting"] = input.mounting
    machine["iso_class"] = input.isoClass
    features["machine"] = machine

    val sampling = LinkedHashMap<String, Any?>()
    sampling["fs_hz"] = input.fsHz.map { SignalMath.roundPy(it, 1) }
    sampling["duration_s"] = SignalMath.roundPy(input.durationS, 1)
    sampling["sync_conf"] = SignalMath.roundPy(input.syncConfidence, 3)
    sampling["band_limit_hz"] = SignalMath.roundPy(input.bandLimitHz, 1)
    features["sampling"] = sampling

    features["running_speed_hz"] = SignalMath.roundPy(input.runningSpeedHz, 3)
    val vel = LinkedHashMap<String, Any?>()
    for ((k, v) in input.velocityMmS) {
        if (v != null) vel[k] = SignalMath.roundPy(v, 2)
    }
    features["overall_velocity_mm_s"] = vel
    features["severity_zone"] = input.severityZone

    val orders = LinkedHashMap<String, Any?>()
    for ((k, v) in input.orders) {
        if (k != "harmonics_count_above_floor") orders[k] = SignalMath.roundPy(v, 4)
    }
    orders["harmonics_count_above_floor"] = (input.orders["harmonics_count_above_floor"] ?: 0.0).toInt()
    features["orders"] = orders

    if (input.axialToRadialRatio != null) {
        features["axial_to_radial_ratio"] = SignalMath.roundPy(input.axialToRadialRatio, 3)
    }
    val phase = LinkedHashMap<String, Any?>()
    for ((k, v) in input.phaseDeg) if (v != null) phase[k] = SignalMath.roundPy(v, 1)
    if (phase.isNotEmpty()) features["phase_deg"] = phase
    val coh = LinkedHashMap<String, Any?>()
    for ((k, v) in input.coherence) if (v != null) coh[k] = SignalMath.roundPy(v, 3)
    if (coh.isNotEmpty()) features["coherence"] = coh
    if (input.crestFactor != null) features["crest_factor"] = SignalMath.roundPy(input.crestFactor, 2)
    if (input.kurtosis != null) features["kurtosis"] = SignalMath.roundPy(input.kurtosis, 2)
    if (input.phaseStabilityDeg != null) {
        features["phase_stability_deg"] = SignalMath.roundPy(input.phaseStabilityDeg, 1)
    }
    if (input.baselineDelta != null) {
        val bd = LinkedHashMap<String, Any?>()
        for ((k, v) in input.baselineDelta) if (v != null) bd[k] = SignalMath.roundPy(v, 1)
        if (bd.isNotEmpty()) features["baseline_delta"] = bd
    }
    return features
}

fun featuresToJson(features: Map<String, Any?>, pretty: Boolean = false): String =
    Json.write(features, pretty)

fun estimateTokenCount(featuresJson: String): Int = maxOf(1, featuresJson.length / 4)
