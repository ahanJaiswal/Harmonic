package com.harmonic.app

import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.ai.InferenceMode
import com.google.firebase.ai.InferenceSource
import com.google.firebase.ai.OnDeviceConfig
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.PublicPreviewAPI
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import com.google.firebase.auth.FirebaseAuth
import com.harmonic.dsp.Diagnosis
import com.harmonic.dsp.GateResult
import com.harmonic.dsp.GateSettings
import com.harmonic.dsp.featuresToJson
import com.harmonic.dsp.finalizeDiagnosis
import com.harmonic.dsp.needsCloudRefinement
import kotlinx.coroutines.tasks.await

data class HarmonicConfig(
    val cloudRefineModel: String = "gemini-3.8-flash",
    val cloudFallbackModel: String = "gemini-3.5-flash-lite",
    val promptVersion: String = "v1",
    val recordSeconds: Int = DEFAULT_SECONDS,
    val settings: GateSettings = GateSettings(),
    val maxOnDeviceCalls: Int = 3,
    val thresholds: Map<String, com.harmonic.dsp.ZoneThresholds>? = null,
)

class HybridDiagnoser(
    private val prompts: Map<String, String>,
) {
    private var onDeviceCalls = 0

    @OptIn(PublicPreviewAPI::class)
    suspend fun diagnose(features: Map<String, Any?>, config: HarmonicConfig): Pair<Diagnosis, GateResult> {
        if (!FirebaseGate.ready) {
            return finalizeDiagnosis(null, features, "physics_fallback", config.settings)
        }
        val system = prompts[config.promptVersion] ?: prompts["v1"] ?: DEFAULT_PROMPT
        val user = "Feature JSON (prompt ${config.promptVersion}):\n" + featuresToJson(features)
        val preferDevice = onDeviceCalls < config.maxOnDeviceCalls
        val first = generate(
            modelName = config.cloudFallbackModel,
            system = system,
            user = user,
            mode = if (preferDevice) InferenceMode.PREFER_ON_DEVICE else InferenceMode.ONLY_IN_CLOUD,
        )
        if (preferDevice) onDeviceCalls++
        var (diagnosis, gate) = finalizeDiagnosis(first.text, features, first.source, config.settings)
        if (needsCloudRefinement(diagnosis, gate, config.settings)) {
            val refineUser = user +
                "\n\nOn-device hypothesis to verify or correct:\n" + featuresToJson(
                    mapOf(
                        "fault" to diagnosis.fault,
                        "summary" to diagnosis.summary,
                        "confidence" to diagnosis.confidence,
                        "hypotheses" to diagnosis.hypotheses,
                    )
                ) +
                "\nReturn the same JSON schema. Keep severity_zone as ${diagnosis.severityZone}."
            val second = generate(
                modelName = config.cloudRefineModel,
                system = system,
                user = refineUser,
                mode = InferenceMode.ONLY_IN_CLOUD,
            )
            val refined = finalizeDiagnosis(second.text, features, "cloud_refinement", config.settings)
            if (refined.first.inferenceSource != "physics_fallback") {
                diagnosis = refined.first
                gate = refined.second
            }
        }
        return diagnosis to gate
    }

    @OptIn(PublicPreviewAPI::class)
    private suspend fun generate(
        modelName: String,
        system: String,
        user: String,
        mode: InferenceMode,
    ): ModelReply {
        return try {
            val model = Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
                modelName = modelName,
                systemInstruction = com.google.firebase.ai.type.content { text(system) },
                generationConfig = generationConfig { responseMimeType = "application/json" },
                onDeviceConfig = OnDeviceConfig(mode = mode),
            )
            val response = model.generateContent(user)
            val source = when (response.inferenceSource) {
                InferenceSource.ON_DEVICE -> "on_device"
                InferenceSource.IN_CLOUD -> "cloud"
                else -> if (mode == InferenceMode.ONLY_IN_CLOUD) "cloud" else "cloud"
            }
            ModelReply(response.text, source)
        } catch (t: Throwable) {
            Log.w(TAG, "Hybrid inference failed ($mode, $modelName)", t)
            ModelReply(null, "physics_fallback")
        }
    }

    private data class ModelReply(val text: String?, val source: String)

    companion object {
        private const val TAG = "Harmonic"
        const val DEFAULT_PROMPT = "You are Harmonic, a vibration analyst. Reply with JSON only."
    }
}

suspend fun ensureSignedIn(): String? {
    if (!FirebaseGate.ready) return null
    val auth = FirebaseAuth.getInstance()
    auth.currentUser?.uid?.let { return it }
    return auth.signInAnonymously().await().user?.uid
}
