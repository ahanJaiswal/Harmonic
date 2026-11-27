import Foundation

public let hannAmplCorr = sqrt((3.0 / 8.0) / (0.5 * 0.5))
public let standardOrders = [0.5, 1.0, 1.5, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0]

public func formatOrderKey(_ order: Double) -> String {
    let nearest = Int(order.rounded())
    let body: String
    if abs(order - Double(nearest)) < 1e-9 {
        body = String(nearest)
    } else {
        var s = String(format: "%.4f", order)
        while s.last == "0" { s.removeLast() }
        if s.last == "." { s.removeLast() }
        body = s
    }
    return body + "x"
}

public func frequencyResolution(fs: Double, nperseg: Int) -> Double { fs / Double(nperseg) }

public func welchPsd(signal: [Double], fs: Double, nperseg: Int? = nil, noverlap: Int? = nil) -> (freqs: [Double], psd: [Double]) {
    let nps = min(nperseg ?? SignalMath.autoNperseg(fs: fs, length: signal.count), signal.count)
    let nov = noverlap ?? nps / 2
    return SignalMath.welchPsd(signal, fs: fs, nperseg: nps, noverlap: nov)
}

public func welchAmplitude(signal: [Double], fs: Double, nperseg: Int? = nil, noverlap: Int? = nil) -> (freqs: [Double], amp: [Double]) {
    let spec = welchPsd(signal: signal, fs: fs, nperseg: nperseg, noverlap: noverlap)
    let df = spec.freqs.count > 1 ? spec.freqs[1] - spec.freqs[0] : 1
    let corr2 = hannAmplCorr * hannAmplCorr
    let amp = spec.psd.enumerated().map { k, p -> Double in
        let base = p * df * corr2
        return k == 0 ? sqrt(base) : sqrt(2 * base)
    }
    return (spec.freqs, amp)
}

public struct RunningSpeed {
    public var fRunHz: Double
    public var rpmRun: Double
    public var amp1x: Double
    public var confidence: Double
    public var fRunPrior: Double
}

private func parabolicInterp(_ freqs: [Double], _ amp: [Double], _ peak: Int) -> (Double, Double) {
    if peak <= 0 || peak >= amp.count - 1 { return (freqs[peak], amp[peak]) }
    let alpha = amp[peak - 1], beta = amp[peak], gamma = amp[peak + 1]
    let denom = alpha - 2 * beta + gamma
    if abs(denom) < 1e-15 { return (freqs[peak], beta) }
    let p = 0.5 * (alpha - gamma) / denom
    let df = freqs[1] - freqs[0]
    return (freqs[peak] + p * df, max(0, beta - 0.25 * (alpha - gamma) * p))
}

public func findRunningSpeed(freqs: [Double], amp: [Double], rpmNameplate: Double, searchFraction: Double = 0.20) -> RunningSpeed {
    let fPrior = rpmNameplate / 60
    let fLo = fPrior * (1 - searchFraction)
    let fHi = fPrior * (1 + searchFraction)
    let indices = freqs.indices.filter { freqs[$0] >= fLo && freqs[$0] <= fHi }
    if indices.count < 3 {
        return RunningSpeed(fRunHz: fPrior, rpmRun: rpmNameplate, amp1x: 0, confidence: 0, fRunPrior: fPrior)
    }
    var best = indices[0]
    var sum = 0.0
    for i in indices {
        sum += amp[i]
        if amp[i] > amp[best] { best = i }
    }
    let refined = parabolicInterp(freqs, amp, best)
    let noise = sum / Double(indices.count)
    let snr = refined.1 / (noise + 1e-12)
    let confidence = min(1, max(0, (snr - 1) / 9))
    return RunningSpeed(fRunHz: refined.0, rpmRun: refined.0 * 60, amp1x: refined.1, confidence: confidence, fRunPrior: fPrior)
}

