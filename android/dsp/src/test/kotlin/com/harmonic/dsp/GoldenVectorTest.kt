package com.harmonic.dsp

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GoldenVectorTest {
    private val doc = Json.obj(
        Json.parse(javaClass.getResource("/golden/vectors.json")!!.readText())
    )

    @Test
    fun goldenVectorsMatchPython() {
        val cases = Json.list(doc["cases"])
        assertTrue(cases.size >= 10, "expected a full golden set, got ${cases.size}")
        for (raw in cases) {
            val c = Json.obj(raw)
            when (Json.str(c["kind"])) {
                "clock" -> checkClock(c)
                "butter" -> checkButter(c)
                "frames" -> checkFrames(c)
                "resample" -> checkResample(c)
                "welch" -> checkWelch(c)
                "orders" -> checkOrders(c)
                "velocity" -> checkVelocity(c)
                "phase" -> checkPhase(c)
                "severity" -> checkSeverity(c)
                "features" -> checkFeatures(c)
                "gate" -> checkGate(c)
                "sync" -> checkSync(c)
                else -> error("unknown kind ${c["kind"]}")
            }
        }
    }

    private fun checkClock(c: Map<String, Any?>) {
        val ts = doubles(c["ts"])
        val got = correctClock(ts, Json.num(c["offset_s"]), Json.num(c["drift_ppm"]), Json.num(c["t_ref"]))
        close("clock", got, doubles(c["expected"]), absTol = 1e-9)
    }

    private fun checkButter(c: Map<String, Any?>) {
        val (b, a) = SignalMath.butterHighpass(4, Json.num(c["cutoff"]), Json.num(c["fs"]))
        close("butter.b", b, doubles(c["b"]), absTol = 1e-8)
        close("butter.a", a, doubles(c["a"]), absTol = 1e-8)
    }

    private fun checkFrames(c: Map<String, Any?>) {
        val accel = matrix(c["accel"])
        val g = estimateGravity(accel)
        close("gravity", g, doubles(c["g"]), absTol = 1e-9)
        val proj = projectAxes(accel, g)
        close("V", proj.v, doubles(c["V"]), absTol = 1e-8)
        close("A", proj.a, doubles(c["A"]), absTol = 1e-8)
        close("H", proj.h, doubles(c["H"]), absTol = 1e-8)
        close("dc", removeDc(proj.h), doubles(c["dc_removed"]), absTol = 1e-8)
    }

    private fun checkResample(c: Map<String, Any?>) {
        val out = resampleUniform(doubles(c["signal"]), doubles(c["timestamps"]), Json.num(c["fs_target"]))
        close("resample", out.signal, doubles(c["signal_out"]), absTol = 1e-6)
        close("resample.ts", out.timestamps, doubles(c["ts_out"]), absTol = 1e-9)
        assertClose("fs", out.fsOut, Json.num(c["fs_out"]), absTol = 1e-9)
    }

    private fun checkWelch(c: Map<String, Any?>) {
        val (freqs, amp) = welchAmplitude(doubles(c["signal"]), Json.num(c["fs"]), (c["nperseg"] as Number).toInt())
        close("welch.f", freqs, doubles(c["freqs"]), absTol = 1e-9)
        close("welch.a", amp, doubles(c["amp"]), absTol = 1e-6, relTol = 1e-5)
    }

    private fun checkOrders(c: Map<String, Any?>) {
        val freqs = doubles(c["freqs"])
        val amp = doubles(c["amp"])
        val speed = findRunningSpeed(freqs, amp, Json.num(c["rpm_nameplate"]))
        assertClose("f_run", speed.fRunHz, Json.num(c["f_run_hz"]), absTol = 1e-6)
        assertClose("amp1x", speed.amp1x, Json.num(c["amp_1x"]), absTol = 1e-6, relTol = 1e-5)
        assertClose("conf", speed.confidence, Json.num(c["confidence"]), absTol = 1e-6)
        val orders = extractOrders(freqs, amp, speed.fRunHz)
        val expected = Json.obj(c["orders"])
        for ((k, v) in expected) {
            assertClose("order $k", orders[k] ?: error("missing $k"), Json.num(v), absTol = 1e-5, relTol = 1e-4)
        }
    }

    private fun checkVelocity(c: Map<String, Any?>) {
        val freqs = doubles(c["freqs"])
        val amp = doubles(c["amp"])
        assertClose("overall", overallVelocityRms(freqs, amp), Json.num(c["overall"]), absTol = 1e-6, relTol = 1e-5)
        assertClose("at", velocityAtOrder(freqs, amp, Json.num(c["f_order"])), Json.num(c["at_order"]), absTol = 1e-6, relTol = 1e-5)
    }

    private fun checkPhase(c: Map<String, Any?>) {
        val result = crossPhaseCoherence(
            doubles(c["a"]), doubles(c["b"]), Json.num(c["fs"]), Json.num(c["f_target"]),
            nperseg = (c["nperseg"] as Number).toInt(),
        )
        assertEquals(c["trusted"] as Boolean, result.trusted, "trusted")
        assertClose("coh", result.coherence, Json.num(c["coherence"]), absTol = 1e-5)
        val expectedPhase = c["phase_deg"]
        if (expectedPhase == null) {
            assertEquals(null, result.phaseDeg)
        } else {
            assertClose("phase", result.phaseDeg ?: error("phase null"), Json.num(expectedPhase), absTol = 1e-3)
        }
    }

    private fun checkSeverity(c: Map<String, Any?>) {
        for (row in Json.list(c["rows"])) {
            val r = Json.obj(row)
            val got = classifyZone(Json.num(r["v"]), Json.str(r["class"]))
            assertEquals(Json.str(r["zone"]), got, "zone v=${r["v"]} class=${r["class"]}")
        }
    }

    private fun checkFeatures(c: Map<String, Any?>) {
        val expected = Json.obj(c["expected"])
        val machine = Json.obj(expected["machine"])
        val sampling = Json.obj(expected["sampling"])
        val input = FeatureInput(
            machineType = Json.str(machine["type"]),
            rpmNameplate = Json.num(machine["rpm_nameplate"]),
            blades = (machine["blades"] as Number).toInt(),
            mounting = Json.str(machine["mounting"]),
            isoClass = Json.str(machine["iso_class"]),
            fsHz = Json.list(sampling["fs_hz"]).map { Json.num(it) },
            durationS = Json.num(sampling["duration_s"]),
            syncConfidence = Json.num(sampling["sync_conf"]),
            bandLimitHz = Json.num(sampling["band_limit_hz"]),
            runningSpeedHz = Json.num(expected["running_speed_hz"]),
            velocityMmS = mapOf(
                "A_H" to 1.23456,
                "A_V" to 0.987,
                "A_A" to null,
                "B_H" to 1.1,
            ),
            severityZone = Json.str(expected["severity_zone"]),
            orders = mapOf("1x" to 2.51234, "2x" to 0.40119, "0.5x" to 0.05, "harmonics_count_above_floor" to 2.0),
            axialToRadialRatio = 0.22222,
            phaseDeg = mapOf("AB_H_1x" to 12.345, "AB_V_1x" to null),
            coherence = mapOf("AB_H_1x" to 0.91234),
            crestFactor = 1.41421,
            kurtosis = 3.14159,
            baselineDelta = mapOf("1x" to 12.345, "2x" to null),
        )
        val got = buildFeatureVector(input)
        assertTreeClose("features", expected, got)
    }

    private fun checkGate(c: Map<String, Any?>) {
        val result = physicsGate(Json.obj(c["hypothesis"]), Json.obj(c["features"]))
        assertEquals(c["accepted"] as Boolean, result.accepted, "accepted ${c["actions"]}")
        assertEquals(c["escalate"] as Boolean, result.escalateToCloud)
        assertEquals(Json.list(c["actions"]).map { it as String }, result.actions.map { it.action })
        assertEquals(Json.str(c["severity_zone"]), result.correctedHypothesis["severity_zone"])
        assertEquals(Json.str(c["next_test"]), result.correctedHypothesis["next_test"])
        @Suppress("UNCHECKED_CAST")
        val hyps = result.correctedHypothesis["hypotheses"] as List<Map<String, Any?>>
        val expectedP = Json.list(c["p"])
        assertEquals(expectedP.size, hyps.size)
        for (i in expectedP.indices) {
            assertClose("p$i", (hyps[i]["p"] as Number).toDouble(), Json.num(expectedP[i]), absTol = 1e-9)
        }
    }

    private fun checkSync(c: Map<String, Any?>) {
        val w1 = Json.list(c["w1"])
        val w2 = Json.list(c["w2"])
        val result = estimateOffset(
            matrix(c["accel_a"]), doubles(c["ts_a"]),
            matrix(c["accel_b"]), doubles(c["ts_b"]),
            Json.num(w1[0]) to Json.num(w1[1]),
            Json.num(w2[0]) to Json.num(w2[1]),
        )
        assertClose("offset", result.offsetS, Json.num(c["offset_s"]), absTol = 1e-4)
        assertClose("drift", result.driftPpm, Json.num(c["drift_ppm"]), absTol = 1.0)
        assertClose("snr", result.snrTap1, Json.num(c["snr_tap1"]), relTol = 1e-3, absTol = 1e-3)
    }

    private fun assertTreeClose(label: String, expected: Any?, got: Any?) {
        when (expected) {
            is Map<*, *> -> {
                val g = got as Map<*, *>
                assertEquals(expected.keys, g.keys, "$label keys")
                for (k in expected.keys) assertTreeClose("$label.$k", expected[k], g[k])
            }
            is List<*> -> {
                val g = got as List<*>
                assertEquals(expected.size, g.size, "$label size")
                for (i in expected.indices) assertTreeClose("$label[$i]", expected[i], g[i])
            }
            is Number -> assertClose(label, (got as Number).toDouble(), expected.toDouble(), absTol = 1e-9, relTol = 1e-9)
            else -> assertEquals(expected, got, label)
        }
    }

    private fun doubles(value: Any?): DoubleArray =
        Json.list(value).map { Json.num(it) }.toDoubleArray()

    private fun matrix(value: Any?): Array<DoubleArray> =
        Json.list(value).map { doubles(it) }.toTypedArray()

    private fun close(label: String, got: DoubleArray, expected: DoubleArray, absTol: Double, relTol: Double = 0.0) {
        assertEquals(expected.size, got.size, "$label length")
        for (i in got.indices) assertClose("$label[$i]", got[i], expected[i], absTol, relTol)
    }

    private fun assertClose(label: String, got: Double, expected: Double, absTol: Double, relTol: Double = 0.0) {
        val scale = maxOf(1.0, abs(expected))
        val tol = maxOf(absTol, relTol * scale)
        assertTrue(abs(got - expected) <= tol, "$label got $got expected $expected tol $tol")
    }
}
