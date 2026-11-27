import SwiftUI
import CoreImage.CIFilterBuiltins

struct RootView: View {
    @StateObject private var model = SessionModel()
    @State private var showDetails = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Text("HARMONIC")
                    .font(.system(size: 28, weight: .black, design: .default))
                    .tracking(3)
                    .foregroundStyle(Palette.phosphor)
                    .frame(maxWidth: .infinity, alignment: .center)
                Text("Multi-phone vibration analyzer")
                    .foregroundStyle(Palette.mist)
                    .frame(maxWidth: .infinity, alignment: .center)
                Text(model.status).font(.footnote).foregroundStyle(Palette.mist)
                if let error = model.error {
                    Text(error).foregroundStyle(Palette.verdictD)
                }
                switch model.screen {
                case "join": join
                case "lobby": lobby
                case "record": record
                case "result": result
                default: home
                }
            }
            .foregroundStyle(Palette.paper)
            .padding(20)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .background(Palette.ink.ignoresSafeArea())
        .tint(Palette.phosphor)
        .task { await model.prepare() }
    }

    private var home: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Screen up. Top edge toward the drive end. Tap the phones together at the start and again near the end.")
            Button("Host a session") { Task { await model.host() } }
                .buttonStyle(PrimaryButtonStyle())
                .disabled(!FirebaseGate.ready || model.busy)
            Button("Join with a code") { model.screen = "join" }
                .buttonStyle(SecondaryButtonStyle())
                .disabled(!FirebaseGate.ready || model.busy)
            Button("Record this phone only") { Task { await model.recordLocal() } }
                .buttonStyle(SecondaryButtonStyle())
            if !FirebaseGate.ready {
                Text("Add GoogleService-Info.plist to enable sessions and Gemini. A single-phone reading still runs the DSP.")
                    .font(.footnote).foregroundStyle(Palette.mist)
            }
        }
    }

    private var join: some View {
        VStack(alignment: .leading, spacing: 12) {
            TextField("", text: $model.joinText, prompt: Text("Session code").foregroundColor(Palette.mist))
                .textInputAutocapitalization(.characters)
                .autocorrectionDisabled()
                .modifier(FieldStyle())
            Button("Join") { Task { await model.join() } }
                .buttonStyle(PrimaryButtonStyle())
            Button("Back") { model.screen = "home" }
                .buttonStyle(SecondaryButtonStyle())
        }
    }

    private var lobby: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(model.code).font(.system(size: 36, weight: .black)).tracking(6)
                .foregroundStyle(Palette.phosphor)
            if let image = qr(model.code) {
                Image(uiImage: image).interpolation(.none).resizable()
                    .frame(width: 180, height: 180)
                    .padding(8)
                    .background(Color.white, in: RoundedRectangle(cornerRadius: 8))
            }
            Text("Android and iPhone can share this session.")
                .font(.footnote).foregroundStyle(Palette.mist)
            ForEach(model.members, id: \.self) { name in Text("• \(name)") }
            TextField("", text: $model.rpm, prompt: Text("RPM").foregroundColor(Palette.mist))
                .keyboardType(.numberPad)
                .modifier(FieldStyle())
            Button("Start recording") { Task { await model.startGroup() } }
                .buttonStyle(PrimaryButtonStyle())
        }
    }

    private var record: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Recording").font(.title.bold())
            ProgressView(value: model.elapsed, total: Double(model.seconds))
                .tint(Palette.phosphor)
            Text("\(max(0, model.seconds - Int(model.elapsed))) seconds left")
                .foregroundStyle(Palette.mist)
            Text(model.elapsed < 2.5 ? "Tap the phones together now." : (model.elapsed > Double(model.seconds) - 2.5 ? "Tap them together once more." : "Hold still on the bearing housing."))
                .font(.title3.bold())
        }
    }

    private var result: some View {
        let fault = PlainWords.faultKey(model.fault)
        let action = PlainWords.action(zone: model.zone, fault: fault, source: model.source, fromModel: model.action)
        return VStack(alignment: .leading, spacing: 16) {
            VStack(alignment: .leading, spacing: 4) {
                Text(PlainWords.verdictTitle(model.zone))
                    .font(.system(size: 36, weight: .black))
                Text(PlainWords.faultName[fault] ?? "Unusual shaking")
                    .font(.title3.bold())
            }
            .foregroundStyle(Palette.ink)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 20)
            .padding(.vertical, 22)
            .background(PlainWords.verdictColor(model.zone), in: RoundedRectangle(cornerRadius: 18))

            Text(PlainWords.faultMeaning[fault] ?? "")
                .foregroundStyle(Palette.paper)

            VStack(alignment: .leading, spacing: 4) {
                Text("What to do")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Palette.mist)
                Text(action)
                    .font(.title3.weight(.semibold))
                    .foregroundStyle(Palette.paper)
            }

            HStack(spacing: 10) {
                Text(PlainWords.sureness(model.confidence))
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Palette.paper)
                SourceBadge(source: model.source)
            }

            Button("Check again") {
                showDetails = false
                model.screen = "home"
            }
            .buttonStyle(PrimaryButtonStyle())

            Button(showDetails ? "Hide details" : "Show details") { showDetails.toggle() }
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(Palette.phosphor)
                .frame(maxWidth: .infinity)

            if showDetails {
                details
            }
        }
    }

    private var details: some View {
        let iso = "\(model.zone) (\(PlainWords.isoLabel(model.zone)))"
        return VStack(alignment: .leading, spacing: 8) {
            detailLine("ISO 10816 zone", iso)
            detailLine("Confidence", "\(Int(model.confidence * 100))%")
            detailLine("Fault code", model.fault.replacingOccurrences(of: "_", with: " "))
            if !model.summary.isEmpty {
                Text(model.summary).font(.subheadline).foregroundStyle(Palette.paper)
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Palette.panel, in: RoundedRectangle(cornerRadius: 14))
    }

    private func detailLine(_ label: String, _ value: String) -> some View {
        HStack {
            Text(label).font(.subheadline).foregroundStyle(Palette.mist)
            Spacer()
            Text(value).font(.subheadline.weight(.semibold)).foregroundStyle(Palette.paper)
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
