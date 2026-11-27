import Foundation

public struct PhoneAnalysis: Sendable {
    public var fsHz: Double
    public var durationS: Double
    public var runningSpeedHz: Double
    public var orders: [String: Double]
    public var velocityH: Double
    public var velocityV: Double
    public var velocityA: Double
    public var severityZone: String
    public var dominantAxis: String
    public var axialToRadial: Double?
    public var crestFactor: Double
    public var kurtosis: Double
    public var v: [Double]
    public var a: [Double]
    public var h: [Double]
    public var timestamps: [Double]
}

public func analyzePhone(raw: [[Double]], timestamps: [Double], rpmNameplate: Double, isoClass: String = "class_I") -> PhoneAnalysis {
    let g = estimateGravity(raw)
    let axes = projectAxes(raw, gVec: g)
    let rv = resampleUniform(signal: removeDc(axes.v), timestamps: timestamps)
    let ra = resampleUniform(signal: removeDc(axes.a), timestamps: timestamps, fsTarget: rv.fs)
    let rh = resampleUniform(signal: removeDc(axes.h), timestamps: timestamps, fsTarget: rv.fs)
    let specH = welchAmplitude(signal: rh.signal, fs: rv.fs)
    let specV = welchAmplitude(signal: rv.signal, fs: rv.fs)
    let specA = welchAmplitude(signal: ra.signal, fs: rv.fs)
    let vH = overallVelocityRms(freqs: specH.freqs, ampAccel: specH.amp)
    let vV = overallVelocityRms(freqs: specH.freqs, ampAccel: specV.amp)
    let vA = overallVelocityRms(freqs: specH.freqs, ampAccel: specA.amp)
    let dom = vH >= vV ? specH.amp : specV.amp
    let domSig = vH >= vV ? rh.signal : rv.signal
    let speed = findRunningSpeed(freqs: specH.freqs, amp: dom, rpmNameplate: rpmNameplate)
    let orders = extractOrders(freqs: specH.freqs, amp: dom, fRunHz: speed.fRunHz)
    let oH = extractOrders(freqs: specH.freqs, amp: specH.amp, fRunHz: speed.fRunHz)
    let oV = extractOrders(freqs: specH.freqs, amp: specV.amp, fRunHz: speed.fRunHz)
    let oA = extractOrders(freqs: specH.freqs, amp: specA.amp, fRunHz: speed.fRunHz)
    let radial = max(oH["1x"] ?? 0, oV["1x"] ?? 0)
    let axial = radial > 1e-9 ? (oA["1x"] ?? 0) / radial : nil
    return PhoneAnalysis(
        fsHz: rv.fs,
        durationS: timestamps.count >= 2 ? timestamps.last! - timestamps.first! : 0,
        runningSpeedHz: speed.fRunHz,
        orders: orders,
        velocityH: vH, velocityV: vV, velocityA: vA,
        severityZone: classifyZone(max(vH, vV), machineClass: isoClass),
        dominantAxis: vH >= vV ? "H" : "V",
        axialToRadial: axial,
        crestFactor: crest(domSig),
        kurtosis: kurt(domSig),
        v: rv.signal, a: ra.signal, h: rh.signal,
        timestamps: rv.timestamps
    )
}

private func crest(_ x: [Double]) -> Double {
    let r = sqrt(x.reduce(0) { $0 + $1 * $1 } / Double(max(1, x.count)))
    if r < 1e-12 { return 0 }
    return (x.map { abs($0) }.max() ?? 0) / r
}

private func kurt(_ x: [Double]) -> Double {
    let m = SignalMath.mean(x)
    var c2 = 0.0, c4 = 0.0
    for v in x {
        let d = v - m
        c2 += d * d
        c4 += d * d * d * d
    }
    c2 /= Double(x.count)
    c4 /= Double(x.count)
    if c2 < 1e-18 { return 0 }
    return c4 / (c2 * c2)
}

public func faultFamily(_ detail: String, kurtosis: Double = 0) -> String {
    switch detail {
    case "static_imbalance", "couple_imbalance": return "imbalance"
    case "angular_misalignment", "parallel_misalignment": return "misalignment"
    case "structural_looseness", "rotating_looseness": return "looseness"
    case "healthy": return "healthy"
    default: return kurtosis >= 5 ? "bearing_wear" : "unknown"
    }
}

public func recommendedAction(family: String, zone: String) -> String {
    let urgency: String
    switch zone {
    case "D": urgency = "Stop the machine and inspect before the next run. "
    case "C": urgency = "Plan maintenance on the next outage. "
    case "B": urgency = "Keep running and trend the next reading. "
    default: urgency = "No immediate action. Trend on the next round. "
    }
    let specific: String
    switch family {
    case "imbalance": specific = "Check for mass loss, buildup, or a missing balance weight."
    case "misalignment": specific = "Check coupling gap and alignment. Soft foot is worth a look."
    case "looseness": specific = "Check hold-down bolts, base grout, and bearing fit."
    case "bearing_wear": specific = "Kurtosis is elevated. Confirm with a proper bearing measurement."
    case "healthy": specific = "Leave the machine in service and compare against this baseline next time."
    default: specific = "Repeat with phones on both bearings so relative phase can separate the faults."
    }
    return urgency + specific
}