public func extractOrders(freqs: [Double], amp: [Double], fRunHz: Double, orders: [Double] = standardOrders, searchHalfBins: Int = 3) -> [String: Double] {
    var result: [String: Double] = [:]
    for order in orders {
        let key = formatOrderKey(order)
        let fNominal = order * fRunHz
        if fNominal <= 0 || fNominal > freqs.last ?? 0 {
            result[key] = 0
            continue
        }
        var idx = 0
        var best = Double.greatestFiniteMagnitude
        for i in freqs.indices {
            let d = abs(freqs[i] - fNominal)
            if d < best { best = d; idx = i }
        }
        let lo = max(0, idx - searchHalfBins)
        let hi = min(amp.count - 1, idx + searchHalfBins)
        var local = lo
        for i in lo...hi where amp[i] > amp[local] { local = i }
        result[key] = parabolicInterp(freqs, amp, local).1
    }
    let floor = SignalMath.median(amp) * 2
    var count = 0.0
    for k in 1...10 where (result["\(k)x"] ?? 0) > floor { count += 1 }
    result["harmonics_count_above_floor"] = count
    return result
}

public func accelToVelocity(freqs: [Double], ampAccel: [Double], fLoHz: Double = 2) -> (freqs: [Double], amp: [Double]) {
    var f: [Double] = []
    var v: [Double] = []
    for i in freqs.indices where freqs[i] >= fLoHz {
        f.append(freqs[i])
        v.append(ampAccel[i] / (2 * .pi * freqs[i] + 1e-12) * 1000)
    }
    return (f, v)
}

public func overallVelocityRms(freqs: [Double], ampAccel: [Double], fLoHz: Double = 2) -> Double {
    let v = accelToVelocity(freqs: freqs, ampAccel: ampAccel, fLoHz: fLoHz).amp
    let sum = v.reduce(0) { $0 + $1 * $1 }
    return sqrt(0.5 / (hannAmplCorr * hannAmplCorr) * sum)
}

public func velocityAtOrder(freqs: [Double], ampAccel: [Double], fOrderHz: Double, fLoHz: Double = 2, searchHalfBins: Int = 2) -> Double {
    if fOrderHz < fLoHz || freqs.isEmpty { return 0 }
    var idx = 0
    var best = Double.greatestFiniteMagnitude
    for i in freqs.indices {
        let d = abs(freqs[i] - fOrderHz)
        if d < best { best = d; idx = i }
    }
    let lo = max(0, idx - searchHalfBins)
    let hi = min(ampAccel.count - 1, idx + searchHalfBins)
    var peak = lo
    for i in lo...hi where ampAccel[i] > ampAccel[peak] { peak = i }
    if freqs[peak] < fLoHz { return 0 }
    return ampAccel[peak] / (2 * .pi * freqs[peak] + 1e-12) * 1000
}

public struct ZoneThresholds { public var a, b, c: Double
    public init(a: Double, b: Double, c: Double) { self.a = a; self.b = b; self.c = c }
}

public let zoneThresholds: [String: ZoneThresholds] = [
    "class_I": ZoneThresholds(a: 0.71, b: 1.8, c: 4.5),
    "group2_rigid": ZoneThresholds(a: 1.4, b: 2.8, c: 4.5),
    "group2_flexible": ZoneThresholds(a: 2.3, b: 4.5, c: 7.1),
]

public func classifyZone(_ velocity: Double, machineClass: String = "class_I", thresholds: [String: ZoneThresholds]? = nil) -> String {
    let table = thresholds ?? zoneThresholds
    let t = table[machineClass] ?? zoneThresholds["class_I"]!
    if velocity <= t.a { return "A" }
    if velocity <= t.b { return "B" }
    if velocity <= t.c { return "C" }
    return "D"
}

public func zoneDescription(_ zone: String) -> String {
    switch zone {
    case "A": return "Good — newly commissioned or well-maintained"
    case "B": return "Acceptable for long-term continuous operation"
    case "C": return "Unsatisfactory — plan maintenance action"
    case "D": return "Damage likely — take immediate action"
    default: return "Unknown zone"
    }
}

public struct PhaseResult {
    public var phaseDeg: Double?
    public var coherence: Double
    public var fActualHz: Double
    public var trusted: Bool
}

