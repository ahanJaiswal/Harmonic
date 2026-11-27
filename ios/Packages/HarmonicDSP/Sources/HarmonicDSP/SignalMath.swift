import Foundation

enum SignalMath {
    static func mean(_ x: [Double]) -> Double {
        guard !x.isEmpty else { return 0 }
        return x.reduce(0, +) / Double(x.count)
    }

    static func median(_ x: [Double]) -> Double {
        guard !x.isEmpty else { return 0 }
        let s = x.sorted()
        let n = s.count
        return n % 2 == 1 ? s[n / 2] : 0.5 * (s[n / 2 - 1] + s[n / 2])
    }

    static func linspace(_ start: Double, _ end: Double, _ n: Int) -> [Double] {
        if n <= 1 { return [start] }
        let step = (end - start) / Double(n - 1)
        return (0..<n).map { start + step * Double($0) }
    }

    static func hannPeriodic(_ n: Int) -> [Double] {
        if n == 1 { return [1] }
        return (0..<n).map { i in 0.5 - 0.5 * cos(2 * .pi * Double(i) / Double(n)) }
    }

    static func rfft(_ x: [Double]) -> (re: [Double], im: [Double]) {
        let n = x.count
        var re = [Double](repeating: 0, count: n)
        var im = [Double](repeating: 0, count: n)
        if n > 0 && (n & (n - 1)) == 0 {
            re = x
            fftRadix2(&re, &im)
        } else {
            for k in 0..<n {
                var sr = 0.0
                var si = 0.0
                let base = -2 * .pi * Double(k) / Double(n)
                for t in 0..<n {
                    let ang = base * Double(t)
                    sr += x[t] * cos(ang)
                    si += x[t] * sin(ang)
                }
                re[k] = sr
                im[k] = si
            }
        }
        let m = n / 2 + 1
        return (Array(re.prefix(m)), Array(im.prefix(m)))
    }

    private static func fftRadix2(_ re: inout [Double], _ im: inout [Double]) {
        let n = re.count
        var j = 0
        if n > 1 {
            for i in 1..<n {
                var bit = n >> 1
                while j & bit != 0 {
                    j ^= bit
                    bit >>= 1
                }
                j ^= bit
                if i < j {
                    re.swapAt(i, j)
                    im.swapAt(i, j)
                }
            }
        }
        var len = 2
        while len <= n {
            let ang = -2 * .pi / Double(len)
            let wlenRe = cos(ang)
            let wlenIm = sin(ang)
            var i = 0
            while i < n {
                var wRe = 1.0
                var wIm = 0.0
                let half = len / 2
                for k in 0..<half {
                    let idx = i + k
                    let jdx = idx + half
                    let vRe = re[jdx] * wRe - im[jdx] * wIm
                    let vIm = re[jdx] * wIm + im[jdx] * wRe
                    let uRe = re[idx]
                    let uIm = im[idx]
                    re[idx] = uRe + vRe
                    im[idx] = uIm + vIm
                    re[jdx] = uRe - vRe
                    im[jdx] = uIm - vIm
                    let nwRe = wRe * wlenRe - wIm * wlenIm
                    let nwIm = wRe * wlenIm + wIm * wlenRe
                    wRe = nwRe
                    wIm = nwIm
                }
                i += len
            }
            len <<= 1
        }
    }

    static func welchPsd(_ signal: [Double], fs: Double, nperseg: Int, noverlap: Int) -> (freqs: [Double], psd: [Double]) {
        let win = hannPeriodic(nperseg)
        let winEnergy = win.reduce(0) { $0 + $1 * $1 }
        let scale = 1.0 / (fs * winEnergy)
        let step = nperseg - noverlap
        let nseg = (signal.count - nperseg) / step + 1
        let bins = nperseg / 2 + 1
        var acc = [Double](repeating: 0, count: bins)
        for s in 0..<nseg {
            let start = s * step
            let mean = signal[start..<(start + nperseg)].reduce(0, +) / Double(nperseg)
            var seg = [Double](repeating: 0, count: nperseg)
            for i in 0..<nperseg { seg[i] = (signal[start + i] - mean) * win[i] }
            let spec = rfft(seg)
            for k in 0..<bins { acc[k] += spec.re[k] * spec.re[k] + spec.im[k] * spec.im[k] }
        }
        let inv = 1.0 / Double(nseg)
        for k in 0..<bins { acc[k] *= inv * scale }
        let last = nperseg % 2 == 0 ? bins - 1 : bins
        if last > 1 { for k in 1..<last { acc[k] *= 2 } }
        let freqs = (0..<bins).map { Double($0) * fs / Double(nperseg) }
        return (freqs, acc)
    }

