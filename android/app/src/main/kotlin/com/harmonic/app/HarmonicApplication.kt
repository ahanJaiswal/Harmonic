package com.harmonic.app

import android.app.Application
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory

/**
 * Firebase is optional at build time: without google-services.json the app still
 * records and runs the on-device DSP, and says so on the result screen.
 */
object FirebaseGate {
    var ready: Boolean = false
        private set

    fun install(app: Application) {
        try {
            if (FirebaseApp.getApps(app).isEmpty()) {
                FirebaseApp.initializeApp(app) ?: run {
                    Log.i(TAG, "No Firebase config. DSP-only mode.")
                    ready = false
                    return
                }
            }
            val check = FirebaseAppCheck.getInstance()
            if (BuildConfig.DEBUG) {
                check.installAppCheckProviderFactory(DebugAppCheckProviderFactory.getInstance())
            } else {
                check.installAppCheckProviderFactory(PlayIntegrityAppCheckProviderFactory.getInstance())
            }
            ready = true
            Log.i(TAG, "Firebase ready. App Check debug=${BuildConfig.DEBUG}")
        } catch (t: Throwable) {
            ready = false
            Log.w(TAG, "Firebase init failed; continuing without it", t)
        }
    }

    private const val TAG = "Harmonic"
}

class HarmonicApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        FirebaseGate.install(this)
    }
}