/// Default Welch segment for coherence: about seven half-overlapped segments. With a single
/// segment, magnitude-squared coherence is 1.0 by construction, so phase from noise looks trusted.
public func coherenceNperseg(_ length: Int) -> Int {
    var n = 32
    while n * 2 <= length / 4 { n *= 2 }
    return max(1, min(n, length))
}

/// Running speed when the RPM is unknown: search 480 to 3600 RPM, then step down to half the
/// frequency when a strong peak sits there, so a dominant 2x is not taken for 1x.
public func searchRunningSpeed(freqs: [Double], amp: [Double], fs: Double) -> RunningSpeed {
    let hi = min(60.0, fs * 0.4)
    let lo = min(8.0, hi / 2)
    let center = (lo + hi) / 2
    let first = findRunningSpeed(freqs: freqs, amp: amp, rpmNameplate: center * 60, searchFraction: (hi - lo) / (hi + lo))
    let half = first.fRunHz / 2
    guard half >= lo, !freqs.isEmpty else { return first }
    let sub = findRunningSpeed(freqs: freqs, amp: amp, rpmNameplate: half * 60, searchFraction: 0.06)
    func peak(_ f: Double) -> Double {
        var best = 0
        for i in freqs.indices where abs(freqs[i] - f) < abs(freqs[best] - f) { best = i }
        return amp[best]
    }
    return peak(sub.fRunHz) >= 0.5 * peak(first.fRunHz) ? sub : first
}

/// fault_detail values allowed by prompts/hypothesis_schema.json.
public let knownFaultDetails: Set<String> = [
    "static_imbalance", "couple_imbalance", "angular_misalignment", "parallel_misalignment",
    "structural_looseness", "rotating_looseness", "bent_shaft", "resonance", "healthy", "unknown",
]

public func crossPhaseCoherence(signalA: [Double], signalB: [Double], fs: Double, fTargetHz: Double, nperseg: Int? = nil, noverlap: Int? = nil, coherenceMin: Double = 0.8) -> PhaseResult {
    let nps = min(nperseg ?? coherenceNperseg(signalA.count), signalA.count)
    let nov = noverlap ?? nps / 2
    let xy = SignalMath.welchCsd(signalA, signalB, fs: fs, nperseg: nps, noverlap: nov)
    let xx = SignalMath.welchPsd(signalA, fs: fs, nperseg: nps, noverlap: nov).psd
    let yy = SignalMath.welchPsd(signalB, fs: fs, nperseg: nps, noverlap: nov).psd
    var idx = 0
    var best = Double.greatestFiniteMagnitude
    for i in xy.freqs.indices {
        let d = abs(xy.freqs[i] - fTargetHz)
        if d < best { best = d; idx = i }
    }
    let denom = xx[idx] * yy[idx]
    if denom < 1e-30 { return PhaseResult(phaseDeg: nil, coherence: 0, fActualHz: xy.freqs[idx], trusted: false) }
    let mag2 = xy.re[idx] * xy.re[idx] + xy.im[idx] * xy.im[idx]
    let gamma = min(1, max(0, mag2 / denom))
    let phase = atan2(xy.im[idx], xy.re[idx]) * 180 / .pi
    return PhaseResult(phaseDeg: gamma >= coherenceMin ? phase : nil, coherence: gamma, fActualHz: xy.freqs[idx], trusted: gamma >= coherenceMin)
}

public func estimateGravity(_ accel: [[Double]]) -> [Double] {
    var g = [0.0, 0.0, 0.0]
    for row in accel { g[0] += row[0]; g[1] += row[1]; g[2] += row[2] }
    let n = Double(accel.count)
    return g.map { $0 / n }
}

/// About 15 degrees from vertical; below this the default -Y axis is too close to gravity.
public let uprightFallbackNorm = 0.25

