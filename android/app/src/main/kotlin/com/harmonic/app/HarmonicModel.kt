package com.harmonic.app

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.Firebase
import com.google.firebase.remoteconfig.remoteConfig
import com.google.firebase.remoteconfig.remoteConfigSettings
import com.harmonic.dsp.ClockOffset
import com.harmonic.dsp.Diagnosis
import com.harmonic.dsp.FeatureInput
import com.harmonic.dsp.GateResult
import com.harmonic.dsp.GateSettings
import com.harmonic.dsp.PhoneAnalysis
import com.harmonic.dsp.buildFeatureVector
import com.harmonic.dsp.correctClock
import com.harmonic.dsp.crossPhaseCoherence
import com.harmonic.dsp.estimateOffset
import com.harmonic.dsp.parseRemoteConfigThresholds
import com.harmonic.dsp.resampleToCommonGrid
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONArray

class HarmonicModel(app: Application) : AndroidViewModel(app) {
    private val recorder = AccelRecorder(app)
    private val sessions = SessionRepository()
    private val diagnoser = HybridDiagnoser(loadPrompts(app))

    var config by mutableStateOf(HarmonicConfig())
        private set
    var screen by mutableStateOf(Screen.Home)
        private set
    var status by mutableStateOf(if (FirebaseGate.ready) "Signed out" else "Firebase not configured — DSP still runs")
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var code by mutableStateOf("")
        private set
    var session by mutableStateOf<LiveSession?>(null)
        private set
    var uid by mutableStateOf<String?>(null)
        private set
    var machineType by mutableStateOf("fan")
    var rpmText by mutableStateOf("1200")
    var isoClass by mutableStateOf("class_I")
    var seconds by mutableStateOf(12)
    var elapsed by mutableStateOf(0f)
        private set
    var magnitude by mutableStateOf(0f)
        private set
    var result by mutableStateOf<ShownResult?>(null)
        private set
    var busy by mutableStateOf(false)
        private set

    private var listenJob: Job? = null
    private val shown = HashSet<String>()

    val sensorOk: Boolean get() = recorder.available
    val isOwner: Boolean get() = uid != null && uid == session?.ownerUid

    init {
        if (FirebaseGate.ready) {
            viewModelScope.launch {
                runCatching { refreshRemoteConfig() }
                runCatching { uid = ensureSignedIn(); status = "Signed in" }
                    .onFailure { status = "Auth failed: ${it.message}" }
            }
        }
    }

    fun host() = launchAction {
        val me = uid ?: ensureSignedIn() ?: error("Sign-in required")
        uid = me
        val c = joinCode()
        code = c
        val id = sessions.host(me, c, seconds)
        watch(id)
        screen = Screen.Lobby
    }

    fun join(raw: String) = launchAction {
        val me = uid ?: ensureSignedIn() ?: error("Sign-in required")
        uid = me
        val id = sessions.join(me, raw.trim().uppercase())
        code = raw.trim().uppercase()
        watch(id)
        screen = Screen.Lobby
    }

    fun saveMachine() = launchAction {
        val id = session?.id ?: return@launchAction
        if (!isOwner) return@launchAction
        sessions.updateMachine(id, machineType, rpmText.toDoubleOrNull() ?: 1200.0, isoClass, seconds)
    }

    fun startSessionRecording() = launchAction {
        val id = session?.id ?: return@launchAction
        sessions.markRecording(id, seconds)
    }

    fun recordThisPhone() {
        viewModelScope.launch {
            error = null
            screen = Screen.Record
            val analysis = capture()
            busy = true
            status = "Analyzing on device"
            val features = featuresFor(analysis, null, emptyMap(), emptyMap(), 1.0)
            val (dx, gate) = diagnoser.diagnose(features, config)
            result = ShownResult(dx, gate, features, listOf("android"))
            busy = false
            screen = Screen.Result
        }
    }

    fun home() {
        screen = Screen.Home
        error = null
    }

    fun openJoin() {
        screen = Screen.Join
        error = null
    }

    private fun watch(id: String) {
        listenJob?.cancel()
        listenJob = viewModelScope.launch {
            sessions.listen(id).collect { live ->
                session = live
                if (live.status == "recording" && live.id !in shown) {
                    shown.add(live.id + ":rec")
                    captureAndUpload(live)
                }
                val diagnosis = live.diagnosis
                if (live.status == "done" && diagnosis != null && screen != Screen.Result) {
                    result = diagnosisFromMap(diagnosis)
                    screen = Screen.Result
                }
            }
        }
    }

