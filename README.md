# Harmonic — A vibration analyst in every pocket

**#BuildHybridAI 2026 · Google · Firebase AI Logic · Devpost**

> Turn the phones your techs already carry into a synchronized multi-channel vibration analyzer
> that works in the basement — offline, on-device, and accurate.

---

## What it does

Harmonic places 2–3 smartphones on rotating machinery (fans, pumps, motors, compressors),
synchronizes their clocks by tapping them together, and diagnoses mechanical faults —
**imbalance, misalignment, looseness, resonance** — the way a certified vibration analyst does.

**The hybrid AI pipeline:**

| Step | What happens | Where |
|------|--------------|-------|
| 1. DSP | Spectrum (Welch), orders, velocity RMS, ISO severity zone, cross-phone phase & coherence | On device |
| 2. On-device hypothesis | Gemini Nano 4 (Android/Chrome) or Apple FM (iOS) → structured JSON fault hypothesis, offline | On device |
| 3. Physics gate | Deterministic checks reject any hypothesis that contradicts the measured signal | On device |
| 4. Active diagnosis | If top-2 within Δp < 0.2 → ask for the one measurement that best separates them | On device |
| 5. Cloud refinement | A fast cloud model (`model_cloud_refine`, default `gemini-3.5-flash-lite`) re-checks ambiguous or zone C/D cases; every AI call is capped at 15 s, after which the first answer stands | Cloud (Firebase AI Logic) |

Multi-platform: **Android** (Gemini Nano 4 · image+JSON) · **iOS** (Apple FM · text+tools+multi-turn) · **Web/Chrome** (Gemini Nano · fleet dashboard).

---

## Why multi-phone is essential

Fault discrimination depends on **relative phase between bearings** — which requires ≥ 2
synchronized sensors. Static imbalance shows in-phase motion (≈ 0°) across bearings; couple
imbalance shows ≈ 180°; misalignment shows strong axial vibration and 180° across the coupling.
No single-channel analyzer can make these distinctions reliably.

---

## How we used Firebase AI Logic

| Platform | Inference mode | Model | Input |
|----------|---------------|-------|-------|
| Android  | `OnDeviceConfig(mode: PREFER_ON_DEVICE)` via `firebase-ai` 18.2.0 + `firebase-ai-ondevice` 16.0.0-beta08 | Gemini Nano, cloud fallback `gemini-3.5-flash-lite` | Feature JSON text |
| iOS      | `LanguageModelSession` on `SystemLanguageModel`, then `geminiLanguageModel` | Apple Foundation Models, cloud fallback through Firebase AI Logic | Feature JSON text |
| Cloud refinement (both apps) | Android `ONLY_IN_CLOUD`; iOS `geminiLanguageModel` | `gemini-3.5-flash-lite` (Remote Config `model_cloud_refine`) | Features plus the on-device hypothesis; 15 s cap |

Firebase Apple SDK 13 removed `HybridModel`. The iOS app implements the same prefer-on-device-then-cloud behavior with the current Foundation Models adapter (`FirebaseAILogic`). The result screen names the path that answered: on device, cloud, cloud refinement, or physics fallback.

All AI inference goes through **Firebase AI Logic SDKs**. No other model API is called anywhere.
Model names, thresholds, and prompt versions are served by **Remote Config** so we can tune
without a release. **App Check** debug tokens are registered from the log line the debug build prints. Steps are in [docs/FIREBASE_SETUP.md](docs/FIREBASE_SETUP.md).

---

## Repository structure

```
harmonic/
├─ LICENSE                         Apache-2.0
├─ README.md
├─ docs/FIREBASE_SETUP.md          beginner console steps (no project is committed)
├─ prompts/
│  ├─ v1_first_pass.md             system prompt, version key "v1"
│  └─ hypothesis_schema.json
├─ reference/harmonic_dsp/         Python DSP, source of truth
├─ reference/tests/                pytest (135 tests)
├─ reference/scripts/generate_golden.py
├─ reference/golden/vectors.json   inputs and expected outputs
├─ firebase/                       rules, indexes, Remote Config, hosting placeholder
├─ android/                        Kotlin DSP module + Jetpack Compose app
└─ ios/
   ├─ Packages/HarmonicDSP/        Swift package + XCTest against the golden file
   └─ Harmonic.xcodeproj           SwiftUI app, bundle id com.harmonic.app
```

The web dashboard from the original outline is not in this slice. Hosting in `firebase/` is a one-page placeholder so `firebase deploy` has a `public` directory.

---

## Benchmarks

*(Updated as results come in — final numbers by Nov 29)*

| Metric | Value |
|--------|-------|
| Diagnosis accuracy — hybrid cascade | TBD |
| Diagnosis accuracy — on-device only | TBD |
| Diagnosis accuracy — cloud only | TBD |
| Sessions resolved fully on-device (%) | TBD |
| Cloud tokens saved by warm start (%) | TBD |
| Hallucinated diagnoses caught by physics gate | TBD |
| Sync offset error (ms) | TBD |
| Phase error at 30 Hz with 1 ms sync error | ≤ 10.8° (by design) |

