import SwiftUI
import HarmonicDSP

extension Color {
    /// 0xRRGGBB in sRGB.
    init(harmonicHex hex: UInt32) {
        self.init(
            .sRGB,
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255,
            opacity: 1
        )
    }
}

/// Same palette as the Android app. The app always runs in dark mode on `ink`.
/// Text pairs used here are all at least 4.5:1 (Mist on Panel is about 8.3:1;
/// Ink on the verdict colors is 6.6:1 or more).
enum Palette {
    static let ink = Color(harmonicHex: 0x0E1412)
    static let panel = Color(harmonicHex: 0x17211E)
    static let line = Color(harmonicHex: 0x2C4038)
    static let phosphor = Color(harmonicHex: 0xD6FF4A)
    static let mist = Color(harmonicHex: 0xA9BDB3)
    static let paper = Color(harmonicHex: 0xE7F2EB)

    static let verdictA = Color(harmonicHex: 0x3DDC97)
    static let verdictB = phosphor
    static let verdictC = Color(harmonicHex: 0xFFB020)
    static let verdictD = Color(harmonicHex: 0xFF6A4A)

    static let cloud = Color(harmonicHex: 0x7DD3FC)
    static let cloudFill = Color(harmonicHex: 0x163445)
    static let offlineText = Color(harmonicHex: 0xE8D48A)
    static let offlineFill = Color(harmonicHex: 0x2C2A1A)
}

// MARK: - Plain-language wording (matches the Android result screen)

enum PlainWords {
    static func verdictTitle(_ zone: String) -> String {
        switch zone {
        case "A": return "Running well"
        case "C": return "Fix soon"
        case "D": return "Stop and fix"
        default: return "OK for now"
        }
    }

    static func verdictColor(_ zone: String) -> Color {
        switch zone {
        case "A": return Palette.verdictA
        case "C": return Palette.verdictC
        case "D": return Palette.verdictD
        default: return Palette.verdictB
        }
    }

    static func isoLabel(_ zone: String) -> String {
        switch zone {
        case "A": return "good"
        case "B": return "acceptable"
        case "C": return "unsatisfactory"
        case "D": return "unacceptable"
        default: return "?"
        }
    }

    static let faultName: [String: String] = [
        "healthy": "No problem found",
        "imbalance": "Out of balance",
        "misalignment": "Shafts not lined up",
        "looseness": "Something is loose",
        "bearing_wear": "Worn bearing",
        "unknown": "Unusual shaking",
    ]

    static let faultMeaning: [String: String] = [
        "healthy": "The shaking is normal for this kind of machine.",
        "imbalance": "One side of the spinning part is heavier than the other. Dirt build-up, a damaged blade or a lost weight can cause it.",
        "misalignment": "The motor shaft and the machine it drives are not in a straight line.",
        "looseness": "Mounting bolts, the base or a worn fit are letting parts move.",
        "bearing_wear": "A bearing is starting to wear out.",
        "unknown": "The shaking does not match a common problem.",
    ]

    static let zoneAction: [String: String] = [
        "A": "Keep using it. Check again in a few months.",
        "B": "Keep using it, and check again next month.",
        "C": "Repair it soon, before it gets worse.",
        "D": "Stop the machine and repair it before using it again.",
    ]

    static let faultTip: [String: String] = [
        "imbalance": "Clean the spinning part and look for a damaged blade or a lost weight.",
        "misalignment": "Check that the motor and the machine it drives sit in a straight line, and that the coupling isn't worn.",
        "looseness": "Tighten the mounting bolts and check the base for cracks.",
        "bearing_wear": "Listen for grinding and feel the bearings for heat.",
        "unknown": "Check again with the phone in a different spot.",
    ]

    /// Maps whatever the model or DSP returned onto one of the six plain fault keys.
    static func faultKey(_ raw: String) -> String {
        if faultName[raw] != nil { return raw }
        let family = faultFamily(raw)
        return faultName[family] != nil ? family : "unknown"
    }

    /// The offline check writes actions for technicians, so on that path (or when the model
    /// leaves it blank) build a plain one from the zone and the fault, like Android.
    static func action(zone: String, fault: String, source: String, fromModel: String) -> String {
        let trimmed = fromModel.trimmingCharacters(in: .whitespacesAndNewlines)
        if source != "physics_fallback" && !trimmed.isEmpty { return trimmed }
        let zoneText = zoneAction[zone] ?? "Keep using it, and check again next month."
        guard zone != "A", let tip = faultTip[fault] else { return zoneText }
        return tip + " " + zoneText
    }

    static func sureness(_ confidence: Double) -> String {
        if confidence >= 0.75 { return "Sure" }
        if confidence >= 0.5 { return "Fairly sure" }
        return "Not sure. Check again."
    }
}

// MARK: - Controls

struct PrimaryButtonStyle: ButtonStyle {
    func makeBody(configuration: ButtonStyleConfiguration) -> some View {
        PrimaryButtonBody(configuration: configuration)
    }
}

struct PrimaryButtonBody: View {
    let configuration: ButtonStyleConfiguration
    @Environment(\.isEnabled) private var isEnabled

    var body: some View {
        configuration.label
            .font(.headline)
            .foregroundStyle(isEnabled ? Palette.ink : Palette.mist)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 14)
            .background(isEnabled ? Palette.phosphor : Palette.line, in: RoundedRectangle(cornerRadius: 12))
            .opacity(configuration.isPressed ? 0.8 : 1)
    }
}

struct SecondaryButtonStyle: ButtonStyle {
    func makeBody(configuration: ButtonStyleConfiguration) -> some View {
        SecondaryButtonBody(configuration: configuration)
    }
}

struct SecondaryButtonBody: View {
    let configuration: ButtonStyleConfiguration
    @Environment(\.isEnabled) private var isEnabled

    var body: some View {
        configuration.label
            .font(.headline)
            .foregroundStyle(isEnabled ? Palette.paper : Palette.mist)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 14)
            .background(Palette.panel, in: RoundedRectangle(cornerRadius: 12))
            .overlay {
                RoundedRectangle(cornerRadius: 12).stroke(Palette.line, lineWidth: 1)
            }
            .opacity(configuration.isPressed ? 0.8 : 1)
    }
}

struct FieldStyle: ViewModifier {
    func body(content: Content) -> some View {
        content
            .foregroundStyle(Palette.paper)
            .tint(Palette.phosphor)
            .padding(.horizontal, 14)
            .padding(.vertical, 12)
            .background(Palette.panel, in: RoundedRectangle(cornerRadius: 12))
            .overlay {
                RoundedRectangle(cornerRadius: 12).stroke(Palette.line, lineWidth: 1)
            }
    }
}

struct SourceBadge: View {
    let source: String

    var body: some View {
        Text(label)
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(foreground)
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .background(fill, in: Capsule())
    }

    private var label: String {
        switch source {
        case "on_device": return "AI on this phone"
        case "cloud", "cloud_refinement": return "AI in the cloud"
        default: return "Offline check"
        }
    }

    private var foreground: Color {
        switch source {
        case "on_device": return Palette.phosphor
        case "cloud", "cloud_refinement": return Palette.cloud
        default: return Palette.offlineText
        }
    }

    private var fill: Color {
        switch source {
        case "on_device": return Palette.phosphor.opacity(0.16)
        case "cloud", "cloud_refinement": return Palette.cloudFill
        default: return Palette.offlineFill
        }
    }
}
