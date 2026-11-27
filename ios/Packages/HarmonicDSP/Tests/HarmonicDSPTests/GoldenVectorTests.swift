import XCTest
// @testable: checkButter uses the internal SignalMath.butterHighpass.
@testable import HarmonicDSP

final class GoldenVectorTests: XCTestCase {
    func testGoldenVectors() throws {
        let url = try XCTUnwrap(Bundle.module.url(forResource: "vectors", withExtension: "json"))
        let data = try Data(contentsOf: url)
        let root = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        let cases = try XCTUnwrap(root["cases"] as? [[String: Any]])
        XCTAssertGreaterThanOrEqual(cases.count, 10)
        for c in cases {
            switch c["kind"] as? String {
            case "clock": try checkClock(c)
            case "butter": try checkButter(c)
            case "welch": try checkWelch(c)
            case "orders": try checkOrders(c)
            case "velocity": try checkVelocity(c)
            case "phase": try checkPhase(c)
            case "severity": try checkSeverity(c)
            case "gate": try checkGate(c)
            case "sync": try checkSync(c)
            case "frames": try checkFrames(c)
            case "resample": try checkResample(c)
            case "features": break
            default: XCTFail("unknown kind")
            }
        }
    }

    private func checkClock(_ c: [String: Any]) throws {
        let ts = doubles(c["ts"])
        let got = correctClock(ts, offsetS: num(c["offset_s"]), driftPpm: num(c["drift_ppm"]), tRefStart: num(c["t_ref"]))
        close(got, doubles(c["expected"]), abs: 1e-9)
    }

    private func checkButter(_ c: [String: Any]) throws {
        let ba = SignalMath.butterHighpass(order: 4, cutoffHz: num(c["cutoff"]), fs: num(c["fs"]))
        close(ba.b, doubles(c["b"]), abs: 1e-8)
        close(ba.a, doubles(c["a"]), abs: 1e-8)
    }

    private func checkWelch(_ c: [String: Any]) throws {
        let spec = welchAmplitude(signal: doubles(c["signal"]), fs: num(c["fs"]), nperseg: int(c["nperseg"]))
        close(spec.freqs, doubles(c["freqs"]), abs: 1e-9)
        close(spec.amp, doubles(c["amp"]), abs: 1e-6, rel: 1e-5)
    }

    private func checkOrders(_ c: [String: Any]) throws {
        let freqs = doubles(c["freqs"])
        let amp = doubles(c["amp"])
        let speed = findRunningSpeed(freqs: freqs, amp: amp, rpmNameplate: num(c["rpm_nameplate"]))
        XCTAssertEqual(speed.fRunHz, num(c["f_run_hz"]), accuracy: 1e-6)
        let orders = extractOrders(freqs: freqs, amp: amp, fRunHz: speed.fRunHz)
        let expected = c["orders"] as! [String: Any]
        for (k, v) in expected {
            XCTAssertEqual(orders[k] ?? -1, num(v), accuracy: max(1e-5, 1e-4 * abs(num(v))))
        }
    }

    private func checkVelocity(_ c: [String: Any]) throws {
        let freqs = doubles(c["freqs"])
        let amp = doubles(c["amp"])
        XCTAssertEqual(overallVelocityRms(freqs: freqs, ampAccel: amp), num(c["overall"]), accuracy: max(1e-6, 1e-5 * abs(num(c["overall"]))))
        XCTAssertEqual(velocityAtOrder(freqs: freqs, ampAccel: amp, fOrderHz: num(c["f_order"])), num(c["at_order"]), accuracy: max(1e-6, 1e-5 * abs(num(c["at_order"]))))
    }

    private func checkPhase(_ c: [String: Any]) throws {
        let result = crossPhaseCoherence(
            signalA: doubles(c["a"]), signalB: doubles(c["b"]), fs: num(c["fs"]),
            fTargetHz: num(c["f_target"]), nperseg: int(c["nperseg"])
        )
        XCTAssertEqual(result.trusted, c["trusted"] as? Bool)
        XCTAssertEqual(result.coherence, num(c["coherence"]), accuracy: 1e-5)
        if c["phase_deg"] is NSNull || c["phase_deg"] == nil {
            XCTAssertNil(result.phaseDeg)
        } else {
            XCTAssertEqual(result.phaseDeg ?? 999, num(c["phase_deg"]), accuracy: 1e-3)
        }
    }

    private func checkSeverity(_ c: [String: Any]) throws {
        for row in c["rows"] as! [[String: Any]] {
            XCTAssertEqual(classifyZone(num(row["v"]), machineClass: row["class"] as! String), row["zone"] as? String)
        }
    }

    private func checkGate(_ c: [String: Any]) throws {
        let result = physicsGate(hypothesis: c["hypothesis"] as! [String: Any], features: c["features"] as! [String: Any])
        XCTAssertEqual(result.accepted, c["accepted"] as? Bool)
        XCTAssertEqual(result.escalateToCloud, c["escalate"] as? Bool)
        XCTAssertEqual(result.actions.map(\.action), c["actions"] as? [String])
        XCTAssertEqual(result.corrected["severity_zone"] as? String, c["severity_zone"] as? String)
    }

    private func checkSync(_ c: [String: Any]) throws {
        let w1 = c["w1"] as! [Any]
        let w2 = c["w2"] as! [Any]
        let result = estimateOffset(
            accelRef: matrix(c["accel_a"]), tsRef: doubles(c["ts_a"]),
            accelB: matrix(c["accel_b"]), tsB: doubles(c["ts_b"]),
            tap1: (num(w1[0]), num(w1[1])), tap2: (num(w2[0]), num(w2[1]))
        )
        XCTAssertEqual(result.offsetS, num(c["offset_s"]), accuracy: 1e-4)
        XCTAssertEqual(result.driftPpm, num(c["drift_ppm"]), accuracy: 1)
    }

    private func checkFrames(_ c: [String: Any]) throws {
        let accel = matrix(c["accel"])
        let g = estimateGravity(accel)
        close(g, doubles(c["g"]), abs: 1e-8)
        let proj = projectAxes(accel, gVec: g)
        close(proj.h, doubles(c["H"]), abs: 1e-8)
    }

    private func checkResample(_ c: [String: Any]) throws {
        let out = resampleUniform(signal: doubles(c["signal"]), timestamps: doubles(c["timestamps"]), fsTarget: num(c["fs_target"]))
        close(out.signal, doubles(c["signal_out"]), abs: 1e-6)
    }

    private func doubles(_ value: Any?) -> [Double] {
        (value as! [Any]).map { num($0) }
    }

    private func matrix(_ value: Any?) -> [[Double]] {
        (value as! [Any]).map { doubles($0) }
    }

    private func num(_ value: Any?) -> Double {
        if let n = value as? NSNumber { return n.doubleValue }
        if let d = value as? Double { return d }
        return 0
    }

    private func int(_ value: Any?) -> Int { Int(num(value)) }

    private func close(_ got: [Double], _ expected: [Double], abs absTol: Double, rel: Double = 0) {
        XCTAssertEqual(got.count, expected.count)
        for i in got.indices {
            let tol = max(absTol, rel * max(1, abs(expected[i])))
            XCTAssertEqual(got[i], expected[i], accuracy: tol, "index \(i)")
        }
    }
}
