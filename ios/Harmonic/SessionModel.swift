import Foundation
import CoreMotion
import FirebaseAuth
import FirebaseFirestore
import FirebaseRemoteConfig
import HarmonicDSP

@MainActor
final class SessionModel: ObservableObject {
    @Published var screen = "home"
    @Published var status = FirebaseGate.ready ? "Firebase ready" : "Firebase not configured — DSP still runs"
    @Published var error: String?
    @Published var code = ""
    @Published var joinText = ""
    @Published var members: [String] = []
    @Published var machine = "fan"
    @Published var rpm = "1200"
    @Published var iso = "class_I"
    @Published var seconds = 12
    @Published var elapsed = 0.0
    @Published var magnitude = 0.0
    @Published var summary = ""
    @Published var fault = ""
    @Published var zone = ""
    @Published var confidence = 0.0
    @Published var action = ""
    @Published var source = ""
    @Published var busy = false

    private let motion = CMMotionManager()
    private var samples: [(t: Double, x: Double, y: Double, z: Double)] = []
    private var sensor0: TimeInterval?
    private var wall0: TimeInterval = 0
    private var uid: String?
    private var sessionId: String?
    private var owner = false
    private var listener: ListenerRegistration?
    // Lazy so the app still launches (DSP-only) when FirebaseApp was never configured.
    private lazy var db = Firestore.firestore()
    private var cloudModel = "gemini-3.5-flash-lite"
    private var refineModel = "gemini-3.5-flash-lite"

    func prepare() async {
        guard FirebaseGate.ready else { return }
        if Auth.auth().currentUser == nil {
            _ = try? await Auth.auth().signInAnonymously()
        }
        uid = Auth.auth().currentUser?.uid
        let rc = RemoteConfig.remoteConfig()
        let settings = RemoteConfigSettings()
        settings.minimumFetchInterval = 60
        rc.configSettings = settings
        rc.setDefaults([
            "model_cloud_refine": "gemini-3.5-flash-lite" as NSObject,
            "model_cloud_fallback": "gemini-3.5-flash-lite" as NSObject,
            "record_seconds": 12 as NSObject,
        ])
        _ = try? await rc.fetchAndActivate()
        cloudModel = rc["model_cloud_fallback"].stringValue ?? cloudModel
        refineModel = rc["model_cloud_refine"].stringValue ?? refineModel
        let secs = rc["record_seconds"].numberValue.intValue
        if secs >= 8 { seconds = secs }
        status = "Signed in"
    }

    func host() async {
        await run {
            guard let uid = self.uid else { throw NSError(domain: "harmonic", code: 1, userInfo: [NSLocalizedDescriptionKey: "Sign-in required"]) }
            let alphabet = Array("ABCDEFGHJKLMNPQRSTUVWXYZ23456789")
            self.code = String((0..<6).map { _ in alphabet.randomElement()! })
            let ref = self.db.collection("sessions").document()
            self.sessionId = ref.documentID
            self.owner = true
            try await ref.setData([
                "ownerUid": uid,
                "joinCode": self.code,
                "memberUids": [uid],
                "members": [uid: ["platform": "ios", "name": "iPhone-\(uid.suffix(4))"]],
                "status": "lobby",
                "machine": ["type": self.machine, "rpmNameplate": Double(self.rpm) ?? 1200, "isoClass": self.iso, "mounting": "rigid"],
                "recordSeconds": self.seconds,
                "createdAt": FieldValue.serverTimestamp(),
            ])
            try await self.db.collection("joinCodes").document(self.code).setData(["sessionId": ref.documentID, "ownerUid": uid])
            self.watch(ref.documentID)
            self.screen = "lobby"
        }
    }

