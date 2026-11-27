package com.harmonic.app

import com.google.firebase.Firebase
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.firestore
import com.harmonic.dsp.PhoneAnalysis
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

data class Member(val uid: String, val platform: String, val name: String)

data class LiveSession(
    val id: String,
    val code: String,
    val ownerUid: String,
    val status: String,
    val members: List<Member>,
    val machineType: String,
    val rpm: Double,
    val isoClass: String,
    val recordSeconds: Int,
    val diagnosis: Map<String, Any?>?,
)

class SessionRepository {
    private val db: FirebaseFirestore get() = Firebase.firestore

    suspend fun host(uid: String, code: String, configSeconds: Int): String {
        val id = db.collection("sessions").document().id
        val doc = mapOf(
            "ownerUid" to uid,
            "joinCode" to code,
            "memberUids" to listOf(uid),
            "members" to mapOf(uid to mapOf("platform" to "android", "name" to "Android-${uid.takeLast(4)}")),
            "status" to "lobby",
            "machine" to mapOf("type" to "fan", "rpmNameplate" to 1200.0, "isoClass" to "class_I", "mounting" to "rigid"),
            "recordSeconds" to configSeconds,
            "createdAt" to FieldValue.serverTimestamp(),
        )
        db.collection("sessions").document(id).set(doc).await()
        db.collection("joinCodes").document(code).set(mapOf("sessionId" to id, "ownerUid" to uid)).await()
        return id
    }

    suspend fun join(uid: String, code: String): String {
        val snap = db.collection("joinCodes").document(code.trim().uppercase()).get().await()
        val id = snap.getString("sessionId") ?: error("No session for that code")
        val ref = db.collection("sessions").document(id)
        db.runTransaction { tx ->
            val cur = tx.get(ref)
            val members = (cur.get("members") as? Map<*, *>)?.toMutableMap() ?: mutableMapOf()
            members[uid] = mapOf("platform" to "android", "name" to "Android-${uid.takeLast(4)}")
            val uids = ((cur.get("memberUids") as? List<*>)?.map { it.toString() } ?: emptyList()).toMutableSet()
            uids.add(uid)
            tx.update(ref, mapOf("members" to members, "memberUids" to uids.toList()))
        }.await()
        return id
    }

    fun listen(id: String): Flow<LiveSession> = callbackFlow {
        val reg = db.collection("sessions").document(id).addSnapshotListener { snap, err ->
            if (err != null || snap == null || !snap.exists()) return@addSnapshotListener
            trySend(snap.toSession(id))
        }
        awaitClose { reg.remove() }
    }

    suspend fun updateMachine(id: String, type: String, rpm: Double, isoClass: String, seconds: Int) {
        db.collection("sessions").document(id).update(
            mapOf(
                "machine" to mapOf("type" to type, "rpmNameplate" to rpm, "isoClass" to isoClass, "mounting" to "rigid"),
                "recordSeconds" to seconds,
            )
        ).await()
    }

    suspend fun markRecording(id: String, seconds: Int) {
        val now = System.currentTimeMillis()
        db.collection("sessions").document(id).update(
            mapOf(
                "status" to "recording",
                "record" to mapOf(
                    "startedWallMs" to now,
                    "tap1Wall" to (now / 1000.0 + 1.2),
                    "tap2Wall" to (now / 1000.0 + seconds - 1.2),
                    "seconds" to seconds,
                ),
            )
        ).await()
    }

    suspend fun uploadRecording(sessionId: String, uid: String, analysis: PhoneAnalysis, b64: String) {
        val body = mapOf(
            "uid" to uid,
            "platform" to "android",
            "ready" to true,
            "fsHz" to analysis.fsHz,
            "t0" to analysis.uniform.timestamps.first(),
            "n" to analysis.uniform.v.size,
            "channelsB64" to b64,
            "runningSpeedHz" to analysis.runningSpeedHz,
            "zone" to analysis.severityZone,
            "velocityH" to analysis.velocityH,
            "velocityV" to analysis.velocityV,
            "velocityA" to analysis.velocityA,
            "orders" to analysis.orders,
            "axialToRadial" to analysis.axialToRadial,
            "crestFactor" to analysis.crestFactor,
            "kurtosis" to analysis.kurtosis,
            "dominantAxis" to analysis.dominantAxis,
        )
        db.collection("sessions").document(sessionId).collection("recordings").document(uid)
            .set(body, SetOptions.merge()).await()
    }

    suspend fun loadRecordings(sessionId: String): List<Map<String, Any?>> {
        val snap = db.collection("sessions").document(sessionId).collection("recordings").get().await()
        return snap.documents.map { it.data ?: emptyMap() }
    }

    suspend fun publishDiagnosis(sessionId: String, payload: Map<String, Any?>) {
        db.collection("sessions").document(sessionId).update(
            mapOf("status" to "done", "diagnosis" to payload)
        ).await()
    }

    private fun com.google.firebase.firestore.DocumentSnapshot.toSession(id: String): LiveSession {
        val machine = get("machine") as? Map<*, *> ?: emptyMap<String, Any>()
        val membersMap = get("members") as? Map<*, *> ?: emptyMap<String, Any>()
        val members = membersMap.map { (k, v) ->
            val m = v as? Map<*, *> ?: emptyMap<String, Any>()
            Member(k.toString(), m["platform"]?.toString() ?: "?", m["name"]?.toString() ?: k.toString())
        }
        @Suppress("UNCHECKED_CAST")
        val diagnosis = get("diagnosis") as? Map<String, Any?>
        return LiveSession(
            id = id,
            code = getString("joinCode") ?: "",
            ownerUid = getString("ownerUid") ?: "",
            status = getString("status") ?: "lobby",
            members = members,
            machineType = machine["type"]?.toString() ?: "fan",
            rpm = (machine["rpmNameplate"] as? Number)?.toDouble() ?: 1200.0,
            isoClass = machine["isoClass"]?.toString() ?: "class_I",
            recordSeconds = (get("recordSeconds") as? Number)?.toInt() ?: 12,
            diagnosis = diagnosis,
        )
    }
}
