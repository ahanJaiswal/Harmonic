package com.harmonic.dsp

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Portable numeric kernels. Tuned to match SciPy / NumPy on the golden vectors. */
internal object SignalMath {

    fun roundPy(value: Double, digits: Int): Double {
        if (value.isNaN() || value.isInfinite()) return value
        return BigDecimal.valueOf(value).setScale(digits, RoundingMode.HALF_EVEN).toDouble()
    }

    fun mean(x: DoubleArray): Double {
        if (x.isEmpty()) return 0.0
        var s = 0.0
        for (v in x) s += v
        return s / x.size
    }

    fun median(x: DoubleArray): Double {
        if (x.isEmpty()) return 0.0
        val s = x.copyOf()
        s.sort()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else 0.5 * (s[n / 2 - 1] + s[n / 2])
    }

    fun maxAbs(x: DoubleArray): Double {
        var m = 0.0
        for (v in x) {
            val a = abs(v)
            if (a > m) m = a
        }
        return m
    }

    fun rms(x: DoubleArray): Double {
        var s = 0.0
        for (v in x) s += v * v
        return sqrt(s / x.size.coerceAtLeast(1))
    }

    fun linspace(start: Double, end: Double, n: Int): DoubleArray {
        if (n <= 1) return doubleArrayOf(start)
        val out = DoubleArray(n)
        val step = (end - start) / (n - 1)
        for (i in 0 until n) out[i] = start + step * i
        return out
    }

    fun diff(x: DoubleArray): DoubleArray {
        if (x.size < 2) return DoubleArray(0)
        val d = DoubleArray(x.size - 1)
        for (i in 0 until d.size) d[i] = x[i + 1] - x[i]
        return d
    }

    fun argMax(x: DoubleArray, from: Int = 0, toExclusive: Int = x.size): Int {
        var best = from
        var bestV = Double.NEGATIVE_INFINITY
        for (i in from until toExclusive) {
            if (x[i] > bestV) {
                bestV = x[i]
                best = i
            }
        }
        return best
    }

    fun hannPeriodic(n: Int): DoubleArray {
        val w = DoubleArray(n)
        if (n == 1) {
            w[0] = 1.0
            return w
        }
        for (i in 0 until n) {
            w[i] = 0.5 - 0.5 * cos(2.0 * PI * i / n)
        }
        return w
    }

    /** NumPy-compatible real rFFT (negative exponent, no 1/n scaling). */
    fun rfft(x: DoubleArray): Pair<DoubleArray, DoubleArray> {
        val n = x.size
        require(n > 0)
        val re = DoubleArray(n)
        val im = DoubleArray(n)
        if (n and (n - 1) == 0) {
            x.copyInto(re)
            fftRadix2(re, im)
        } else {
            for (k in 0 until n) {
                var sr = 0.0
                var si = 0.0
                val base = -2.0 * PI * k / n
                for (t in 0 until n) {
                    val ang = base * t
                    sr += x[t] * cos(ang)
                    si += x[t] * sin(ang)
                }
                re[k] = sr
                im[k] = si
            }
        }
        val m = n / 2 + 1
        return re.copyOf(m) to im.copyOf(m)
    }