    func join() async {
        await run {
            guard let uid = self.uid else { throw NSError(domain: "harmonic", code: 1, userInfo: [NSLocalizedDescriptionKey: "Sign-in required"]) }
            let code = self.joinText.trimmingCharacters(in: .whitespaces).uppercased()
            let snap = try await self.db.collection("joinCodes").document(code).getDocument()
            guard let id = snap.data()?["sessionId"] as? String else { throw NSError(domain: "harmonic", code: 2, userInfo: [NSLocalizedDescriptionKey: "No session for that code"]) }
            self.code = code
            self.sessionId = id
            self.owner = false
            let ref = self.db.collection("sessions").document(id)
            _ = try await self.db.runTransaction { tx, errorPointer -> Any? in
                let cur: DocumentSnapshot
                do { cur = try tx.getDocument(ref) } catch {
                    errorPointer?.pointee = error as NSError
                    return nil
                }
                var members = cur.data()?["members"] as? [String: Any] ?? [:]
                members[uid] = ["platform": "ios", "name": "iPhone-\(uid.suffix(4))"]
                var uids = cur.data()?["memberUids"] as? [String] ?? []
                if !uids.contains(uid) { uids.append(uid) }
                tx.updateData(["members": members, "memberUids": uids], forDocument: ref)
                return nil
            }
            self.watch(id)
            self.screen = "lobby"
        }
    }

    func recordLocal() async {
        screen = "record"
        let analysis = await capture()
        await finish(analysis, other: nil)
    }

    func startGroup() async {
        guard let id = sessionId else { return }
        try? await db.collection("sessions").document(id).updateData([
            "status": "recording",
            "recordSeconds": seconds,
            "machine": ["type": machine, "rpmNameplate": Double(rpm) ?? 0, "isoClass": iso, "mounting": "rigid"],
        ])
    }

    private func watch(_ id: String) {
        listener?.remove()
        listener = db.collection("sessions").document(id).addSnapshotListener { [weak self] snap, _ in
            guard let self, let data = snap?.data() else { return }
            Task { @MainActor in
                let members = data["members"] as? [String: Any] ?? [:]
                self.members = members.values.compactMap { ($0 as? [String: Any])?["name"] as? String }
                if data["status"] as? String == "recording" && self.screen != "record" && self.screen != "result" {
                    let analysis = await self.capture()
                    await self.upload(analysis)
                    if self.owner { await self.combine() }
                }
                if data["status"] as? String == "done", let dx = data["diagnosis"] as? [String: Any], !self.owner {
                    self.show(dx)
                }
            }
        }
    }

    private func capture() async -> PhoneAnalysis {
        samples.removeAll()
        sensor0 = nil
        elapsed = 0
        motion.accelerometerUpdateInterval = 1.0 / 200.0
        motion.startAccelerometerUpdates(to: .main) { [weak self] data, _ in
            MainActor.assumeIsolated {
                guard let self, let data else { return }
                if self.sensor0 == nil {
                    self.sensor0 = data.timestamp
                    self.wall0 = Date().timeIntervalSince1970
                }
                let t = self.wall0 + (data.timestamp - (self.sensor0 ?? data.timestamp))
                let g = 9.80665
                self.samples.append((t, data.acceleration.x * g, data.acceleration.y * g, data.acceleration.z * g))
                self.magnitude = sqrt(pow(data.acceleration.x, 2) + pow(data.acceleration.y, 2) + pow(data.acceleration.z, 2)) * g
            }
        }
        let total = seconds
        for tick in 1...(total * 10) {
            try? await Task.sleep(nanoseconds: 100_000_000)
            elapsed = Double(tick) / 10
        }
        motion.stopAccelerometerUpdates()
        let raw = samples.map { [$0.x, $0.y, $0.z] }
        let ts = samples.map { $0.t }
        // 0 means unknown: the DSP searches the whole speed range.
        return analyzePhone(raw: raw, timestamps: ts, rpmNameplate: Double(rpm) ?? 0, isoClass: iso)
    }

    private func upload(_ analysis: PhoneAnalysis) async {
        guard let id = sessionId, let uid else { return }
        let n = analysis.v.count
        var bytes = Data(capacity: n * 12)
        for i in 0..<n {
            var x = Float(analysis.v[i]), y = Float(analysis.a[i]), z = Float(analysis.h[i])
            withUnsafeBytes(of: &x) { bytes.append(contentsOf: $0) }
            withUnsafeBytes(of: &y) { bytes.append(contentsOf: $0) }
            withUnsafeBytes(of: &z) { bytes.append(contentsOf: $0) }
        }
        try? await db.collection("sessions").document(id).collection("recordings").document(uid).setData([
            "uid": uid, "platform": "ios", "ready": true,
            "fsHz": analysis.fsHz, "t0": analysis.timestamps.first ?? 0, "n": n,
            "channelsB64": bytes.base64EncodedString(),
            "runningSpeedHz": analysis.runningSpeedHz,
            "zone": analysis.severityZone,
            "velocityH": analysis.velocityH, "velocityV": analysis.velocityV, "velocityA": analysis.velocityA,
            "orders": analysis.orders,
            "axialToRadial": analysis.axialToRadial as Any,
            "crestFactor": analysis.crestFactor, "kurtosis": analysis.kurtosis,
            "dominantAxis": analysis.dominantAxis,
        ])
    }

