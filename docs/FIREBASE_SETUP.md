# Firebase setup for Harmonic

There is no Firebase project in the repo. The apps run the on-device DSP without one. Sessions, Anonymous Auth, Remote Config, and Gemini need the project below. Never commit `google-services.json`, `GoogleService-Info.plist`, or a service-account key. Those paths are gitignored.

Do this in a desktop browser. The clicks match the Firebase console as of November 2026.

## 1. Create the project

1. Open [Firebase console](https://console.firebase.google.com/) and sign in with the account that will own the hackathon project.
2. Click **Create a project** (or **Add project**).
3. Name it, for example `harmonic-demo`. Google Analytics is not required for AI Logic. You can leave it off.
4. Wait until the project overview opens.

## 2. Turn on Firebase AI Logic

1. In the left nav, open **AI Services > AI Logic** (the product was formerly “Vertex AI in Firebase”).
2. Click **Get started**.
3. When asked for a Gemini API provider, choose **Gemini Developer API**. That path does not need billing to start. You can add the Agent Platform Gemini API later if you want.
4. Finish the workflow. It enables the Gemini API and turns on **App Check** for Firebase AI Logic.
5. Confirm enforcement: **Security > App Check > APIs**. The row **Firebase AI Logic** must say enforced. If it says unenforced, open the row and enforce it. Cloud Gemini calls from the phones fail until this is on and a debug token is registered.

The apps call these models, which are also the Remote Config defaults:

| Remote Config key | Value | Used for |
| --- | --- | --- |
| `model_cloud_fallback` | `gemini-3.5-flash-lite` | Android `PREFER_ON_DEVICE` cloud fallback, and the iOS cloud model |
| `model_cloud_refine` | `gemini-3.5-flash-lite` | Second pass when the case is ambiguous or ISO zone C/D. Keep this a fast model: the app gives every AI call 15 s |
| `prompt_version` | `v1` | Loads `prompts/v1_first_pass.md` |

## 3. Register the Android app

1. Project overview → **Add app** → Android.
2. Android package name: `com.harmonic.app`. The app id in `android/app/build.gradle.kts` is the same string. Nickname can be `Harmonic Android`.
3. Download **google-services.json**.
4. Put that file at `android/app/google-services.json`. Do not commit it. The Gradle plugin is applied only when the file is present, so `assembleDebug` still works before you add it.
5. You do not need the Google services Gradle snippet from the console. The repo already depends on Firebase BoM `35.2.0`, `firebase-ai` `18.2.0`, and `firebase-ai-ondevice` `16.0.0-beta08`.

## 4. Register the iOS app

1. Project overview → **Add app** → iOS.
2. Bundle ID: `com.harmonic.app`. That is `PRODUCT_BUNDLE_IDENTIFIER` in `ios/Harmonic.xcodeproj`.
3. Download **GoogleService-Info.plist**.
4. Drag it into the `ios/Harmonic` group in Xcode and check **Copy items if needed** and the Harmonic target. Do not commit it.
5. In Xcode: **File > Add Package Dependencies**, if the packages are not already resolved:
   - `https://github.com/firebase/firebase-ios-sdk` from version **13.2.0**
   - Add products **FirebaseAILogic**, **FirebaseCore**, **FirebaseAuth**, **FirebaseFirestore**, **FirebaseRemoteConfig**, **FirebaseAppCheck**
   - The local package `ios/Packages/HarmonicDSP` is already referenced

Firebase Apple SDK 13 removed the older `HybridModel` / `FirebaseAI.SystemLanguageModel` types. The iOS app uses Apple’s `LanguageModelSession`: on-device `SystemLanguageModel` first, then `FirebaseAI.geminiLanguageModel(name:)` for cloud. That cloud adapter needs **Xcode 27** and **iOS 27** at runtime. On-device Apple Intelligence works on iOS 26. Open `ios/Harmonic.xcodeproj` on the Mac.

## 5. Anonymous Auth, Firestore, Storage

1. **Build > Authentication > Sign-in method > Anonymous > Enable**. Both apps call `signInAnonymously()` before they touch Firestore.
2. **Build > Firestore Database > Create database**. Start in **production mode**. The repo rules replace the default. Pick any region.
3. Storage is not used (recordings sit in Firestore), and `firebase/storage.rules` denies all access. You don't need a bucket or the Blaze plan.

## 6. Deploy rules, indexes, and Remote Config

Install the Firebase CLI (`npm install -g firebase-tools`) and log in (`firebase login`). From the repo:

```powershell
cd firebase
firebase use --add
firebase deploy --only firestore,storage,remoteconfig
```

`firebase/firebase.json` is the CLI project file. Paths inside it are relative to `firebase/`, not the repo root. Hosting is a one-page placeholder at `firebase/public` (there is no `web/` app in this slice). Deploy it only if you want that page:

```powershell
firebase deploy --only hosting
```

Remote Config parameters live in `firebase/remoteconfig.template.json`. After deploy, check **Remote Config** in the console and confirm `prompt_version` is `v1`.

## 7. Register App Check debug tokens

Debug builds use the App Check debug provider. A debug build cannot call Gemini until its token is on the allow list.

**Android**

1. Install the debug APK (`android\gradlew.bat assembleDebug`, then `adb install`) or run from Android Studio.
2. Logcat tag `DebugAppCheckProvider` prints a line like: `Enter this debug secret into the allow list in the Firebase Console for your project: 123a4567-b89c-12d3-e456-789012345678`.
3. Copy that UUID.

**iOS**

1. Run from Xcode on a simulator or an iPhone.
2. The Xcode console prints: `[AppCheckCore][I-GAC004001] App Check debug token: '…'`.
3. Copy that UUID. On Firebase Apple SDK 13.2, a simulator can also pick up a token created with `firebase appcheck:debugtokens:create` from `~/.config/configstore/firebase-appcheck.json`. Registering the token printed in the console is the path that always works.

**Console, both platforms**

1. **Security > App Check > Apps**.
2. Open the overflow menu on the app → **Manage debug tokens**.
3. Add the token. Repeat for the other platform. Each install can mint a different token. Add every phone you will demo (two Pixels, two iPhones) the first time each one logs a new token.

Do not ship a debug build or paste a debug token into the repo. Before a store release, register Play Integrity (Android) and App Attest (iOS) and stop using the debug provider. The Android release build already selects Play Integrity; the iOS debug build always uses the debug provider (`#if DEBUG`).

## 8. What to tap in the apps

1. Open Harmonic on each phone. The status line says whether Firebase initialized.
2. One phone: **Host a session**. Read the 6-character code or the QR.
3. Other phones: **Join with a code**. Android and iOS share one Firestore session.
4. Host sets machine type, nameplate RPM, ISO class, and duration, then **Start recording**.
5. When the screen says so, tap the phones together, hold them on the bearing, and tap again near the end.
6. The result shows the fault, ISO zone, confidence, the recommended action, and whether the answer came from **on device**, **the cloud**, **cloud refinement**, or the **physics fallback**.

A phone with no `google-services.json` / plist can still use **Record this phone only**. That path runs the DSP and the physics fallback and does not create a session.