public func projectAxes(_ accel: [[Double]], gVec: [Double], shaft: [Double]? = nil) -> (v: [Double], a: [Double], h: [Double]) {
    let gNorm = sqrt(gVec[0] * gVec[0] + gVec[1] * gVec[1] + gVec[2] * gVec[2]) + 1e-12
    let vHat = [-gVec[0] / gNorm, -gVec[1] / gNorm, -gVec[2] / gNorm]
    func orthogonalize(_ s: [Double]) -> ([Double], Double) {
        let dot = s[0] * vHat[0] + s[1] * vHat[1] + s[2] * vHat[2]
        let o = [s[0] - dot * vHat[0], s[1] - dot * vHat[1], s[2] - dot * vHat[2]]
        return (o, sqrt(o[0] * o[0] + o[1] * o[1] + o[2] * o[2]))
    }
    var (aOrth, aNorm) = orthogonalize(shaft ?? [0, -1, 0])
    if aNorm < (shaft == nil ? uprightFallbackNorm : 1e-6) {
        // Phone stands upright (or the given axis is vertical), so use the
        // screen normal (-Z) as the shaft direction rather than crashing.
        (aOrth, aNorm) = orthogonalize([0, 0, -1])
    }
    if aNorm < 1e-6 { (aOrth, aNorm) = orthogonalize([1, 0, 0]) }
    aOrth = aOrth.map { $0 / aNorm }
    var hRaw = [
        vHat[1] * aOrth[2] - vHat[2] * aOrth[1],
        vHat[2] * aOrth[0] - vHat[0] * aOrth[2],
        vHat[0] * aOrth[1] - vHat[1] * aOrth[0],
    ]
    let hNorm = sqrt(hRaw[0] * hRaw[0] + hRaw[1] * hRaw[1] + hRaw[2] * hRaw[2]) + 1e-12
    hRaw = hRaw.map { $0 / hNorm }
    var v = [Double](), a = [Double](), h = [Double]()
    for r in accel {
        v.append(r[0] * vHat[0] + r[1] * vHat[1] + r[2] * vHat[2])
        a.append(r[0] * aOrth[0] + r[1] * aOrth[1] + r[2] * aOrth[2])
        h.append(r[0] * hRaw[0] + r[1] * hRaw[1] + r[2] * hRaw[2])
    }
    return (v, a, h)
}

public func removeDc(_ signal: [Double]) -> [Double] {
    let m = SignalMath.mean(signal)
    return signal.map { $0 - m }
}

public func measureFs(_ timestamps: [Double]) -> Double {
    var dts: [Double] = []
    for i in 1..<timestamps.count { dts.append(timestamps[i] - timestamps[i - 1]) }
    return 1 / SignalMath.median(dts)
}

public func resampleUniform(signal: [Double], timestamps: [Double], fsTarget: Double? = nil) -> (signal: [Double], timestamps: [Double], fs: Double) {
    let source = measureFs(timestamps)
    let target = fsTarget ?? max(50, (source / 50).rounded() * 50)
    let nOut = Int(floor((timestamps.last! - timestamps.first!) * target)) + 1
    let ts = SignalMath.linspace(timestamps.first!, timestamps.last!, nOut)
    return (SignalMath.cubicSpline(timestamps, signal, ts), ts, target)
}

public struct ClockOffset {
    public var offsetS: Double
    public var driftPpm: Double
    public var snrTap1: Double
    public var snrTap2: Double
    public var tTap1Ref: Double
    public var tTap2Ref: Double
}

public func correctClock(_ ts: [Double], offsetS: Double, driftPpm: Double, tRefStart: Double) -> [Double] {
    let epsilon = driftPpm * 1e-6
    return ts.map { t in t - (offsetS + epsilon * (t - tRefStart)) }
}

public func estimateOffset(accelRef: [[Double]], tsRef: [Double], accelB: [[Double]], tsB: [Double], tap1: (Double, Double), tap2: (Double, Double), fsRef: Double? = nil, fsB: Double? = nil, hpCutoffHz: Double = 5) -> ClockOffset {
    let fsA = fsRef ?? measureFs(tsRef)
    let fsOther = fsB ?? measureFs(tsB)
    let magA = highpassMagnitude(accelRef, fsA, hpCutoffHz)
    let magB = highpassMagnitude(accelB, fsOther, hpCutoffHz)
    let i1a = findImpulse(magA, tsRef, tap1.0, tap1.1)
    let i1b = findImpulse(magB, tsB, tap1.0, tap1.1)
    let i2a = findImpulse(magA, tsRef, tap2.0, tap2.1)
    let i2b = findImpulse(magB, tsB, tap2.0, tap2.1)
    let delta1 = i1a.0 - i1b.0
    let delta2 = i2a.0 - i2b.0
    let span = i2a.0 - i1a.0
    let drift = abs(span) > 1 ? (delta2 - delta1) / span : 0
    return ClockOffset(offsetS: delta1, driftPpm: drift * 1e6, snrTap1: min(i1a.1, i1b.1), snrTap2: min(i2a.1, i2b.1), tTap1Ref: i1a.0, tTap2Ref: i2a.0)
}

