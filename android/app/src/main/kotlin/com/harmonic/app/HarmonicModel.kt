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
import android.os.Build
import android.util.Log
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONArray

class HarmonicModel(app: Application) : AndroidViewModel(app) {
    private val recorder = AccelRecorder(app)
    private val sessions = SessionRepository()
    private val diagnoser = HybridDiagnoser(loadPrompts(app))
    private val machineStore = MachineStore(app)

    /** Saved machines, most recently checked first. */
    var machines by mutableStateOf(sortMachines(machineStore.load()))
        private set
    /** The machine new checks are logged to; null for an unsaved one-off check. */
    var activeMachineId by mutableStateOf<String?>(null)
        private set
    /** The machine open on the history screen. */
    var viewedMachineId by mutableStateOf<String?>(null)
        private set
    val activeMachine: Machine? get() = machines.firstOrNull { it.id == activeMachineId }
    val viewedMachine: Machine? get() = machines.firstOrNull { it.id == viewedMachineId }

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
    /** Blank means "I don't know"; the DSP then assumes [DEFAULT_RPM]. */
    var rpmText by mutableStateOf("")
    var isoClass by mutableStateOf("class_I")
    var seconds by mutableStateOf(DEFAULT_SECONDS)
        private set
    /** True when phones should be tapped together (multi-phone sessions). */
    var syncTaps by mutableStateOf(false)
        private set
    /** 0 = first tap, 1 = hold still, 2 = second tap. Single-phone stays at 1. */
    var recordStep by mutableStateOf(1)
        private set
    val isLargeMachine: Boolean get() = isoClass != "class_I"
    private val effectiveRpm: Double get() = rpmText.toDoubleOrNull()?.takeIf { it >= 60 } ?: DEFAULT_RPM

    fun setLargeMachine(large: Boolean) {
        isoClass = if (large) "group2_rigid" else "class_I"
    }
    var elapsed by mutableStateOf(0f)
        private set
    var magnitude by mutableStateOf(0f)
        private set
    var result by mutableStateOf<ShownResult?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var isPreview by mutableStateOf(false)
        private set
    var trace by mutableStateOf(floatArrayOf())
        private set
    var tapCount by mutableStateOf(0)
        private set
    var lastTapAt by mutableStateOf(0L)
        private set
    var analyzing by mutableStateOf(false)
        private set

    private var listenJob: Job? = null
    private var captureJob: Job? = null
    private val shown = HashSet<String>()

    /** Sign-in state shown on Home; kept so returning Home restores the setup hint. */
    private var authNote = if (FirebaseGate.ready) "Signing in…" else "Firebase not configured — DSP still runs"

    val sensorOk: Boolean get() = recorder.available
    val isOwner: Boolean get() = uid != null && uid == session?.ownerUid

    init {
        if (FirebaseGate.ready) {
            viewModelScope.launch {
                runCatching { refreshRemoteConfig() }
                runCatching { uid = ensureSignedIn(); authNote = "Signed in" }
                    .onFailure { authNote = friendlyFirebaseError(it) }
                if (screen == Screen.Home) status = authNote
            }
        }
    }

    fun host() = launchAction {
        if (isPreview) error("Leave the debug preview before hosting a live session")
        val me = uid ?: ensureSignedIn() ?: error("Sign-in required")
        uid = me
        authNote = "Signed in"
        val c = joinCode()
        code = c
        val id = sessions.host(me, c, seconds)
        watch(id)
        screen = Screen.Lobby
    }

    fun join(raw: String) = launchAction {
        if (isPreview) error("Leave the debug preview before joining a live session")
        val me = uid ?: ensureSignedIn() ?: error("Sign-in required")
        uid = me
        authNote = "Signed in"
        val id = sessions.join(me, raw.trim().uppercase())
        code = raw.trim().uppercase()
        watch(id)
        screen = Screen.Lobby
    }

    fun startSessionRecording() = launchAction {
        if (isPreview) {
            recordThisPhone()
            return@launchAction
        }
        val id = session?.id ?: return@launchAction
        if (!isOwner) return@launchAction
        // Save the machine first so every phone records with the host's settings.
        sessions.updateMachine(id, machineType, effectiveRpm, isoClass, seconds)
        sessions.markRecording(id, seconds)
    }