    static func welchCsd(_ x: [Double], _ y: [Double], fs: Double, nperseg: Int, noverlap: Int) -> (freqs: [Double], re: [Double], im: [Double]) {
        let win = hannPeriodic(nperseg)
        let winEnergy = win.reduce(0) { $0 + $1 * $1 }
        let scale = 1.0 / (fs * winEnergy)
        let step = nperseg - noverlap
        let nseg = (x.count - nperseg) / step + 1
        let bins = nperseg / 2 + 1
        var reAcc = [Double](repeating: 0, count: bins)
        var imAcc = [Double](repeating: 0, count: bins)
        for s in 0..<nseg {
            let start = s * step
            var mx = 0.0, my = 0.0
            for i in 0..<nperseg { mx += x[start + i]; my += y[start + i] }
            mx /= Double(nperseg); my /= Double(nperseg)
            var segX = [Double](repeating: 0, count: nperseg)
            var segY = [Double](repeating: 0, count: nperseg)
            for i in 0..<nperseg {
                segX[i] = (x[start + i] - mx) * win[i]
                segY[i] = (y[start + i] - my) * win[i]
            }
            let X = rfft(segX)
            let Y = rfft(segY)
            for k in 0..<bins {
                reAcc[k] += X.re[k] * Y.re[k] + X.im[k] * Y.im[k]
                imAcc[k] += X.re[k] * Y.im[k] - X.im[k] * Y.re[k]
            }
        }
        let inv = 1.0 / Double(nseg)
        let last = nperseg % 2 == 0 ? bins - 1 : bins
        for k in 0..<bins {
            reAcc[k] *= inv * scale
            imAcc[k] *= inv * scale
        }
        if last > 1 {
            for k in 1..<last { reAcc[k] *= 2; imAcc[k] *= 2 }
        }
        let freqs = (0..<bins).map { Double($0) * fs / Double(nperseg) }
        return (freqs, reAcc, imAcc)
    }

    static func autoNperseg(fs: Double, length: Int) -> Int {
        let raw = Int(pow(2.0, ceil(log2(fs / 0.1))))
        return max(1, min(raw, length))
    }

    static func butterHighpass(order: Int, cutoffHz: Double, fs: Double) -> (b: [Double], a: [Double]) {
        let wn = cutoffHz / (fs / 2)
        var pRe = [Double](repeating: 0, count: order)
        var pIm = [Double](repeating: 0, count: order)
        var m = -order + 1
        var idx = 0
        while m < order {
            let ang = Double.pi * Double(m) / (2.0 * Double(order))
            pRe[idx] = -cos(ang)
            pIm[idx] = -sin(ang)
            idx += 1
            m += 2
        }
        let fsDig = 2.0
        let warped = 2 * fsDig * tan(Double.pi * wn / fsDig)
        var kRe = 1.0, kIm = 0.0
        for i in 0..<order {
            let nr = -pRe[i], ni = -pIm[i]
            let den = nr * nr + ni * ni
            let invRe = nr / den, invIm = -ni / den
            let nkRe = kRe * invRe - kIm * invIm
            let nkIm = kRe * invIm + kIm * invRe
            kRe = nkRe; kIm = nkIm
        }
        var hpRe = [Double](repeating: 0, count: order)
        var hpIm = [Double](repeating: 0, count: order)
        for i in 0..<order {
            let den = pRe[i] * pRe[i] + pIm[i] * pIm[i]
            hpRe[i] = warped * pRe[i] / den
            hpIm[i] = -warped * pIm[i] / den
        }
        let fs2 = 4.0
        var denRe = 1.0, denIm = 0.0
        let num = pow(fs2, Double(order))
        for i in 0..<order {
            let ar = fs2 - hpRe[i], ai = -hpIm[i]
            let ndr = denRe * ar - denIm * ai
            let ndi = denRe * ai + denIm * ar
            denRe = ndr; denIm = ndi
        }
        let denAbs = denRe * denRe + denIm * denIm
        let fracRe = num * denRe / denAbs
        let fracIm = num * (-denIm) / denAbs
        let kz = kRe * fracRe - kIm * fracIm
        let zRe = [Double](repeating: 1, count: order)
        let zIm = [Double](repeating: 0, count: order)
        var pdRe = [Double](repeating: 0, count: order)
        var pdIm = [Double](repeating: 0, count: order)
        for i in 0..<order {
            let nr = fs2 + hpRe[i], ni = hpIm[i]
            let dr = fs2 - hpRe[i], di = -hpIm[i]
            let d = dr * dr + di * di
            pdRe[i] = (nr * dr + ni * di) / d
            pdIm[i] = (ni * dr - nr * di) / d
        }
        var b = polyReal(zRe, zIm, kz)
        var a = polyReal(pdRe, pdIm, 1)
        let a0 = a[0]
        for i in a.indices { a[i] /= a0 }
        for i in b.indices { b[i] /= a0 }
        return (b, a)
    }