    private suspend fun captureAndUpload(live: LiveSession) {
        screen = Screen.Record
        val analysis = capture()
        val me = uid ?: return
        val b64 = encodeChannels(analysis.uniform.v, analysis.uniform.a, analysis.uniform.h)
        sessions.uploadRecording(live.id, me, analysis, b64)
        if (me == live.ownerUid) {
            busy = true
            status = "Waiting for the other phones"
            repeat(40) {
                val rows = sessions.loadRecordings(live.id)
                if (rows.size >= live.members.size.coerceAtLeast(1)) {
                    publishCombined(live, rows)
                    return
                }
                delay(500)
            }
            publishCombined(live, sessions.loadRecordings(live.id))
        } else {
            status = "Uploaded. Waiting for the host's diagnosis."
            screen = Screen.Lobby
        }
    }

    private suspend fun publishCombined(live: LiveSession, rows: List<Map<String, Any?>>) {
        status = "Running DSP and the hybrid model"
        val analyses = rows.mapNotNull { rowToAnalysis(it) }
        val primary = analyses.firstOrNull() ?: return
        val secondary = analyses.getOrNull(1)
        var phase = emptyMap<String, Double?>()
        var coh = emptyMap<String, Double?>()
        var syncConf = if (secondary == null) 1.0 else 0.5
        if (secondary != null) {
            val cross = crossPhones(primary, secondary)
            phase = cross.phase
            coh = cross.coherence
            syncConf = cross.syncConf
        }
        val features = featuresFor(primary, secondary, phase, coh, syncConf, live.machineType, live.rpm, live.isoClass)
        val (dx, gate) = diagnoser.diagnose(features, config)
        val payload = mapOf(
            "fault" to dx.fault,
            "faultDetail" to dx.faultDetail,
            "summary" to dx.summary,
            "severityZone" to dx.severityZone,
            "confidence" to dx.confidence,
            "recommendedAction" to dx.recommendedAction,
            "inferenceSource" to dx.inferenceSource,
            "evidence" to dx.evidence,
            "nextTest" to dx.nextTest,
            "gateActions" to gate.actions.map { it.action },
            "platforms" to rows.map { it["platform"]?.toString() ?: "?" },
        )
        sessions.publishDiagnosis(live.id, payload)
        result = ShownResult(dx, gate, features, rows.map { it["platform"]?.toString() ?: "?" })
        busy = false
        screen = Screen.Result
    }

    private suspend fun capture(): PhoneAnalysis {
        elapsed = 0f
        recorder.start()
        val total = seconds
        val steps = total * 10
        repeat(steps) { i ->
            delay(100)
            elapsed = (i + 1) / 10f
            magnitude = recorder.lastMagnitude
        }
        val recording = recorder.stop()
        if (recording.raw.size < 32) error("Not enough samples (${recording.raw.size}). Is the accelerometer available?")
        return recording.analyze(
            rpmText.toDoubleOrNull() ?: session?.rpm ?: 1200.0,
            isoClass,
            config.thresholds,
        )
    }

    private fun featuresFor(
        primary: PhoneAnalysis,
        secondary: PhoneAnalysis?,
        phase: Map<String, Double?>,
        coherence: Map<String, Double?>,
        syncConf: Double,
        type: String = machineType,
        rpm: Double = rpmText.toDoubleOrNull() ?: 1200.0,
        iso: String = isoClass,
    ): Map<String, Any?> {
        val vel = linkedMapOf<String, Double?>(
            "A_H" to primary.velocityH,
            "A_V" to primary.velocityV,
            "A_A" to primary.velocityA,
        )
        if (secondary != null) {
            vel["B_H"] = secondary.velocityH
            vel["B_V"] = secondary.velocityV
            vel["B_A"] = secondary.velocityA
        }
        val fs = listOf(primary.fsHz) + (secondary?.let { listOf(it.fsHz) } ?: emptyList())
        return buildFeatureVector(
            FeatureInput(
                machineType = type,
                rpmNameplate = rpm,
                blades = null,
                mounting = if (iso.contains("flexible")) "flexible" else "rigid",
                isoClass = iso,
                fsHz = fs,
                durationS = primary.durationS,
                syncConfidence = syncConf,
                bandLimitHz = fs.min() / 2.0,
                runningSpeedHz = primary.runningSpeedHz,
                velocityMmS = vel,
                severityZone = primary.severityZone,
                orders = primary.orders,
                axialToRadialRatio = primary.axialToRadial,
                phaseDeg = phase,
                coherence = coherence,
                crestFactor = primary.crestFactor,
                kurtosis = primary.kurtosis,
            )
        )
    }