    private func combine() async {
        guard let id = sessionId else { return }
        status = "Waiting for the other phones"
        var snap: QuerySnapshot?
        for _ in 0..<40 {
            snap = try? await db.collection("sessions").document(id).collection("recordings").getDocuments()
            if (snap?.documents.count ?? 0) >= max(1, members.count) { break }
            try? await Task.sleep(nanoseconds: 500_000_000)
        }
        status = "Running DSP and the hybrid model"
        let platforms = snap?.documents.compactMap { $0.data()["platform"] as? String } ?? ["ios"]
        // Every phone counts: start from the host's own recording, then take the worst
        // severity any phone measured.
        let docs = snap?.documents ?? []
        let mine = docs.first { ($0.data()["uid"] as? String) == uid } ?? docs.first
        let first = mine.flatMap { row($0.data()) }
        // `await` is not allowed inside the autoclosure on the right of `??`.
        var analysis: PhoneAnalysis
        if let first {
            analysis = first
        } else {
            analysis = await capture()
        }
        let zones = docs.compactMap { $0.data()["zone"] as? String } + [analysis.severityZone]
        analysis.severityZone = zones.max() ?? analysis.severityZone
        await finish(analysis, other: nil, platforms: platforms, publish: true)
    }

    private func finish(_ analysis: PhoneAnalysis, other: PhoneAnalysis?, platforms: [String] = ["ios"], publish: Bool = false) async {
        let features = featureJSON(analysis)
        let system = (try? String(contentsOf: Bundle.main.url(forResource: "v1_first_pass", withExtension: "md") ?? URL(fileURLWithPath: "/dev/null"), encoding: .utf8))
            ?? "Reply with JSON only. Copy severity_zone."
        var reply = await HybridClient.diagnose(featuresJSON: features, systemPrompt: system, cloudModel: cloudModel)
        if reply.source != "physics_fallback" && (analysis.severityZone == "C" || analysis.severityZone == "D") {
            reply = await HybridClient.diagnose(featuresJSON: features + "\nVerify the previous call.", systemPrompt: system, cloudModel: refineModel)
            if reply.text != nil { reply.source = "cloud_refinement" }
        }
        let parsed = parseDiagnosis(reply.text, analysis: analysis, source: reply.source)
        summary = parsed.summary
        fault = parsed.fault
        zone = parsed.zone
        confidence = parsed.confidence
        action = parsed.action
        source = parsed.source
        screen = "result"
        if publish, let id = sessionId {
            try? await db.collection("sessions").document(id).updateData([
                "status": "done",
                "diagnosis": [
                    "fault": fault, "summary": summary, "severityZone": zone,
                    "confidence": confidence, "recommendedAction": action,
                    "inferenceSource": source, "platforms": platforms,
                ],
            ])
        }
    }

    private func show(_ dx: [String: Any]) {
        fault = dx["fault"] as? String ?? "unknown"
        summary = dx["summary"] as? String ?? ""
        zone = dx["severityZone"] as? String ?? "A"
        confidence = (dx["confidence"] as? NSNumber)?.doubleValue ?? 0
        action = dx["recommendedAction"] as? String ?? ""
        source = dx["inferenceSource"] as? String ?? "cloud"
        screen = "result"
    }

