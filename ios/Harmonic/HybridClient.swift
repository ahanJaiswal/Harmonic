import Foundation
import FoundationModels
import FirebaseAILogic

/// Prefer Apple Foundation Models, then fall back to Gemini through Firebase AI Logic.
///
/// Firebase Apple SDK 13.0.0 (October 2026) removed `HybridModel` and
/// `SystemLanguageModel`. The current hybrid path, documented at
/// https://firebase.google.com/docs/ai-logic/apple-foundation-models-framework/get-started
/// (updated 2026-11-18), is Apple's `LanguageModelSession` with either the
/// on-device system model or `FirebaseAI.geminiLanguageModel(name:)`.
/// Resumes a continuation exactly once, whichever side finishes first.
private final class ResumeOnce: @unchecked Sendable {
    private let lock = NSLock()
    private var done = false
    func claim() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        if done { return false }
        done = true
        return true
    }
}

enum HybridClient {
    /// Longest any single AI call may take before the app keeps the answer it has.
    static let timeoutSeconds: Double = 15

    /// Races the model call against a timer. A task group would wait for a call that ignores
    /// cancellation, so the timer resumes the continuation directly instead.
    static func diagnose(featuresJSON: String, systemPrompt: String, cloudModel: String) async -> (text: String?, source: String) {
        await withCheckedContinuation { (cont: CheckedContinuation<(text: String?, source: String), Never>) in
            let once = ResumeOnce()
            let work = Task {
                let reply = await HybridClient.attempt(featuresJSON: featuresJSON, systemPrompt: systemPrompt, cloudModel: cloudModel)
                if once.claim() { cont.resume(returning: reply) }
            }
            Task {
                try? await Task.sleep(nanoseconds: UInt64(HybridClient.timeoutSeconds * 1_000_000_000))
                if once.claim() {
                    work.cancel()
                    cont.resume(returning: (text: nil, source: "physics_fallback"))
                }
            }
        }
    }

    fileprivate static func attempt(featuresJSON: String, systemPrompt: String, cloudModel: String) async -> (text: String?, source: String) {
        let prompt = systemPrompt + "\n\nFeature JSON:\n" + featuresJSON
        if #available(iOS 26.0, *) {
            if case .available = SystemLanguageModel.default.availability {
                do {
                    let session = LanguageModelSession(model: SystemLanguageModel.default)
                    let response = try await session.respond(to: prompt)
                    return (response.content, "on_device")
                } catch {
                    // Cloud fallback below.
                }
            }
        }
        if #available(iOS 27.0, *) {
            do {
                let ai = FirebaseAI.firebaseAI(backend: .googleAI())
                let model = ai.geminiLanguageModel(name: cloudModel)
                let session = LanguageModelSession(model: model)
                let response = try await session.respond(to: prompt)
                return (response.content, "cloud")
            } catch {
                return (nil, "physics_fallback")
            }
        }
        return (nil, "physics_fallback")
    }
}