    private suspend fun refreshRemoteConfig() {
        val rc = Firebase.remoteConfig
        rc.setConfigSettingsAsync(remoteConfigSettings { minimumFetchIntervalInSeconds = 60 }).await()
        rc.setDefaultsAsync(
            mapOf(
                "model_cloud_refine" to "gemini-3.8-flash",
                "model_cloud_fallback" to "gemini-3.5-flash-lite",
                "prompt_version" to "v1",
                "record_seconds" to 12L,
                "coherence_min" to 0.8,
                "ambiguity_delta_p" to 0.2,
                "escalate_on_zone" to "[\"C\",\"D\"]",
                "max_on_device_calls_per_session" to 3L,
                "gate_misalignment_min_2x_ratio" to 0.5,
                "severity_thresholds" to "{\"class_I\":{\"A\":0.71,\"B\":1.8,\"C\":4.5},\"group2_rigid\":{\"A\":1.4,\"B\":2.8,\"C\":4.5},\"group2_flexible\":{\"A\":2.3,\"B\":4.5,\"C\":7.1}}",
            )
        ).await()
        rc.fetchAndActivate().await()
        val zones = runCatching { JSONArray(rc.getString("escalate_on_zone")) }.getOrNull()
        val escalate = if (zones == null) setOf("C", "D") else buildSet {
            for (i in 0 until zones.length()) add(zones.getString(i))
        }
        val thresholds = runCatching { parseRemoteConfigThresholds(rc.getString("severity_thresholds")) }.getOrNull()
        config = HarmonicConfig(
            cloudRefineModel = rc.getString("model_cloud_refine").ifBlank { "gemini-3.8-flash" },
            cloudFallbackModel = rc.getString("model_cloud_fallback").ifBlank { "gemini-3.5-flash-lite" },
            promptVersion = rc.getString("prompt_version").ifBlank { "v1" },
            recordSeconds = rc.getLong("record_seconds").toInt().coerceIn(8, 60),
            settings = GateSettings(
                coherenceMin = rc.getDouble("coherence_min"),
                misalignmentMin2xRatio = rc.getDouble("gate_misalignment_min_2x_ratio"),
                ambiguityDeltaP = rc.getDouble("ambiguity_delta_p"),
                escalateZones = escalate,
            ),
            maxOnDeviceCalls = rc.getLong("max_on_device_calls_per_session").toInt().coerceAtLeast(1),
            thresholds = thresholds,
        )
        seconds = config.recordSeconds
    }

    private fun launchAction(block: suspend () -> Unit) {
        viewModelScope.launch {
            busy = true
            error = null
            try {
                block()
            } catch (t: Throwable) {
                error = t.message ?: t.javaClass.simpleName
            } finally {
                busy = false
            }
        }
    }

    private fun loadPrompts(app: Application): Map<String, String> {
        val text = runCatching {
            app.assets.open("prompts/v1_first_pass.md").bufferedReader().use { it.readText() }
        }.getOrElse { HybridDiagnoser.DEFAULT_PROMPT }
        return mapOf("v1" to text)
    }
}

data class ShownResult(
    val diagnosis: Diagnosis,
    val gate: GateResult,
    val features: Map<String, Any?>,
    val platforms: List<String>,
)

enum class Screen { Home, Join, Lobby, Record, Result }

private data class Cross(val phase: Map<String, Double?>, val coherence: Map<String, Double?>, val syncConf: Double)

private fun crossPhones(a: PhoneAnalysis, b: PhoneAnalysis): Cross {
    val seriesA = listOf(a.uniform.v, a.uniform.a, a.uniform.h)
    val seriesB = listOf(b.uniform.v, b.uniform.a, b.uniform.h)
    val accelA = stack(seriesA)
    val accelB = stack(seriesB)
    val t0 = a.uniform.timestamps.first()
    val t1 = a.uniform.timestamps.last()
    val span = t1 - t0
    val w1 = t0 to (t0 + span * 0.25)
    val w2 = (t1 - span * 0.25) to t1
    val offset: ClockOffset = runCatching {
        estimateOffset(accelA, a.uniform.timestamps, accelB, b.uniform.timestamps, w1, w2, a.fsHz, b.fsHz)
    }.getOrElse { ClockOffset(0.0, 0.0, 1.0, 1.0, t0, t1) }
    val corrected = correctClock(b.uniform.timestamps, offset.offsetS, offset.driftPpm, offset.tTap1Ref)
    val commonV = resampleToCommonGrid(listOf(a.uniform.v, b.uniform.v), listOf(a.uniform.timestamps, corrected), minOf(a.fsHz, b.fsHz))
    val commonA = resampleToCommonGrid(listOf(a.uniform.a, b.uniform.a), listOf(a.uniform.timestamps, corrected), commonV.fsOut)
    val commonH = resampleToCommonGrid(listOf(a.uniform.h, b.uniform.h), listOf(a.uniform.timestamps, corrected), commonV.fsOut)
    val f = a.runningSpeedHz
    val h = crossPhaseCoherence(commonH.signals[0], commonH.signals[1], commonH.fsOut, f)
    val v = crossPhaseCoherence(commonV.signals[0], commonV.signals[1], commonV.fsOut, f)
    val ax = crossPhaseCoherence(commonA.signals[0], commonA.signals[1], commonA.fsOut, f)
    val hv = crossPhaseCoherence(commonH.signals[0], commonV.signals[0], commonH.fsOut, f)
    val phase = linkedMapOf(
        "AB_H_1x" to h.phaseDeg, "AB_V_1x" to v.phaseDeg, "AB_A_1x" to ax.phaseDeg, "A_HV_1x" to hv.phaseDeg,
    )
    val coh = linkedMapOf(
        "AB_H_1x" to h.coherence, "AB_V_1x" to v.coherence, "AB_A_1x" to ax.coherence, "A_HV_1x" to hv.coherence,
    )
    val snr = minOf(offset.snrTap1, offset.snrTap2)
    val syncConf = ((snr - 1.0) / 9.0).coerceIn(0.0, 1.0)
    return Cross(phase, coh, syncConf)
}

