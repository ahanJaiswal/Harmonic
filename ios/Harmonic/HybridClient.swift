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
enum HybridClient {
    static func diagnose(featuresJSON: String, systemPrompt: String, cloudModel: String) async -> (text: String?, source: String) {
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