private func highpassMagnitude(_ accel: [[Double]], _ fs: Double, _ cutoff: Double) -> [Double] {
    let ba = SignalMath.butterHighpass(order: 4, cutoffHz: cutoff, fs: fs)
    let n = accel.count
    var cols = (0..<3).map { axis in accel.map { $0[axis] } }
    cols = cols.map { SignalMath.filtfilt(ba.b, ba.a, $0) }
    return (0..<n).map { i in
        let x = cols[0][i], y = cols[1][i], z = cols[2][i]
        return sqrt(x * x + y * y + z * z)
    }
}

private func findImpulse(_ mag: [Double], _ timestamps: [Double], _ start: Double, _ end: Double) -> (Double, Double) {
    let idx = timestamps.indices.filter { timestamps[$0] >= start && timestamps[$0] <= end }
    var coarse = idx[0]
    var sumSq = 0.0
    for i in idx {
        sumSq += mag[i] * mag[i]
        if mag[i] > mag[coarse] { coarse = i }
    }
    let sub: Double
    if coarse <= 0 || coarse >= mag.count - 1 {
        sub = Double(coarse)
    } else {
        let alpha = mag[coarse - 1], beta = mag[coarse], gamma = mag[coarse + 1]
        let denom = alpha - 2 * beta + gamma
        sub = abs(denom) < 1e-12 ? Double(coarse) : Double(coarse) + 0.5 * (alpha - gamma) / denom
    }
    let lo = min(max(Int(floor(sub)), 0), timestamps.count - 1)
    let hi = min(lo + 1, timestamps.count - 1)
    let frac = sub - Double(lo)
    let tPeak = timestamps[lo] * (1 - frac) + timestamps[hi] * frac
    let rms = sqrt(sumSq / Double(idx.count))
    return (tPeak, mag[coarse] / (rms + 1e-12))
}

public struct GateAction {
    public var action: String
    public var fault: String?
    public var reason: String
}

public struct GateResult {
    public var accepted: Bool
    public var actions: [GateAction]
    public var corrected: [String: Any]
    public var escalateToCloud: Bool
}

private func num(_ map: [String: Any]?, _ key: String, _ fallback: Double = 0) -> Double {
    guard let v = map?[key] else { return fallback }
    if let n = v as? NSNumber { return n.doubleValue }
    if let d = v as? Double { return d }
    if let i = v as? Int { return Double(i) }
    return fallback
}