private fun stack(channels: List<DoubleArray>): Array<DoubleArray> {
    val n = channels.minOf { it.size }
    return Array(n) { i -> doubleArrayOf(channels[0][i], channels[1][i], channels[2][i]) }
}

private fun rowToAnalysis(row: Map<String, Any?>): PhoneAnalysis? {
    val n = (row["n"] as? Number)?.toInt() ?: return null
    val b64 = row["channelsB64"] as? String ?: return null
    val (v, a, h) = decodeChannels(b64, n)
    val fs = (row["fsHz"] as? Number)?.toDouble() ?: return null
    val t0 = (row["t0"] as? Number)?.toDouble() ?: 0.0
    val ts = DoubleArray(n) { i -> t0 + i / fs }
    val orders = (row["orders"] as? Map<*, *>)?.mapNotNull { (k, v) ->
        val num = (v as? Number)?.toDouble() ?: return@mapNotNull null
        k.toString() to num
    }?.toMap() ?: emptyMap()
    return PhoneAnalysis(
        fsHz = fs,
        durationS = if (n > 1) (n - 1) / fs else 0.0,
        runningSpeedHz = (row["runningSpeedHz"] as? Number)?.toDouble() ?: 0.0,
        rpmRun = ((row["runningSpeedHz"] as? Number)?.toDouble() ?: 0.0) * 60.0,
        speedConfidence = 0.0,
        orders = orders,
        velocityH = (row["velocityH"] as? Number)?.toDouble() ?: 0.0,
        velocityV = (row["velocityV"] as? Number)?.toDouble() ?: 0.0,
        velocityA = (row["velocityA"] as? Number)?.toDouble() ?: 0.0,
        severityZone = row["zone"]?.toString() ?: "A",
        dominantAxis = row["dominantAxis"]?.toString() ?: "H",
        axialToRadial = (row["axialToRadial"] as? Number)?.toDouble(),
        crestFactor = (row["crestFactor"] as? Number)?.toDouble() ?: 0.0,
        kurtosis = (row["kurtosis"] as? Number)?.toDouble() ?: 0.0,
        uniform = com.harmonic.dsp.AxisSeries(v, a, h, ts, fs),
        spectrumFreqs = doubleArrayOf(),
        spectrumAmp = doubleArrayOf(),
    )
}

private fun diagnosisFromMap(map: Map<String, Any?>): ShownResult {
    val dx = Diagnosis(
        fault = map["fault"]?.toString() ?: "unknown",
        faultDetail = map["faultDetail"]?.toString() ?: "unknown",
        summary = map["summary"]?.toString() ?: "",
        severityZone = map["severityZone"]?.toString() ?: "A",
        confidence = (map["confidence"] as? Number)?.toDouble() ?: 0.0,
        recommendedAction = map["recommendedAction"]?.toString() ?: "",
        hypotheses = emptyList(),
        evidence = (map["evidence"] as? List<*>)?.map { it.toString() } ?: emptyList(),
        nextTest = map["nextTest"]?.toString() ?: "none",
        inferenceSource = map["inferenceSource"]?.toString() ?: "cloud",
    )
    val actions = (map["gateActions"] as? List<*>)?.map {
        com.harmonic.dsp.GateAction(it.toString(), null, "")
    } ?: emptyList()
    val gate = GateResult(actions.isEmpty(), actions, emptyMap(), false)
    val platforms = (map["platforms"] as? List<*>)?.map { it.toString() } ?: emptyList()
    return ShownResult(dx, gate, emptyMap(), platforms)
}
