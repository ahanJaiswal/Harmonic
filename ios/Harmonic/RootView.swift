import SwiftUI
import CoreImage.CIFilterBuiltins

struct RootView: View {
    @StateObject private var model = SessionModel()

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Text("HARMONIC")
                    .font(.system(size: 22, weight: .black, design: .default))
                    .tracking(3)
                    .foregroundStyle(Color(red: 0.84, green: 1, blue: 0.29))
                Text("Multi-phone vibration analyzer")
                    .foregroundStyle(.secondary)
                Text(model.status).font(.footnote).foregroundStyle(.secondary)
                if let error = model.error {
                    Text(error).foregroundStyle(.red)
                }
                switch model.screen {
                case "join": join
                case "lobby": lobby
                case "record": record
                case "result": result
                default: home
                }
            }
            .padding(20)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .background(Color(red: 0.05, green: 0.08, blue: 0.07))
        .task { await model.prepare() }
    }

    private var home: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Screen up. Top edge toward the drive end. Tap the phones together at the start and again near the end.")
            Button("Host a session") { Task { await model.host() } }
                .buttonStyle(.borderedProminent)
                .disabled(!FirebaseGate.ready || model.busy)
            Button("Join with a code") { model.screen = "join" }
                .disabled(!FirebaseGate.ready || model.busy)
            Button("Record this phone only") { Task { await model.recordLocal() } }
            if !FirebaseGate.ready {
                Text("Add GoogleService-Info.plist to enable sessions and Gemini. A single-phone reading still runs the DSP.")
                    .font(.footnote).foregroundStyle(.secondary)
            }
        }
    }

    private var join: some View {
        VStack(alignment: .leading, spacing: 10) {
            TextField("Session code", text: $model.joinText)
                .textInputAutocapitalization(.characters)
                .textFieldStyle(.roundedBorder)
            Button("Join") { Task { await model.join() } }.buttonStyle(.borderedProminent)
            Button("Back") { model.screen = "home" }
        }
    }

    private var lobby: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(model.code).font(.system(size: 36, weight: .black)).tracking(6)
                .foregroundStyle(Color(red: 0.84, green: 1, blue: 0.29))
            if let image = qr(model.code) {
                Image(uiImage: image).interpolation(.none).resizable().frame(width: 180, height: 180)
            }
            Text("Android and iPhone can share this session.")
                .font(.footnote).foregroundStyle(.secondary)
            ForEach(model.members, id: \.self) { name in Text("• \(name)") }
            TextField("RPM", text: $model.rpm).textFieldStyle(.roundedBorder).keyboardType(.numberPad)
            Button("Start recording") { Task { await model.startGroup() } }.buttonStyle(.borderedProminent)
        }
    }

    private var record: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Recording").font(.title.bold())
            ProgressView(value: model.elapsed, total: Double(model.seconds))
            Text("\(Int(model.elapsed)) / \(model.seconds) s    |a| = \(String(format: "%.2f", model.magnitude)) m/s²")
            Text(model.elapsed < 2.5 ? "Tap the phones together now." : (model.elapsed > Double(model.seconds) - 2.5 ? "Tap them together once more." : "Hold still on the bearing housing."))
        }
    }

    private var result: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .firstTextBaseline) {
                Text(model.zone).font(.system(size: 56, weight: .black))
                    .foregroundStyle(zoneColor(model.zone))
                VStack(alignment: .leading) {
                    Text(model.fault.replacingOccurrences(of: "_", with: " ")).font(.title2.bold())
                    Text("Confidence \(Int(model.confidence * 100))%")
                    Text(sourceLabel(model.source)).font(.footnote)
                        .foregroundStyle(Color(red: 0.84, green: 1, blue: 0.29))
                }
            }
            Text(model.summary)
            Text(model.action)
            Button("New reading") { model.screen = "home" }.buttonStyle(.borderedProminent)
        }
    }

    private func sourceLabel(_ source: String) -> String {
        switch source {
        case "on_device": return "Answered on device"
        case "cloud": return "Answered in the cloud"
        case "cloud_refinement": return "Cloud refinement"
        default: return "Physics fallback — model unavailable"
        }
    }

    private func zoneColor(_ zone: String) -> Color {
        switch zone {
        case "A": return Color(red: 0.24, green: 0.86, blue: 0.59)
        case "C": return Color(red: 1, green: 0.69, blue: 0.13)
        case "D": return Color(red: 1, green: 0.35, blue: 0.21)
        default: return Color(red: 0.84, green: 1, blue: 0.29)
        }
    }

    private func qr(_ text: String) -> UIImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage else { return nil }
        let scaled = output.transformed(by: CGAffineTransform(scaleX: 8, y: 8))
        let context = CIContext()
        guard let cg = context.createCGImage(scaled, from: scaled.extent) else { return nil }
        return UIImage(cgImage: cg)
    }
}