    private static func polyReal(_ rootRe: [Double], _ rootIm: [Double], _ gain: Double) -> [Double] {
        var cr = [gain]
        var ci = [0.0]
        for i in rootRe.indices {
            let n = cr.count
            var nr = [Double](repeating: 0, count: n + 1)
            var ni = [Double](repeating: 0, count: n + 1)
            let rRe = rootRe[i], rIm = rootIm[i]
            for j in 0..<n {
                nr[j] += cr[j]
                ni[j] += ci[j]
                nr[j + 1] += -rRe * cr[j] + rIm * ci[j]
                ni[j + 1] += -rRe * ci[j] - rIm * cr[j]
            }
            cr = nr; ci = ni
        }
        return cr
    }

    static func lfilterZi(_ bIn: [Double], _ aIn: [Double]) -> [Double] {
        var b = bIn, a = aIn
        let a0 = a[0]
        for i in b.indices { b[i] /= a0 }
        for i in a.indices { a[i] /= a0 }
        let n = max(a.count, b.count)
        if b.count < n { b += [Double](repeating: 0, count: n - b.count) }
        if a.count < n { a += [Double](repeating: 0, count: n - a.count) }
        let sumB = b.reduce(0, +)
        let sumA = a.reduce(0, +)
        let yInf = sumB / sumA
        let diff = (0..<n).map { b[$0] - yInf * a[$0] }
        var acc = 0.0
        var flipped = [Double](repeating: 0, count: n)
        for i in stride(from: n - 1, through: 0, by: -1) {
            acc += diff[i]
            flipped[i] = acc
        }
        return Array(flipped.dropFirst())
    }

    static func lfilter(_ bIn: [Double], _ aIn: [Double], _ x: [Double], zi: [Double]?) -> [Double] {
        var b = bIn, a = aIn
        let a0 = a[0]
        for i in b.indices { b[i] /= a0 }
        for i in a.indices { a[i] /= a0 }
        let n = max(a.count, b.count) - 1
        if b.count < n + 1 { b += [Double](repeating: 0, count: n + 1 - b.count) }
        if a.count < n + 1 { a += [Double](repeating: 0, count: n + 1 - a.count) }
        var z = zi ?? [Double](repeating: 0, count: n)
        var y = [Double](repeating: 0, count: x.count)
        for i in x.indices {
            let xi = x[i]
            y[i] = b[0] * xi + (n > 0 ? z[0] : 0)
            if n > 1 {
                for j in 0..<(n - 1) { z[j] = b[j + 1] * xi + z[j + 1] - a[j + 1] * y[i] }
            }
            if n > 0 { z[n - 1] = b[n] * xi - a[n] * y[i] }
        }
        return y
    }

    static func filtfilt(_ b: [Double], _ a: [Double], _ x: [Double]) -> [Double] {
        let edge = 3 * max(a.count, b.count)
        let ext = oddExt(x, edge)
        let zi = lfilterZi(b, a)
        let y1 = lfilter(b, a, ext, zi: zi.map { $0 * ext[0] })
        let rev = Array(y1.reversed())
        let y2 = lfilter(b, a, rev, zi: zi.map { $0 * rev[0] })
        let y = Array(y2.reversed())
        return Array(y[edge..<(y.count - edge)])
    }

    private static func oddExt(_ x: [Double], _ n: Int) -> [Double] {
        var out = [Double]()
        out.reserveCapacity(x.count + 2 * n)
        for i in 0..<n { out.append(2 * x[0] - x[n - i]) }
        out.append(contentsOf: x)
        let last = x.count - 1
        for i in 0..<n { out.append(2 * x[last] - x[last - 1 - i]) }
        return out
    }

