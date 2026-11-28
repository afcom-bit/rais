package id.my.radioislamsragen.studio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Cuplikan data meter untuk layar LCD. Semua nilai 0..1. */
class MeterSnap(
    val wave: FloatArray = FloatArray(Meters.WAVE),
    val peakL: Float = 0f, val peakR: Float = 0f,
    val vuL: Float = 0f, val vuR: Float = 0f,
    val spec: FloatArray = FloatArray(Meters.BANDS),
    val specPeak: FloatArray = FloatArray(Meters.BANDS),
)

/** Mengolah campuran audio (stereo float 44.1 kHz) menjadi data VU, LED, gelombang, dan spektrum. */
class Meters {
    companion object {
        const val WAVE = 150
        const val BANDS = 32
        private const val N = 1024
        private const val RATE = 44100
    }

    private val ring = FloatArray(WAVE)
    private var head = 0
    private var peakL = 0f
    private var peakR = 0f
    private var vuL = 0f
    private var vuR = 0f
    private val spec = FloatArray(BANDS)
    private val specPeak = FloatArray(BANDS)
    private val re = FloatArray(N)
    private val im = FloatArray(N)
    private val win = FloatArray(N) { (0.5 - 0.5 * cos(2 * PI * it / (N - 1))).toFloat() }
    private val edges: IntArray = IntArray(BANDS + 1) { b ->
        val f = 50.0 * (16000.0 / 50.0).pow(b.toDouble() / BANDS)
        min(N / 2 - 1, max(1, (f * N / RATE).toInt()))
    }

    fun reset() {
        ring.fill(0f); peakL = 0f; peakR = 0f; vuL = 0f; vuR = 0f; spec.fill(0f); specPeak.fill(0f)
    }

    fun process(mix: FloatArray, frames: Int, spectrum: Boolean = true) {
        var pa = 0f; var pb = 0f
        var pl = 0f; var pr = 0f
        var sl = 0.0; var sr = 0.0
        val half = frames / 2
        for (i in 0 until frames) {
            val l = mix[i * 2]; val r = mix[i * 2 + 1]
            val al = abs(l); val ar = abs(r)
            pl = max(pl, al); pr = max(pr, ar)
            sl += l * l; sr += r * r
            val pk = max(al, ar)
            if (i < half) pa = max(pa, pk) else pb = max(pb, pk)
            if (i < N) { re[i] = (l + r) * 0.5f * win[i]; im[i] = 0f }
        }
        for (i in frames until N) { re[i] = 0f; im[i] = 0f }
        ring[head] = pa; head = (head + 1) % WAVE
        ring[head] = pb; head = (head + 1) % WAVE

        peakL = max(pl, peakL * 0.86f)
        peakR = max(pr, peakR * 0.86f)
        val rl = sqrt(sl / max(1, frames)).toFloat()
        val rr = sqrt(sr / max(1, frames)).toFloat()
        vuL += (rl - vuL) * 0.22f
        vuR += (rr - vuR) * 0.22f

        if (!spectrum) return
        fft()
        for (b in 0 until BANDS) {
            var m = 0f
            for (k in edges[b]..max(edges[b], edges[b + 1] - 1)) m = max(m, sqrt(re[k] * re[k] + im[k] * im[k]))
            val db = 20 * log10(max(1e-6f, m / (N / 4f)))
            val v = ((db + 60f) / 60f).coerceIn(0f, 1f)
            spec[b] = max(v, spec[b] * 0.82f)
            specPeak[b] = if (spec[b] >= specPeak[b]) spec[b] else max(0f, specPeak[b] - 0.012f)
        }
    }

    fun snapshot(): MeterSnap {
        val w = FloatArray(WAVE)
        for (k in 0 until WAVE) w[k] = ring[(head + k) % WAVE]
        return MeterSnap(w, peakL, peakR, vuL, vuR, spec.copyOf(), specPeak.copyOf())
    }

    private fun fft() {
        var j = 0
        for (i in 1 until N) {
            var bit = N shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { val t = re[i]; re[i] = re[j]; re[j] = t; val u = im[i]; im[i] = im[j]; im[j] = u }
        }
        var len = 2
        while (len <= N) {
            val ang = -2 * PI / len
            val wr = cos(ang).toFloat(); val wi = sin(ang).toFloat()
            var i = 0
            while (i < N) {
                var cr = 1f; var ci = 0f
                for (k in 0 until len / 2) {
                    val a = i + k; val b = a + len / 2
                    val xr = re[b] * cr - im[b] * ci
                    val xi = re[b] * ci + im[b] * cr
                    re[b] = re[a] - xr; im[b] = im[a] - xi
                    re[a] += xr; im[a] += xi
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr; cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }
}
