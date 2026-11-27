import SwiftUI
import FirebaseCore
import FirebaseAppCheck

@main
struct HarmonicApp: App {
    init() {
        if Bundle.main.path(forResource: "GoogleService-Info", ofType: "plist") != nil {
            #if DEBUG
            AppCheck.setAppCheckProviderFactory(AppCheckDebugProviderFactory())
            #endif
            FirebaseApp.configure()
            FirebaseGate.ready = true
        }
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                // The palette is dark-only (Ink background), so pin the scheme; otherwise
                // system controls and default text follow light mode and lose contrast.
                .preferredColorScheme(.dark)
        }
    }
}

enum FirebaseGate {
    static var ready = false
}
