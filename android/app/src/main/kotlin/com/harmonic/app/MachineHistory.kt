package com.harmonic.app

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt

/** One saved check of a machine. [shakeMmS] is overall velocity RMS (mm/s), the ISO severity measure. */
data class Reading(
    val at: Long,
    val zone: String,
    val fault: String,
    val shakeMmS: Double?,
    val confidence: Double,
    val source: String,
)

data class Machine(
    val id: String,
    val name: String,
    val type: String,
    val isoClass: String,
    val rpmText: String,
    val readings: List<Reading>,
) {
    val last: Reading? get() = readings.lastOrNull()
}

/**
 * Machines and their past checks, kept in a JSON file on the phone so history works with
 * no signal and survives the anonymous Firebase account changing after a reinstall.
 */
class MachineStore(context: Context) {
    private val file = File(context.filesDir, "machines.json")

    fun load(): List<Machine> = runCatching {
        if (!file.exists()) return emptyList()
        val arr = JSONArray(file.readText())
        List(arr.length()) { arr.getJSONObject(it).toMachine() }
    }.getOrElse {
        Log.w("Harmonic", "Could not read machine history", it)
        emptyList()
    }

    fun save(machines: List<Machine>) {
        val arr = JSONArray()
        machines.forEach { arr.put(it.toJson()) }
        // Write then rename so a crash mid-write never leaves a half file.
        val tmp = File(file.parentFile, "machines.json.tmp")
        tmp.writeText(arr.toString())
        if (!tmp.renameTo(file)) {
            file.writeText(arr.toString())
            tmp.delete()
        }
    }

    companion object {
        fun newId(): String = UUID.randomUUID().toString()
    }
}

/** How this check compares with the machine's previous one, in plain words. */
data class Comparison(val text: String, val worse: Boolean)

fun compareReadings(now: Reading, before: Reading?): Comparison? {
    before ?: return null
    val when_ = shortDate(before.at)
    val a = now.shakeMmS
    val b = before.shakeMmS
    if (a != null && b != null && b > 0.05) {
        val change = (a - b) / b
        return when {
            abs(change) < 0.15 -> Comparison("About the same shaking as last time ($when_).", false)
            change >= 1.0 -> Comparison("Shaking is ${(a / b).roundToInt()} times what it was last time ($when_).", true)
            change > 0 -> Comparison("${(change * 100).roundToInt()}% more shaking than last time ($when_).", true)
            else -> Comparison("${(-change * 100).roundToInt()}% less shaking than last time ($when_).", false)
        }
    }
    val rank = "ABCD"
    val d = rank.indexOf(now.zone) - rank.indexOf(before.zone)
    return when {
        d > 0 -> Comparison("Worse than last time ($when_).", true)
        d < 0 -> Comparison("Better than last time ($when_).", false)
        else -> Comparison("Same as last time ($when_).", false)
    }
}

fun shortDate(ms: Long): String {
    val days = ((System.currentTimeMillis() - ms) / 86_400_000L).toInt()
    return when {
        days <= 0 -> "today"
        days == 1 -> "yesterday"
        days < 7 -> "$days days ago"
        else -> java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault()).format(java.util.Date(ms))
    }
}

private fun Machine.toJson() = JSONObject().apply {
    put("id", id)
    put("name", name)
    put("type", type)
    put("isoClass", isoClass)
    put("rpmText", rpmText)
    put("readings", JSONArray().apply { readings.forEach { put(it.toJson()) } })
}

private fun Reading.toJson() = JSONObject().apply {
    put("at", at)
    put("zone", zone)
    put("fault", fault)
    if (shakeMmS != null) put("shakeMmS", shakeMmS)
    put("confidence", confidence)
    put("source", source)
}

private fun JSONObject.toMachine(): Machine {
    val r = optJSONArray("readings") ?: JSONArray()
    return Machine(
        id = getString("id"),
        name = getString("name"),
        type = optString("type", "motor"),
        isoClass = optString("isoClass", "class_I"),
        rpmText = optString("rpmText", ""),
        readings = List(r.length()) { r.getJSONObject(it).toReading() },
    )
}

private fun JSONObject.toReading() = Reading(
    at = getLong("at"),
    zone = optString("zone", "A"),
    fault = optString("fault", "unknown"),
    shakeMmS = if (has("shakeMmS")) getDouble("shakeMmS") else null,
    confidence = optDouble("confidence", 0.0),
    source = optString("source", "physics_fallback"),
)
