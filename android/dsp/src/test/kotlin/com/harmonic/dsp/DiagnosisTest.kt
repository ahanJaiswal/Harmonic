package com.harmonic.dsp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagnosisTest {
    @Test
    fun extractsJsonFromFencedReply() {
        val raw = "Sure.\n```json\n{\"fault\":\"imbalance\",\"summary\":\"Mass is off.\"}\n```"
        val json = checkNotNull(extractJsonObject(raw))
        val obj = Json.obj(Json.parse(json))
        assertEquals("imbalance", obj["fault"])
    }

    @Test
    fun physicsFallbackLabelsImbalance() {
        val features = mapOf<String, Any?>(
            "orders" to mapOf(
                "1x" to 2.4, "2x" to 0.2, "0.5x" to 0.01,
                "harmonics_count_above_floor" to 1,
            ),
            "severity_zone" to "B",
            "axial_to_radial_ratio" to 0.1,
            "kurtosis" to 3.0,
        )
        val (diagnosis, gate) = finalizeDiagnosis(null, features, "on_device")
        assertEquals("physics_fallback", diagnosis.inferenceSource)
        assertEquals("imbalance", diagnosis.fault)
        assertEquals("B", diagnosis.severityZone)
        assertFalse(needsCloudRefinement(diagnosis, gate, GateSettings()))
    }

    @Test
    fun modelZoneIsOverwrittenByDsp() {
        val features = mapOf<String, Any?>(
            "orders" to mapOf("1x" to 2.0, "2x" to 0.2, "0.5x" to 0.0, "harmonics_count_above_floor" to 1),
            "severity_zone" to "C",
            "axial_to_radial_ratio" to 0.1,
        )
        val reply = """{"summary":"Looks unbalanced.","fault":"imbalance","fault_detail":"static_imbalance","severity_zone":"A","confidence":0.8,"recommended_action":"Balance the rotor.","hypotheses":[{"fault":"static_imbalance","p":0.8}],"evidence":["1x"],"next_test":"none"}"""
        val (diagnosis, gate) = finalizeDiagnosis(reply, features, "on_device")
        assertEquals("C", diagnosis.severityZone)
        assertEquals("on_device", diagnosis.inferenceSource)
        assertTrue(gate.actions.any { it.action == "OVERWRITE_ZONE" })
        assertTrue(needsCloudRefinement(diagnosis, gate, GateSettings()))
    }
}
