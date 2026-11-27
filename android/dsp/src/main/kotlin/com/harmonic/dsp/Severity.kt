package com.harmonic.dsp

data class ZoneThresholds(val a: Double, val b: Double, val c: Double)

val ZONE_THRESHOLDS: Map<String, ZoneThresholds> = mapOf(
    "class_I" to ZoneThresholds(0.71, 1.8, 4.5),
    "group2_rigid" to ZoneThresholds(1.4, 2.8, 4.5),
    "group2_flexible" to ZoneThresholds(2.3, 4.5, 7.1),
)

fun classifyZone(
    velocityRmsMmS: Double,
    machineClass: String = "class_I",
    thresholds: Map<String, ZoneThresholds>? = null,
): String {
    require(velocityRmsMmS.isFinite() && velocityRmsMmS >= 0.0) {
        "Velocity RMS must be a finite, non-negative number (got $velocityRmsMmS)"
    }
    val table = thresholds ?: ZONE_THRESHOLDS
    val t = table[machineClass] ?: ZONE_THRESHOLDS.getValue("class_I")
    return when {
        velocityRmsMmS <= t.a -> "A"
        velocityRmsMmS <= t.b -> "B"
        velocityRmsMmS <= t.c -> "C"
        else -> "D"
    }
}

fun zoneDescription(zone: String): String = when (zone) {
    "A" -> "Good — newly commissioned or well-maintained"
    "B" -> "Acceptable for long-term continuous operation"
    "C" -> "Unsatisfactory — plan maintenance action"
    "D" -> "Damage likely — take immediate action"
    else -> "Unknown zone"
}

fun parseRemoteConfigThresholds(json: String): Map<String, ZoneThresholds> {
    val out = LinkedHashMap<String, ZoneThresholds>()
    val body = json.trim().removePrefix("{").removeSuffix("}")
    val classes = Regex("\"([^\"]+)\"\\s*:\\s*\\{([^}]*)}").findAll(body)
    for (match in classes) {
        val name = match.groupValues[1]
        val fields = match.groupValues[2]
        fun num(key: String): Double {
            val m = Regex("\"$key\"\\s*:\\s*([-+0-9.eE]+)").find(fields)
                ?: throw IllegalArgumentException("Missing $key in $name")
            return m.groupValues[1].toDouble()
        }
        out[name] = ZoneThresholds(num("A"), num("B"), num("C"))
    }
    if (out.isEmpty()) throw IllegalArgumentException("No thresholds in remote config JSON")
    return out
}