    private fun fftRadix2(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wlenRe = cos(ang)
            val wlenIm = sin(ang)
            var i = 0
            while (i < n) {
                var wRe = 1.0
                var wIm = 0.0
                val half = len / 2
                for (k in 0 until half) {
                    val idx = i + k
                    val jdx = idx + half
                    val vRe = re[jdx] * wRe - im[jdx] * wIm
                    val vIm = re[jdx] * wIm + im[jdx] * wRe
                    val uRe = re[idx]
                    val uIm = im[idx]
                    re[idx] = uRe + vRe
                    im[idx] = uIm + vIm
                    re[jdx] = uRe - vRe
                    im[jdx] = uIm - vIm
                    val nwRe = wRe * wlenRe - wIm * wlenIm
                    val nwIm = wRe * wlenIm + wIm * wlenRe
                    wRe = nwRe
                    wIm = nwIm
                }
                i += len
            }
            len = len shl 1
        }
    }

    /**
     * One-sided Welch PSD matching scipy.signal.welch(..., window="hann",
     * detrend="constant", scaling="density"). Hann is the periodic window
     * scipy's get_window produces.
     */
    fun welchPsd(
        signal: DoubleArray,
        fs: Double,
        nperseg: Int,
        noverlap: Int,
    ): Pair<DoubleArray, DoubleArray> {
        require(signal.size >= nperseg)
        val win = hannPeriodic(nperseg)
        var winEnergy = 0.0
        for (w in win) winEnergy += w * w
        val scale = 1.0 / (fs * winEnergy)
        val step = nperseg - noverlap
        val nseg = (signal.size - nperseg) / step + 1
        val bins = nperseg / 2 + 1
        val acc = DoubleArray(bins)
        val seg = DoubleArray(nperseg)
        for (s in 0 until nseg) {
            val start = s * step
            var mean = 0.0
            for (i in 0 until nperseg) mean += signal[start + i]
            mean /= nperseg
            for (i in 0 until nperseg) seg[i] = (signal[start + i] - mean) * win[i]
            val (re, im) = rfft(seg)
            for (k in 0 until bins) acc[k] += re[k] * re[k] + im[k] * im[k]
        }
        val inv = 1.0 / nseg
        for (k in 0 until bins) acc[k] *= inv * scale
        val last = if (nperseg % 2 == 0) bins - 1 else bins
        for (k in 1 until last) acc[k] *= 2.0
        val freqs = DoubleArray(bins) { k -> k * fs / nperseg }
        return freqs to acc
    }

    /** Cross spectrum Pxy with the same windowing as [welchPsd]. Phase is angle(Pxy). */
    fun welchCsd(
        x: DoubleArray,
        y: DoubleArray,
        fs: Double,
        nperseg: Int,
        noverlap: Int,
    ): Pair<DoubleArray, Pair<DoubleArray, DoubleArray>> {
        require(x.size == y.size && x.size >= nperseg)
        val win = hannPeriodic(nperseg)
        var winEnergy = 0.0
        for (w in win) winEnergy += w * w
        val scale = 1.0 / (fs * winEnergy)
        val step = nperseg - noverlap
        val nseg = (x.size - nperseg) / step + 1
        val bins = nperseg / 2 + 1
        val reAcc = DoubleArray(bins)
        val imAcc = DoubleArray(bins)
        val segX = DoubleArray(nperseg)
        val segY = DoubleArray(nperseg)
        for (s in 0 until nseg) {
            val start = s * step
            var mx = 0.0
            var my = 0.0
            for (i in 0 until nperseg) {
                mx += x[start + i]
                my += y[start + i]
            }
            mx /= nperseg
            my /= nperseg
            for (i in 0 until nperseg) {
                segX[i] = (x[start + i] - mx) * win[i]
                segY[i] = (y[start + i] - my) * win[i]
            }
            val (xr, xi) = rfft(segX)
            val (yr, yi) = rfft(segY)
            // scipy csd: conj(X) * Y
            for (k in 0 until bins) {
                reAcc[k] += xr[k] * yr[k] + xi[k] * yi[k]
                imAcc[k] += xr[k] * yi[k] - xi[k] * yr[k]
            }
        }
        val inv = 1.0 / nseg
        val last = if (nperseg % 2 == 0) bins - 1 else bins
        for (k in 0 until bins) {
            reAcc[k] *= inv * scale
            imAcc[k] *= inv * scale
        }
        for (k in 1 until last) {
            reAcc[k] *= 2.0
            imAcc[k] *= 2.0
        }
        val freqs = DoubleArray(bins) { k -> k * fs / nperseg }
        return freqs to (reAcc to imAcc)
    }

    fun autoNperseg(fs: Double, length: Int): Int {
        val raw = 2.0.pow(kotlin.math.ceil(kotlin.math.log2(fs / 0.1))).toInt()
        return minOf(raw, length).coerceAtLeast(1)
    }

    /**
     * Order-N highpass Butterworth, bilinear transform, matching scipy.signal.butter
     * for digital filters (Wn = cutoff / Nyquist).
     */
    fun butterHighpass(order: Int, cutoffHz: Double, fs: Double): Pair<DoubleArray, DoubleArray> {
        val wn = cutoffHz / (fs / 2.0)
        require(wn > 0.0 && wn < 1.0)
        val pRe = DoubleArray(order)
        val pIm = DoubleArray(order)
        var m = -order + 1
        var idx = 0
        while (m < order) {
            val ang = PI * m / (2.0 * order)
            pRe[idx] = -cos(ang)
            pIm[idx] = -sin(ang)
            idx++
            m += 2
        }
        val fsDig = 2.0
        val warped = 2.0 * fsDig * tan(PI * wn / fsDig)
        var kRe = 1.0
        var kIm = 0.0
        for (i in 0 until order) {
            val nr = -pRe[i]
            val ni = -pIm[i]
            val den = nr * nr + ni * ni
            val invRe = nr / den
            val invIm = -ni / den
            val nkRe = kRe * invRe - kIm * invIm
            val nkIm = kRe * invIm + kIm * invRe
            kRe = nkRe
            kIm = nkIm
        }
        val hpRe = DoubleArray(order)
        val hpIm = DoubleArray(order)
        for (i in 0 until order) {
            val den = pRe[i] * pRe[i] + pIm[i] * pIm[i]
            hpRe[i] = warped * pRe[i] / den
            hpIm[i] = -warped * pIm[i] / den
        }
        val fs2 = 4.0
        var denRe = 1.0
        var denIm = 0.0
        val num = fs2.pow(order)
        for (i in 0 until order) {
            val ar = fs2 - hpRe[i]
            val ai = -hpIm[i]
            val ndr = denRe * ar - denIm * ai
            val ndi = denRe * ai + denIm * ar
            denRe = ndr
            denIm = ndi
        }
        val denAbs = denRe * denRe + denIm * denIm
        val fracRe = num * denRe / denAbs
        val fracIm = num * (-denIm) / denAbs
        val kz = kRe * fracRe - kIm * fracIm
        val zRe = DoubleArray(order) { 1.0 }
        val zIm = DoubleArray(order)
        val pdRe = DoubleArray(order)
        val pdIm = DoubleArray(order)
        for (i in 0 until order) {
            val nr = fs2 + hpRe[i]
            val ni = hpIm[i]
            val dr = fs2 - hpRe[i]
            val di = -hpIm[i]
            val d = dr * dr + di * di
            pdRe[i] = (nr * dr + ni * di) / d
            pdIm[i] = (ni * dr - nr * di) / d
        }
        val b = polyReal(zRe, zIm, kz)
        val a = polyReal(pdRe, pdIm, 1.0)
        val a0 = a[0]
        for (i in a.indices) a[i] /= a0
        for (i in b.indices) b[i] /= a0
        return b to a
    }

    private fun polyReal(rootRe: DoubleArray, rootIm: DoubleArray, gain: Double): DoubleArray {
        var cr = doubleArrayOf(gain)
        var ci = doubleArrayOf(0.0)
        for (i in rootRe.indices) {
            val n = cr.size
            val nr = DoubleArray(n + 1)
            val ni = DoubleArray(n + 1)
            val rRe = rootRe[i]
            val rIm = rootIm[i]
            for (j in 0 until n) {
                nr[j] += cr[j]
                ni[j] += ci[j]
                nr[j + 1] += -rRe * cr[j] + rIm * ci[j]
                ni[j + 1] += -rRe * ci[j] - rIm * cr[j]
            }
            cr = nr
            ci = ni
        }
        return DoubleArray(cr.size) { cr[it] }
    }

    fun lfilterZi(bIn: DoubleArray, aIn: DoubleArray): DoubleArray {
        var b = bIn.copyOf()
        var a = aIn.copyOf()
        val a0 = a[0]
        for (i in b.indices) b[i] /= a0
        for (i in a.indices) a[i] /= a0
        val n = maxOf(a.size, b.size)
        b = b.copyOf(n)
        a = a.copyOf(n)
        var sumB = 0.0
        var sumA = 0.0
        for (i in 0 until n) {
            sumB += b[i]
            sumA += a[i]
        }
        val yInf = sumB / sumA
        val diff = DoubleArray(n) { b[it] - yInf * a[it] }
        // flip(cumsum(flip(diff)))[1:]
        var acc = 0.0
        val flippedCum = DoubleArray(n)
        for (i in n - 1 downTo 0) {
            acc += diff[i]
            flippedCum[i] = acc
        }
        return flippedCum.copyOfRange(1, n)
    }

    fun lfilter(bIn: DoubleArray, aIn: DoubleArray, x: DoubleArray, zi: DoubleArray?): DoubleArray {
        var b = bIn.copyOf()
        var a = aIn.copyOf()
        val a0 = a[0]
        for (i in b.indices) b[i] /= a0
        for (i in a.indices) a[i] /= a0
        val n = maxOf(a.size, b.size) - 1
        b = b.copyOf(n + 1)
        a = a.copyOf(n + 1)
        val z = if (zi == null) DoubleArray(n) else zi.copyOf()
        val y = DoubleArray(x.size)
        for (i in x.indices) {
            val xi = x[i]
            y[i] = b[0] * xi + if (n > 0) z[0] else 0.0
            for (j in 0 until (n - 1)) {
                z[j] = b[j + 1] * xi + z[j + 1] - a[j + 1] * y[i]
            }
            if (n > 0) z[n - 1] = b[n] * xi - a[n] * y[i]
        }
        return y
    }

    fun filtfilt(b: DoubleArray, a: DoubleArray, x: DoubleArray): DoubleArray {
        val edge = 3 * maxOf(a.size, b.size)
        require(x.size > edge)
        val ext = oddExt(x, edge)
        val zi = lfilterZi(b, a)
        val forwardZi = DoubleArray(zi.size) { zi[it] * ext[0] }
        val y1 = lfilter(b, a, ext, forwardZi)
        val rev = y1.reversedArray()
        val backZi = DoubleArray(zi.size) { zi[it] * rev[0] }
        val y2 = lfilter(b, a, rev, backZi)
        val y = y2.reversedArray()
        return y.copyOfRange(edge, y.size - edge)
    }

    private fun oddExt(x: DoubleArray, n: Int): DoubleArray {
        val out = DoubleArray(x.size + 2 * n)
        for (i in 0 until n) out[i] = 2.0 * x[0] - x[n - i]
        x.copyInto(out, n)
        val last = x.lastIndex
        for (i in 0 until n) out[n + x.size + i] = 2.0 * x[last] - x[last - 1 - i]
        return out
    }

    /** Not-a-knot cubic spline, matching scipy.interpolate.CubicSpline defaults. */
    fun cubicSpline(x: DoubleArray, y: DoubleArray, grid: DoubleArray): DoubleArray {
        val n = x.size
        require(n == y.size && n >= 2)
        val h = diff(x)
        if (n == 2) {
            return DoubleArray(grid.size) { g ->
                val t = ((grid[g] - x[0]) / h[0]).coerceIn(0.0, 1.0)
                y[0] * (1 - t) + y[1] * t
            }
        }
        if (n == 3) {
            val slope0 = (y[1] - y[0]) / h[0]
            val slope1 = (y[2] - y[1]) / h[1]
            // 3x3 system from CubicSpline for n == 3 not-a-knot.
            val a00 = 1.0; val a01 = 1.0; val a02 = 0.0
            val a10 = h[1]; val a11 = 2 * (h[0] + h[1]); val a12 = h[0]
            val a20 = 0.0; val a21 = 1.0; val a22 = 1.0
            val b0 = 2 * slope0
            val b1 = 3 * (h[0] * slope1 + h[1] * slope0)
            val b2 = 2 * slope1
            val s = solve3(a00, a01, a02, a10, a11, a12, a20, a21, a22, b0, b1, b2)
            return evalHermite(x, y, h, s, grid)
        }
        val d = DoubleArray(n)
        for (i in 1 until n - 1) {
            d[i] = (y[i + 1] - y[i]) / h[i] - (y[i] - y[i - 1]) / h[i - 1]
        }
        val mcount = n - 2
        val lower = DoubleArray(mcount)
        val diag = DoubleArray(mcount)
        val upper = DoubleArray(mcount)
        val rhs = DoubleArray(mcount)
        for (i in 1 until n - 1) {
            var cIm1 = h[i - 1] / 6.0
            var cI = (h[i - 1] + h[i]) / 3.0
            var cIp1 = h[i] / 6.0
            if (i == 1) {
                cI += cIm1 * ((h[0] + h[1]) / h[1])
                cIp1 += cIm1 * (-h[0] / h[1])
                cIm1 = 0.0
            }
            if (i == n - 2) {
                cIm1 += cIp1 * (-h[n - 2] / h[n - 3])
                cI += cIp1 * ((h[n - 3] + h[n - 2]) / h[n - 3])
                cIp1 = 0.0
            }
            val j = i - 1
            if (i - 1 >= 1) lower[j] += cIm1
            diag[j] += cI
            if (i + 1 <= n - 2) upper[j] += cIp1
            rhs[j] = d[i]
        }
        val mids = thomas(lower, diag, upper, rhs)
        val m = DoubleArray(n)
        for (i in 0 until mcount) m[i + 1] = mids[i]
        m[0] = ((h[0] + h[1]) * m[1] - h[0] * m[2]) / h[1]
        m[n - 1] = (-h[n - 2] * m[n - 3] + (h[n - 3] + h[n - 2]) * m[n - 2]) / h[n - 3]
        return DoubleArray(grid.size) { gi ->
            val g = grid[gi]
            var i = binarySearchLeft(x, g) - 1
            if (i < 0) i = 0
            if (i > n - 2) i = n - 2
            val hi = h[i]
            val t1 = x[i + 1] - g
            val dx = g - x[i]
            m[i] * t1 * t1 * t1 / (6 * hi) + m[i + 1] * dx * dx * dx / (6 * hi) +
                (y[i] - m[i] * hi * hi / 6) * t1 / hi +
                (y[i + 1] - m[i + 1] * hi * hi / 6) * dx / hi
        }
    }

    private fun evalHermite(
        x: DoubleArray,
        y: DoubleArray,
        h: DoubleArray,
        s: DoubleArray,
        grid: DoubleArray,
    ): DoubleArray {
        val n = x.size
        return DoubleArray(grid.size) { gi ->
            val g = grid[gi]
            var i = binarySearchLeft(x, g) - 1
            if (i < 0) i = 0
            if (i > n - 2) i = n - 2
            val hi = h[i]
            val t = (g - x[i]) / hi
            val t2 = t * t
            val t3 = t2 * t
            val h00 = 2 * t3 - 3 * t2 + 1
            val h10 = t3 - 2 * t2 + t
            val h01 = -2 * t3 + 3 * t2
            val h11 = t3 - t2
            h00 * y[i] + h10 * hi * s[i] + h01 * y[i + 1] + h11 * hi * s[i + 1]
        }
    }

    private fun solve3(
        a00: Double, a01: Double, a02: Double,
        a10: Double, a11: Double, a12: Double,
        a20: Double, a21: Double, a22: Double,
        b0: Double, b1: Double, b2: Double,
    ): DoubleArray {
        val det = a00 * (a11 * a22 - a12 * a21) - a01 * (a10 * a22 - a12 * a20) + a02 * (a10 * a21 - a11 * a20)
        val s0 = (b0 * (a11 * a22 - a12 * a21) - a01 * (b1 * a22 - a12 * b2) + a02 * (b1 * a21 - a11 * b2)) / det
        val s1 = (a00 * (b1 * a22 - a12 * b2) - b0 * (a10 * a22 - a12 * a20) + a02 * (a10 * b2 - b1 * a20)) / det
        val s2 = (a00 * (a11 * b2 - b1 * a21) - a01 * (a10 * b2 - b1 * a20) + b0 * (a10 * a21 - a11 * a20)) / det
        return doubleArrayOf(s0, s1, s2)
    }

    private fun thomas(lower: DoubleArray, diag: DoubleArray, upper: DoubleArray, rhs: DoubleArray): DoubleArray {
        val n = diag.size
        val c = upper.copyOf()
        val d = rhs.copyOf()
        val b = diag.copyOf()
        val a = lower
        for (i in 1 until n) {
            val w = a[i] / b[i - 1]
            b[i] -= w * c[i - 1]
            d[i] -= w * d[i - 1]
        }
        val x = DoubleArray(n)
        x[n - 1] = d[n - 1] / b[n - 1]
        for (i in n - 2 downTo 0) x[i] = (d[i] - c[i] * x[i + 1]) / b[i]
        return x
    }

    /** Same insertion point as numpy.searchsorted(..., side='left'). */
    fun binarySearchLeft(x: DoubleArray, value: Double): Int {
        var lo = 0
        var hi = x.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (x[mid] < value) lo = mid + 1 else hi = mid
        }
        return lo
    }

    fun clip(v: Double, lo: Double, hi: Double) = v.coerceIn(lo, hi)
}