    fun recordThisPhone() {
        captureJob?.cancel()
        captureJob = viewModelScope.launch {
            error = null
            analyzing = false
            syncTaps = false
            tapCount = 0
            lastTapAt = 0L
            trace = floatArrayOf()
            screen = Screen.Record
            try {
                val analysis = capture()
                analyzing = true
                busy = true
                status = "Analyzing"
                val features = featuresFor(analysis, null, emptyMap(), emptyMap(), 1.0)
                val (dx, gate) = diagnoser.diagnose(features, config)
                result = logToActiveMachine(
                    ShownResult(dx, gate, features, listOf("android"), shakeMmS = maxOf(analysis.velocityH, analysis.velocityV))
                )
                status = ""
                screen = Screen.Result
            } catch (e: CancellationException) {
                recorder.stop()
                throw e
            } catch (t: Throwable) {
                Log.w(TAG, "Single-phone reading failed", t)
                recorder.stop()
                status = if (isPreview) "" else authNote
                error = "Reading failed: ${friendlyFirebaseError(t)}"
                screen = if (isPreview) Screen.Lobby else Screen.Home
            } finally {
                busy = false
                analyzing = false
            }
        }
    }

    fun cancelRecording() {
        captureJob?.cancel()
        recorder.stop()
        analyzing = false
        busy = false
        elapsed = 0f
        magnitude = 0f
        trace = floatArrayOf()
        status = if (isPreview) "" else authNote
        screen = if (isPreview) Screen.Lobby else Screen.Home
    }

    fun openLobbyPreview() {
        if (!BuildConfig.DEBUG) return
        isPreview = true
        error = null
        status = ""
        uid = "preview"
        code = "PREVIEW"
        session = LiveSession(
            id = "preview",
            code = "PREVIEW",
            ownerUid = "preview",
            status = "lobby",
            members = listOf(Member("preview", "android", "This phone")),
            machineType = machineType,
            rpm = effectiveRpm,
            isoClass = isoClass,
            recordSeconds = seconds,
            diagnosis = null,
        )
        screen = Screen.Lobby
    }

    fun home() {
        captureJob?.cancel()
        // Leaving a live session must stop listening, or a later "recording"
        // from the host would pull this phone back into a capture.
        listenJob?.cancel()
        listenJob = null
        recorder.stop()
        busy = false
        status = authNote
        if (!isPreview) {
            session = null
            code = ""
        }
        if (isPreview) {
            isPreview = false
            if (uid == "preview") uid = null
            if (session?.id == "preview") session = null
            code = ""
        }
        analyzing = false
        screen = Screen.Home
        error = null
    }

    // ── Machine history ─────────────────────────────────────────────────────────

    /** Next checks go to this machine, using its saved type, size and speed. */
    fun selectMachine(id: String) {
        val m = machines.firstOrNull { it.id == id } ?: return
        activeMachineId = m.id
        machineType = m.type
        isoClass = m.isoClass
        rpmText = m.rpmText
        screen = Screen.Home
        error = null
    }

    fun clearActiveMachine() {
        activeMachineId = null
    }

    fun openMachine(id: String) {
        viewedMachineId = id
        screen = Screen.Machine
        error = null
    }

    fun checkMachine(id: String) {
        selectMachine(id)
        recordThisPhone()
    }

    /** Saves the shown (unsaved) result as the first check of a new machine. */
    fun saveResultAsMachine(rawName: String) {
        val shown = result ?: return
        if (shown.machineName != null) return
        val name = rawName.trim().ifBlank { suggestedMachineName() }.take(40)
        val machine = Machine(
            id = MachineStore.newId(),
            name = name,
            type = machineType,
            isoClass = isoClass,
            rpmText = rpmText,
            readings = listOf(shown.toReading()),
        )
        persist(machines + machine)
        activeMachineId = machine.id
        result = shown.copy(machineName = name)
    }

    fun deleteMachine(id: String) {
        persist(machines.filterNot { it.id == id })
        if (activeMachineId == id) activeMachineId = null
        if (viewedMachineId == id) viewedMachineId = null
        screen = Screen.Home
    }

