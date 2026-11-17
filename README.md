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
| 5. Cloud refinement | `gemini-3.8-flash` verifies on-device hypothesis against machine PDF manual + history → final diagnosis + work order | Cloud (Firebase AI Logic) |

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
| Android  | `PREFER_ON_DEVICE` | Gemini Nano 4 (AICore) | Spectrum PNG + features JSON |
| Web/Chrome | `PREFER_ON_DEVICE` | Gemini Nano (Prompt API) | Spectrum PNG + features JSON |
| iOS      | `HybridModel(primary: SystemLanguageModel)` | Apple Foundation Models | Features JSON (text) + tool calling |
| Cloud refinement (all platforms) | `ONLY_IN_CLOUD` | `gemini-3.8-flash` | Full features + PDF manual + history |

All AI inference goes through **Firebase AI Logic SDKs**. No other model API is called anywhere.
Model names, thresholds, and prompt versions are served by **Remote Config** so we can tune
without a release. **App Check** is enabled on all endpoints (debug tokens documented below).

---

## Repository structure

```
harmonic/
├─ LICENSE                     Apache-2.0
├─ README.md                   ← you are here
├─ .env.example                environment / App Check debug token template
├─ docs/
│  └─ HARMONIC_PROJECT.md      full project bible (spec, timeline, decisions)
├─ prompts/                    versioned prompts + JSON schemas
│  ├─ v1_first_pass.md
│  └─ hypothesis_schema.json
├─ reference/
│  ├─ harmonic_dsp/            Python DSP package (source of truth for all ports)
│  │  ├─ sync.py               tap-sync, drift correction
│  │  ├─ frames.py             gravity-based coordinate frame
│  │  ├─ resample.py           non-uniform → uniform time grid
│  │  ├─ spectrum.py           Welch PSD, amplitude spectrum
│  │  ├─ orders.py             order extraction & interpolation
│  │  ├─ velocity.py           acceleration → velocity, RMS, severity
│  │  ├─ severity.py           ISO 10816/20816 zone classification
│  │  ├─ phase.py              cross-phone phase & coherence
│  │  ├─ features.py           feature JSON assembler
│  │  └─ gate.py               physics gate (hallucination filter)
│  └─ tests/                   pytest suite + golden vector validation
├─ reference/golden/           synthetic inputs + expected feature JSON outputs
├─ eval/                       datasets, benchmark runners, results
├─ firebase/
│  ├─ firestore.rules
│  ├─ storage.rules
│  ├─ firebase.json
│  └─ remoteconfig.template.json
├─ web/                        Vite + React + TypeScript dashboard
├─ android/                    Kotlin + Jetpack Compose
└─ ios/                        SwiftUI + SPM
```

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

## Setup for judges

### Prerequisites
- Firebase project with App Check enabled (debug token: set `VITE_APP_CHECK_DEBUG_TOKEN` from `.env.example`)
- Chrome 139+ with `chrome://flags/#prompt-api-for-gemini-nano-multimodal-input` enabled, ≥ 22 GB free disk, ≥ 4 GB VRAM or 16 GB RAM
- Android: Gemini Nano 4 device (Pixel 10/11, Galaxy S25/S26, OnePlus 13/15)
- iOS: iPhone with Apple Intelligence enabled (iPhone 15 Pro or newer)

### Web / judge mode
```bash
cd web
npm install
cp ../.env.example .env.local   # fill in Firebase config + App Check debug token
npm run dev
```
Open `http://localhost:5173`. Use **Judge Mode** (top-right) to explore pre-recorded sessions
without needing physical phones. If your Chrome has no Nano model, calls fall back to cloud —
that's the hybrid design working as intended.

### Python DSP reference
```bash
pip install -e "reference/[dev]"
pytest reference/tests/ -v
```

### Pre-existing code / disclosures
- Firebase starter templates and codelabs used as reference for SDK integration patterns
- Python NumPy/SciPy/Matplotlib for reference DSP
- Vite + React scaffold for web app
- All application logic, DSP algorithms, prompts, and architecture are original work created
  during the submission period (Nov 11 – Nov 30, 2026)
- AI coding assistants (Claude, Cursor) used for code generation under team supervision

---

## Scope and honest limitations

- Phones sample at ~100 Hz (iPhone) to ~200 Hz (Android) → reliable for **low-frequency faults**
  up to a few thousand RPM. High-frequency bearing defect frequencies are **out of scope**.
- Velocity RMS is computed over a **band-limited** range (up to fs/2), labeled accordingly.
- Harmonic is **screening / triage**, not a replacement for a certified analyst (ISO 18436)
  on safety-critical assets.

---

## License

Apache-2.0 — see [LICENSE](LICENSE).
