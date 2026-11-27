package com.harmonic.app

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.harmonic.dsp.PhoneAnalysis
import com.harmonic.dsp.analyzePhone
import kotlin.math.abs
import kotlin.math.sqrt

class AccelRecorder(context: Context) : SensorEventListener {
    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val samples = ArrayList<Sample>(4096)
    private val trace = FloatArray(TRACE_LEN)
    private var traceIdx = 0
    private var traceFilled = 0
    private var mean = 0.0
    private var sensor0 = 0L
    private var wall0 = 0.0
    private var recording = false
    private var lastTapNs = 0L
    private var pendingTaps = 0

    @Volatile var lastMagnitude: Float = 0f
        private set

    val available: Boolean get() = sensor != null

    @Synchronized
    fun start() {
        samples.clear()
        sensor0 = 0L
        mean = 0.0
        traceIdx = 0
        traceFilled = 0
        pendingTaps = 0
        lastTapNs = 0L
        lastMagnitude = 0f
        recording = true
        sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_FASTEST)
    }

    @Synchronized
    fun stop(): Recording {
        recording = false
        sensorManager.unregisterListener(this)
        val copy = samples.toList()
        val n = copy.size
        val raw = Array(n) { DoubleArray(3) }
        val ts = DoubleArray(n)
        for (i in 0 until n) {
            raw[i][0] = copy[i].x
            raw[i][1] = copy[i].y
            raw[i][2] = copy[i].z
            ts[i] = copy[i].t
        }
        val duration = if (n >= 2) ts.last() - ts.first() else 0.0
        val fs = if (duration > 0) (n - 1) / duration else 0.0
        return Recording(raw, ts, fs, duration)
    }

    @Synchronized
    fun snapshotTrace(): FloatArray {
        if (traceFilled == 0) return floatArrayOf()
        val out = FloatArray(traceFilled)
        val start = if (traceFilled < TRACE_LEN) 0 else traceIdx
        for (i in 0 until traceFilled) {
            out[i] = trace[(start + i) % TRACE_LEN]
        }
        return out
    }

    @Synchronized
    fun consumeTaps(): Int {
        val n = pendingTaps
        pendingTaps = 0
        return n
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!recording) return
        if (sensor0 == 0L) {
            sensor0 = event.timestamp
            wall0 = System.currentTimeMillis() / 1000.0
        }
        val t = wall0 + (event.timestamp - sensor0) / 1e9
        val x = event.values[0].toDouble()
        val y = event.values[1].toDouble()
        val z = event.values[2].toDouble()
        val mag = sqrt(x * x + y * y + z * z).toFloat()
        lastMagnitude = mag
        mean = if (mean == 0.0) mag.toDouble() else mean * (1.0 - MEAN_ALPHA) + mag * MEAN_ALPHA
        val ac = (mag - mean).toFloat()
        synchronized(this) {
            val last = samples.lastOrNull()?.t ?: Double.NEGATIVE_INFINITY
            if (t > last) {
                samples.add(Sample(t, x, y, z))
            }
            trace[traceIdx] = ac
            traceIdx = (traceIdx + 1) % TRACE_LEN
            if (traceFilled < TRACE_LEN) traceFilled++
            if (abs(ac) >= TAP_DELTA && event.timestamp - lastTapNs > TAP_REFRACTORY_NS) {
                lastTapNs = event.timestamp
                pendingTaps++
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private data class Sample(val t: Double, val x: Double, val y: Double, val z: Double)

    companion object {
        private const val TRACE_LEN = 256
        private const val MEAN_ALPHA = 0.04
        private const val TAP_DELTA = 2.0f
        private const val TAP_REFRACTORY_NS = 250_000_000L
    }
}

data class Recording(
    val raw: Array<DoubleArray>,
    val timestamps: DoubleArray,
    val measuredFs: Double,
    val durationS: Double,
)

fun Recording.analyze(
    rpm: Double,
    isoClass: String,
    thresholds: Map<String, com.harmonic.dsp.ZoneThresholds>? = null,
): PhoneAnalysis = analyzePhone(raw, timestamps, rpm, isoClass, thresholds = thresholds)