public func physicsGate(hypothesis: [String: Any], features: [String: Any], coherenceMin: Double = 0.8, misalignmentMin2xRatio: Double = 0.5, phaseStabilityMaxDeg: Double = 30) -> GateResult {
    var actions: [GateAction] = []
    var accepted = true
    var escalate = false
    let raw = hypothesis["hypotheses"] as? [[String: Any]] ?? []
    var severity = hypothesis["severity_zone"] as? String ?? "A"
    var nextTest = hypothesis["next_test"] as? String ?? "none"
    let orders = features["orders"] as? [String: Any]
    let phase = features["phase_deg"] as? [String: Any]
    let coherence = features["coherence"] as? [String: Any]
    let dspZone = features["severity_zone"] as? String ?? "A"
    let harmonics = Int(num(orders, "harmonics_count_above_floor"))
    let axRad = num(features, "axial_to_radial_ratio")
    let phaseStab = (features["phase_stability_deg"] as? NSNumber)?.doubleValue
    let amp1 = num(orders, "1x"), amp2 = num(orders, "2x"), amp05 = num(orders, "0.5x")
    if severity != dspZone {
        actions.append(GateAction(action: "OVERWRITE_ZONE", fault: nil, reason: "zone overwrite"))
        severity = dspZone
        accepted = false
    }
    var kept: [[String: Any]] = []
    for hyp0 in raw {
        let fault = hyp0["fault"] as? String ?? "unknown"
        // Model output can carry p outside [0, 1]; clamp before any check uses it.
        let rawP = num(hyp0, "p")
        let p = rawP.isFinite ? min(1, max(0, rawP)) : 0
        var hyp = hyp0
        hyp["p"] = p
        var keep = true
        if !knownFaultDetails.contains(fault) {
            actions.append(GateAction(action: "REJECT", fault: fault, reason: "not a schema fault name"))
            escalate = true
            keep = false
        }
        if fault == "static_imbalance" || fault == "couple_imbalance" {
            if amp1 < amp2 && amp2 > 1e-6 {
                actions.append(GateAction(action: "REJECT", fault: fault, reason: "2x > 1x"))
                escalate = true
                keep = false
            } else if harmonics > 3 {
                actions.append(GateAction(action: "DOWNGRADE", fault: fault, reason: "harmonics"))
                hyp["p"] = p * 0.6
            }
        }
        if fault == "static_imbalance" && keep {
            let ph = (phase?["AB_H_1x"] as? NSNumber)?.doubleValue
            let coh = (coherence?["AB_H_1x"] as? NSNumber)?.doubleValue ?? 0
            if let ph, coh >= coherenceMin, abs(ph) > 45 {
                actions.append(GateAction(action: "DOWNGRADE", fault: fault, reason: "phase"))
                if nextTest == "none" { nextTest = "radial_phase_DE_NDE" }
                hyp["p"] = p * 0.7
            }
        }
        if fault == "couple_imbalance" && keep {
            let ph = (phase?["AB_H_1x"] as? NSNumber)?.doubleValue
            let coh = (coherence?["AB_H_1x"] as? NSNumber)?.doubleValue ?? 0
            if let ph, coh >= coherenceMin, abs(ph) < 135 {
                actions.append(GateAction(action: "DOWNGRADE", fault: fault, reason: "phase"))
                hyp["p"] = p * 0.7
            }
        }
        if fault == "angular_misalignment" || fault == "parallel_misalignment" {
            let two = amp2 / (amp1 + 1e-12)
            if two < misalignmentMin2xRatio && axRad < misalignmentMin2xRatio {
                actions.append(GateAction(action: "REJECT", fault: fault, reason: "no signature"))
                escalate = true
                keep = false
            }
        }
        if fault == "structural_looseness" || fault == "rotating_looseness" {
            let unstable = phaseStab != nil && phaseStab! > phaseStabilityMaxDeg
            if harmonics < 3 && !(amp05 > 0.1 * amp1) && !unstable {
                actions.append(GateAction(action: "REJECT", fault: fault, reason: "no looseness"))
                escalate = true
                keep = false
            }
        }
        if fault == "resonance" {
            actions.append(GateAction(action: "DOWNGRADE", fault: fault, reason: "needs sweep"))
            if nextTest == "none" { nextTest = "vary_speed" }
            hyp["p"] = p * 0.5
        }
        if fault == "healthy" {
            if dspZone == "C" || dspZone == "D" {
                actions.append(GateAction(action: "REJECT", fault: fault, reason: "zone"))
                escalate = true
                keep = false
            } else if harmonics > 2 || amp05 > 0.2 * amp1 {
                actions.append(GateAction(action: "DOWNGRADE", fault: fault, reason: "harmonics"))
                hyp["p"] = p * 0.6
            }
        }
        if keep { kept.append(hyp) }
    }
    if kept.isEmpty {
        accepted = false
        escalate = true
        actions.append(GateAction(action: "REJECT", fault: nil, reason: "none left"))
    } else if !actions.isEmpty {
        accepted = false
    }
    let corrected: [String: Any] = [
        "hypotheses": kept,
        "severity_zone": severity,
        "evidence": hypothesis["evidence"] ?? [],
        "next_test": nextTest,
    ]
    return GateResult(accepted: accepted, actions: actions, corrected: corrected, escalateToCloud: escalate)
}