    static func cubicSpline(_ x: [Double], _ y: [Double], _ grid: [Double]) -> [Double] {
        let n = x.count
        let h = (0..<(n - 1)).map { x[$0 + 1] - x[$0] }
        if n == 2 {
            return grid.map { g in
                let t = min(1, max(0, (g - x[0]) / h[0]))
                return y[0] * (1 - t) + y[1] * t
            }
        }
        if n == 3 {
            let slope0 = (y[1] - y[0]) / h[0]
            let slope1 = (y[2] - y[1]) / h[1]
            let s = solve3(
                1, 1, 0,
                h[1], 2 * (h[0] + h[1]), h[0],
                0, 1, 1,
                2 * slope0,
                3 * (h[0] * slope1 + h[1] * slope0),
                2 * slope1
            )
            return evalHermite(x, y, h, s, grid)
        }
        var d = [Double](repeating: 0, count: n)
        for i in 1..<(n - 1) {
            d[i] = (y[i + 1] - y[i]) / h[i] - (y[i] - y[i - 1]) / h[i - 1]
        }
        let mcount = n - 2
        var lower = [Double](repeating: 0, count: mcount)
        var diag = [Double](repeating: 0, count: mcount)
        var upper = [Double](repeating: 0, count: mcount)
        var rhs = [Double](repeating: 0, count: mcount)
        for i in 1..<(n - 1) {
            var cIm1 = h[i - 1] / 6
            var cI = (h[i - 1] + h[i]) / 3
            var cIp1 = h[i] / 6
            if i == 1 {
                cI += cIm1 * ((h[0] + h[1]) / h[1])
                cIp1 += cIm1 * (-h[0] / h[1])
                cIm1 = 0
            }
            if i == n - 2 {
                cIm1 += cIp1 * (-h[n - 2] / h[n - 3])
                cI += cIp1 * ((h[n - 3] + h[n - 2]) / h[n - 3])
                cIp1 = 0
            }
            let j = i - 1
            if i - 1 >= 1 { lower[j] += cIm1 }
            diag[j] += cI
            if i + 1 <= n - 2 { upper[j] += cIp1 }
            rhs[j] = d[i]
        }
        let mids = thomas(lower, diag, upper, rhs)
        var m = [Double](repeating: 0, count: n)
        for i in 0..<mcount { m[i + 1] = mids[i] }
        m[0] = ((h[0] + h[1]) * m[1] - h[0] * m[2]) / h[1]
        m[n - 1] = (-h[n - 2] * m[n - 3] + (h[n - 3] + h[n - 2]) * m[n - 2]) / h[n - 3]
        return grid.map { g in
            var i = binarySearchLeft(x, g) - 1
            if i < 0 { i = 0 }
            if i > n - 2 { i = n - 2 }
            let hi = h[i]
            let t1 = x[i + 1] - g
            let dx = g - x[i]
            return m[i] * t1 * t1 * t1 / (6 * hi) + m[i + 1] * dx * dx * dx / (6 * hi)
                + (y[i] - m[i] * hi * hi / 6) * t1 / hi
                + (y[i + 1] - m[i + 1] * hi * hi / 6) * dx / hi
        }
    }

    private static func evalHermite(_ x: [Double], _ y: [Double], _ h: [Double], _ s: [Double], _ grid: [Double]) -> [Double] {
        let n = x.count
        return grid.map { g in
            var i = binarySearchLeft(x, g) - 1
            if i < 0 { i = 0 }
            if i > n - 2 { i = n - 2 }
            let hi = h[i]
            let t = (g - x[i]) / hi
            let t2 = t * t
            let t3 = t2 * t
            let h00 = 2 * t3 - 3 * t2 + 1
            let h10 = t3 - 2 * t2 + t
            let h01 = -2 * t3 + 3 * t2
            let h11 = t3 - t2
            return h00 * y[i] + h10 * hi * s[i] + h01 * y[i + 1] + h11 * hi * s[i + 1]
        }
    }

    private static func solve3(
        _ a00: Double, _ a01: Double, _ a02: Double,
        _ a10: Double, _ a11: Double, _ a12: Double,
        _ a20: Double, _ a21: Double, _ a22: Double,
        _ b0: Double, _ b1: Double, _ b2: Double
    ) -> [Double] {
        let det = a00 * (a11 * a22 - a12 * a21) - a01 * (a10 * a22 - a12 * a20) + a02 * (a10 * a21 - a11 * a20)
        let s0 = (b0 * (a11 * a22 - a12 * a21) - a01 * (b1 * a22 - a12 * b2) + a02 * (b1 * a21 - a11 * b2)) / det
        let s1 = (a00 * (b1 * a22 - a12 * b2) - b0 * (a10 * a22 - a12 * a20) + a02 * (a10 * b2 - b1 * a20)) / det
        let s2 = (a00 * (a11 * b2 - b1 * a21) - a01 * (a10 * b2 - b1 * a20) + b0 * (a10 * a21 - a11 * a20)) / det
        return [s0, s1, s2]
    }

    private static func thomas(_ lower: [Double], _ diag: [Double], _ upper: [Double], _ rhs: [Double]) -> [Double] {
        let n = diag.count
        var c = upper
        var d = rhs
        var b = diag
        for i in 1..<n {
            let w = lower[i] / b[i - 1]
            b[i] -= w * c[i - 1]
            d[i] -= w * d[i - 1]
        }
        var x = [Double](repeating: 0, count: n)
        x[n - 1] = d[n - 1] / b[n - 1]
        if n > 1 {
            for i in stride(from: n - 2, through: 0, by: -1) {
                x[i] = (d[i] - c[i] * x[i + 1]) / b[i]
            }
        }
        return x
    }

    static func binarySearchLeft(_ x: [Double], _ value: Double) -> Int {
        var lo = 0
        var hi = x.count
        while lo < hi {
            let mid = (lo + hi) >> 1
            if x[mid] < value { lo = mid + 1 } else { hi = mid }
        }
        return lo
    }
}