Dataset: box-fan rig (N runs) + misalignment rig (N runs) + MaFaulDa downsampled.
Prompt version: v1. See `eval/results/` for full ablation tables.

---

## What is implemented

### Android (builds and runs)

- **Simple farmer-facing UI.** One big "check this machine" flow, plain-language results, and an optional "Use more phones" path that hosts a session.
- **Multi-phone sessions.** Host a session or join with a 6-character code (QR on Android and iOS). The session document is platform-neutral, so a Pixel and an iPhone can sit in the same session.
- **Machine history.** Each machine keeps its past readings on the phone, and the result screen says how it compares to last time.
- **"Why do we think so?"** A result view that explains, in plain words, which measurements led to the diagnosis.
- **Hybrid AI with three paths.** On-device Gemini Nano first, cloud Gemini through Firebase AI Logic when needed (plus a cloud refinement for zones C/D and close calls), and an offline physics fallback when neither model answers. The result screen names the path that answered.

The pipeline underneath: record the accelerometer (`SENSOR_DELAY_FASTEST`), build the feature JSON on device (gravity frame, resample, Welch, orders, velocity RMS, ISO zone, cross-phone phase when a second recording is present), ask the model, then let the physics gate overwrite the zone and reject claims that contradict the spectrum.

**Record this phone only** runs the DSP with no Firebase project. The diagnosis is then the physics fallback.

### iOS (code exists, not yet compiling)

The SwiftUI app and the `HarmonicDSP` Swift package are in `ios/`. It targets Xcode 27 / iOS 27 (Foundation Models + Firebase AI Logic) and is being fixed to compile; it has not been built on a Mac in this checkout. It uses the same Firestore session layout as Android.

### Tests

- Python reference DSP: 135 pytest tests in `reference/tests/`.
- Kotlin: `:dsp` unit tests (including the golden-vector check against the Python output) and app unit tests (machine-history comparison and the "Why" view).

## Setup

Console steps, debug-token registration, and the deploy command are in [docs/FIREBASE_SETUP.md](docs/FIREBASE_SETUP.md). Package name and bundle id are both `com.harmonic.app`.

### Android (verified on this machine)

Needs JDK 17+ (Android Studio’s JBR 21 works) and Android SDK 36.

```powershell
cd android
.\gradlew.bat :dsp:test :app:testDebugUnitTest assembleDebug
```

`google-services.json` is optional for that command. Put it at `android/app/google-services.json` before you want Auth, Firestore, or Gemini. Install `android/app/build/outputs/apk/debug/app-debug.apk` on a phone.

### iOS (not compiled yet)

On a Mac with Xcode 27:

```bash
cd ios/Packages/HarmonicDSP
swift test
```

Then open `ios/Harmonic.xcodeproj`, let Swift Package Manager resolve Firebase 13.2+, set your team, drop in `GoogleService-Info.plist`, and run on an iPhone with Apple Intelligence. Cloud Gemini through Foundation Models needs iOS 27. The on-device system model is used when it is available; otherwise the app falls back to the cloud model, then to the physics screen.

### Python DSP reference
```bash
pip install -e "reference/[dev]"
pytest reference/tests/ -v
python reference/scripts/generate_golden.py
```

`generate_golden.py` rewrites `reference/golden/vectors.json` and the copies the Kotlin and Swift tests load. Do not hand-edit those copies.

### Pre-existing code / disclosures
- Firebase starter templates and codelabs used as reference for SDK integration patterns
- Python NumPy/SciPy for the reference DSP. The Kotlin and Swift ports are checked against golden vectors generated from that package
- Vite + React scaffold was planned for a web app and is not part of this mobile slice
- All application logic, DSP algorithms, prompts, and architecture are original work created
  during the submission period (Nov 11 – Nov 30, 2026)
- AI coding assistants (Claude, Cursor) used for code generation under team supervision

---

## Limitations

- **No bearing tones.** Phones sample at ~100 Hz (iPhone) to ~200 Hz (Android), so Harmonic sees low-frequency faults (imbalance, misalignment, looseness) up to a few thousand RPM. High-frequency bearing defect tones are out of reach of a phone accelerometer.
- **Placement matters.** Each phone must lie flat on the machine with its top edge along the shaft. The axis labels (horizontal / vertical / axial) assume that orientation.
- **Band-limited velocity.** Velocity RMS is computed up to fs/2 and labeled that way.
- **Anonymous sessions.** Phones sign in with Firebase Anonymous Auth. Anyone with a 6-character code can join a session while it is in the lobby; there are no named accounts.
- **Screening, not certification.** Harmonic is triage, not a replacement for a certified analyst (ISO 18436) on safety-critical assets.

---

## License

Apache-2.0 — see [LICENSE](LICENSE).