    fun suggestedMachineName(): String {
        val base = machineType.replaceFirstChar { it.uppercase() }
        val used = machines.map { it.name }.toSet()
        return (1..99).map { "$base $it" }.firstOrNull { it !in used } ?: base
    }

    private fun logToActiveMachine(shown: ShownResult): ShownResult {
        val m = activeMachine ?: return shown
        val reading = shown.toReading()
        val before = m.last
        val updated = m.copy(type = machineType, isoClass = isoClass, rpmText = rpmText, readings = m.readings + reading)
        return runCatching {
            persist(machines.map { if (it.id == m.id) updated else it })
            shown.copy(machineName = m.name, comparison = compareReadings(reading, before))
        }.getOrElse {
            Log.w(TAG, "Could not save the check to ${m.name}", it)
            shown
        }
    }

    private fun persist(list: List<Machine>) {
        val sorted = sortMachines(list)
        machineStore.save(sorted)
        machines = sorted
    }

    fun openJoin() {
        screen = Screen.Join
        error = null
    }

    private fun watch(id: String) {
        listenJob?.cancel()
        listenJob = viewModelScope.launch {
            try {
                sessions.listen(id).collect { live ->
                    session = live
                    if (!isOwner) {
                        // Joiners follow the host's machine settings and duration.
                        machineType = live.machineType
                        rpmText = if (live.rpm == DEFAULT_RPM) "" else live.rpm.toInt().toString()
                        isoClass = live.isoClass
                        seconds = live.recordSeconds.coerceIn(MIN_SECONDS, MAX_SECONDS)
                    }
                    val recKey = live.id + ":rec"
                    if (live.status == "recording" && recKey !in shown) {
                        shown.add(recKey)
                        captureAndUpload(live)
                    }
                    val diagnosis = live.diagnosis
                    if (live.status == "done" && diagnosis != null && screen != Screen.Result) {
                        result = diagnosisFromMap(diagnosis)
                        busy = false
                        analyzing = false
                        status = ""
                        screen = Screen.Result
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w(TAG, "Session listener failed", t)
                error = "Lost the session: ${friendlyFirebaseError(t)}"
                busy = false
                analyzing = false
                screen = Screen.Home
            }
        }
    }

    private suspend fun captureAndUpload(live: LiveSession) {
        error = null
        syncTaps = true
        screen = Screen.Record
        try {
            val analysis = capture()
            analyzing = true
            val me = uid ?: error("Not signed in")
            val b64 = encodeChannels(analysis.uniform.v, analysis.uniform.a, analysis.uniform.h)
            status = "Uploading this phone's recording"
            sessions.uploadRecording(live.id, me, analysis, b64)
            if (me == live.ownerUid) {
                busy = true
                status = "Waiting for the other phones"
                var rows = sessions.loadRecordings(live.id)
                val expected = live.members.size.coerceAtLeast(1)
                var waits = 0
                while (rows.size < expected && waits < 40) {
                    delay(500)
                    rows = sessions.loadRecordings(live.id)
                    waits++
                }
                publishCombined(live, rows)
            } else {
                analyzing = false
                status = "Uploaded. Waiting for the host's diagnosis."
                screen = Screen.Lobby
            }
        } catch (e: CancellationException) {
            recorder.stop()
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "Session capture failed", t)
            recorder.stop()
            busy = false
            analyzing = false
            status = ""
            error = "Recording failed: ${friendlyFirebaseError(t)}"
            screen = Screen.Lobby
        }
    }

    private suspend fun publishCombined(live: LiveSession, rows: List<Map<String, Any?>>) {
        status = "Running DSP and the hybrid model"
        // Host phone first so the reference clock and running speed come from it.
        val ordered = rows.sortedByDescending { it["uid"] == live.ownerUid }
        val analyses = ordered.mapNotNull { rowToAnalysis(it) }
        val primary = analyses.firstOrNull() ?: error("No usable recordings were uploaded")
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
        result = logToActiveMachine(
            ShownResult(
                dx, gate, features, rows.map { it["platform"]?.toString() ?: "?" },
                shakeMmS = maxOf(primary.velocityH, primary.velocityV),
            )
        )
        busy = false
        analyzing = false
        status = ""
        screen = Screen.Result
    }

    private suspend fun capture(): PhoneAnalysis {
        elapsed = 0f
        tapCount = 0
        lastTapAt = 0L
        trace = floatArrayOf()
        recorder.start()
        try {
            val total = seconds
            val steps = total * 10
            recordStep = if (syncTaps) 0 else 1
            if (syncTaps) pulseTap(120)
            repeat(steps) { i ->
                delay(100)
                elapsed = (i + 1) / 10f
                magnitude = recorder.lastMagnitude
                trace = recorder.snapshotTrace()
                val step = when {
                    !syncTaps -> 1
                    elapsed < TAP_WINDOW_S -> 0
                    elapsed > total - TAP_WINDOW_S -> 2
                    else -> 1
                }
                if (step != recordStep) {
                    recordStep = step
                    // A buzz on each step change, since the screen may not be in view.
                    pulseTap(120)
                }
                val taps = recorder.consumeTaps()
                if (taps > 0) {
                    tapCount += taps
                    lastTapAt = SystemClock.elapsedRealtime()
                    pulseTap()
                }
            }
            pulseTap(300)
        } catch (e: CancellationException) {
            recorder.stop()
            throw e
        }
        val recording = recorder.stop()
        if (recording.raw.size < 32) error("Not enough samples (${recording.raw.size}). Is the accelerometer available?")
        return recording.analyze(
            effectiveRpm,
            isoClass,
            config.thresholds,
        )
    }

    private fun pulseTap(ms: Long = 40) {
        val app = getApplication<Application>()
        val vibrator = if (Build.VERSION.SDK_INT >= 31) {
            app.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            app.getSystemService(Vibrator::class.java)
        } ?: return
        if (!vibrator.hasVibrator()) return
        vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun featuresFor(
        primary: PhoneAnalysis,
        secondary: PhoneAnalysis?,
        phase: Map<String, Double?>,
        coherence: Map<String, Double?>,
        syncConf: Double,
        type: String = machineType,
        rpm: Double = effectiveRpm,
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
                "record_seconds" to DEFAULT_SECONDS.toLong(),
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
            recordSeconds = rc.getLong("record_seconds").toInt().coerceIn(MIN_SECONDS, MAX_SECONDS),
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
                error = friendlyFirebaseError(t)
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
    /** Overall shake (velocity RMS, mm/s) on this phone; null when the result came from another phone. */
    val shakeMmS: Double? = null,
    /** Set once the check is saved to a machine's history. */
    val machineName: String? = null,
    val comparison: Comparison? = null,
)

enum class Screen { Home, Join, Lobby, Record, Result, Machine }

private const val TAG = "Harmonic"

private fun sortMachines(list: List<Machine>) = list.sortedByDescending { it.last?.at ?: 0L }

private fun ShownResult.toReading() = Reading(
    at = System.currentTimeMillis(),
    zone = diagnosis.severityZone,
    fault = diagnosis.fault,
    shakeMmS = shakeMmS,
    confidence = diagnosis.confidence,
    source = diagnosis.inferenceSource,
)

/** Typical 4-pole farm motor on 60 Hz mains; used when the RPM is left blank. */
const val DEFAULT_RPM = 1800.0
const val DEFAULT_SECONDS = 15
private const val MIN_SECONDS = 8
private const val MAX_SECONDS = 20
/** Seconds at the start and end of a multi-phone recording reserved for the sync taps. */
const val TAP_WINDOW_S = 3f

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

private fun friendlyFirebaseError(t: Throwable): String {
    val msg = t.message ?: t.javaClass.simpleName
    return when {
        msg.contains("CONFIGURATION_NOT_FOUND") ->
            "Anonymous sign-in is not enabled yet. In the Firebase console, open Authentication, add the Anonymous provider, and try again."
        msg.contains("App Check token is invalid", ignoreCase = true) ||
            msg.contains("App attestation failed", ignoreCase = true) ->
            "App Check rejected this debug build. Register the emulator debug token in Firebase App Check and try again."
        else -> msg
    }
}