    private func row(_ data: [String: Any]) -> PhoneAnalysis? {
        guard let n = data["n"] as? Int, let b64 = data["channelsB64"] as? String, let raw = Data(base64Encoded: b64) else { return nil }
        let fs = (data["fsHz"] as? NSNumber)?.doubleValue ?? 100
        let t0 = (data["t0"] as? NSNumber)?.doubleValue ?? 0
        var v: [Double] = [], a: [Double] = [], h: [Double] = []
        raw.withUnsafeBytes { (buf: UnsafeRawBufferPointer) in
            for i in 0..<n {
                let base = i * 12
                let x = buf.loadUnaligned(fromByteOffset: base, as: Float.self)
                let y = buf.loadUnaligned(fromByteOffset: base + 4, as: Float.self)
                let z = buf.loadUnaligned(fromByteOffset: base + 8, as: Float.self)
                v.append(Double(x)); a.append(Double(y)); h.append(Double(z))
            }
        }
        let orders = data["orders"] as? [String: Double] ?? [:]
        return PhoneAnalysis(
            fsHz: fs, durationS: Double(max(0, n - 1)) / fs,
            runningSpeedHz: (data["runningSpeedHz"] as? NSNumber)?.doubleValue ?? 0,
            orders: orders,
            velocityH: (data["velocityH"] as? NSNumber)?.doubleValue ?? 0,
            velocityV: (data["velocityV"] as? NSNumber)?.doubleValue ?? 0,
            velocityA: (data["velocityA"] as? NSNumber)?.doubleValue ?? 0,
            severityZone: data["zone"] as? String ?? "A",
            dominantAxis: data["dominantAxis"] as? String ?? "H",
            axialToRadial: (data["axialToRadial"] as? NSNumber)?.doubleValue,
            crestFactor: (data["crestFactor"] as? NSNumber)?.doubleValue ?? 0,
            kurtosis: (data["kurtosis"] as? NSNumber)?.doubleValue ?? 0,
            v: v, a: a, h: h,
            timestamps: (0..<n).map { t0 + Double($0) / fs }
        )
    }

    private func featureJSON(_ analysis: PhoneAnalysis) -> String {
        let payload: [String: Any] = [
            "machine": ["type": machine, "rpm_nameplate": Double(rpm) ?? analysis.runningSpeedHz * 60, "mounting": "rigid", "iso_class": iso],
            "sampling": ["fs_hz": [analysis.fsHz], "duration_s": analysis.durationS, "band_limit_hz": analysis.fsHz / 2],
            "running_speed_hz": analysis.runningSpeedHz,
            "overall_velocity_mm_s": ["A_H": analysis.velocityH, "A_V": analysis.velocityV, "A_A": analysis.velocityA],
            "severity_zone": analysis.severityZone,
            "orders": analysis.orders,
            "axial_to_radial_ratio": analysis.axialToRadial as Any,
            "crest_factor": analysis.crestFactor,
            "kurtosis": analysis.kurtosis,
        ]
        let data = try? JSONSerialization.data(withJSONObject: payload, options: [.sortedKeys])
        return data.flatMap { String(data: $0, encoding: .utf8) } ?? "{}"
    }

    private func parseDiagnosis(_ text: String?, analysis: PhoneAnalysis, source: String) -> (summary: String, fault: String, zone: String, confidence: Double, action: String, source: String) {
        let zone = analysis.severityZone
        guard let text, let start = text.firstIndex(of: "{"), let end = text.lastIndex(of: "}"),
              let data = String(text[start...end]).data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            let family = faultFamily("static_imbalance", kurtosis: analysis.kurtosis)
            let picked = (analysis.orders["2x"] ?? 0) > (analysis.orders["1x"] ?? 0) ? "misalignment" : (analysis.kurtosis >= 5 ? "bearing_wear" : "imbalance")
            return ("Screening from the on-device DSP. ISO zone \(zone).", picked, zone, 0.55, recommendedAction(family: picked, zone: zone), "physics_fallback")
        }
        // The model may answer with a family ("misalignment") or a detail
        // ("parallel_misalignment"); show the family either way.
        let fault = faultFamily(obj["fault"] as? String ?? "unknown", kurtosis: analysis.kurtosis)
        return (
            obj["summary"] as? String ?? "",
            fault,
            zone,
            (obj["confidence"] as? NSNumber)?.doubleValue ?? 0.5,
            obj["recommended_action"] as? String ?? recommendedAction(family: fault, zone: zone),
            source
        )
    }

    private func run(_ body: () async throws -> Void) async {
        busy = true
        error = nil
        do { try await body() } catch { self.error = error.localizedDescription }
        busy = false
    }
}
