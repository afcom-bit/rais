package id.my.radioislamsragen.studio

import de.sciss.jump3r.lowlevel.LameEncoder
import de.sciss.jump3r.mp3.MPEGMode
import javazoom.jl.decoder.Bitstream
import javazoom.jl.decoder.Decoder
import javazoom.jl.decoder.Header
import javazoom.jl.decoder.SampleBuffer
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.Mixer
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine
import kotlin.math.max
import kotlin.math.min

const val RATE = 44100

private fun grow(a: FloatArray, need: Int): FloatArray =
    if (a.size >= need) a else a.copyOf(max(need, a.size * 2))

/** Mendekode file MP3 menjadi PCM float stereo 44.1 kHz. */
class MpDeck(path: String) {
    private val file = File(path)
    private val bs: Bitstream
    private val dec = Decoder()
    private var pending: Header?
    val durationUs: Long
    var positionUs = 0L
        private set
    private var eos = false
    private var srcRate = RATE
    private var src = FloatArray(32768)
    private var srcFrames = 0
    private var pos = 0.0
    private var out = FloatArray(32768)
    private var outFrames = 0

    init {
        if (!file.exists()) throw IOException("File tidak ditemukan")
        bs = Bitstream(BufferedInputStream(FileInputStream(file), 65536))
        val h = bs.readFrame() ?: throw IOException("Bukan file MP3")
        pending = h
        srcRate = h.frequency()
        durationUs = (h.total_ms(file.length().toInt()) * 1000).toLong()
    }

    fun read(dst: FloatArray, frames: Int): Int {
        var guard = 0
        while (outFrames < frames && !eos && guard < 200) { decodeOne(); guard++ }
        val n = min(frames, outFrames)
        System.arraycopy(out, 0, dst, 0, n * 2)
        System.arraycopy(out, n * 2, out, 0, (outFrames - n) * 2)
        outFrames -= n
        positionUs += n * 1_000_000L / RATE
        return n
    }

    private fun decodeOne() {
        val h = pending ?: try { bs.readFrame() } catch (e: Exception) { null }
        pending = null
        if (h == null) { eos = true; resample(); return }
        try {
            val sb = dec.decodeFrame(h, bs) as SampleBuffer
            val ch = max(1, sb.channelCount)
            srcRate = sb.sampleFrequency
            val buf = sb.buffer
            val frames = sb.bufferLength / ch
            src = grow(src, (srcFrames + frames) * 2)
            for (f in 0 until frames) {
                val l = buf[f * ch] / 32768f
                val r = if (ch > 1) buf[f * ch + 1] / 32768f else l
                src[(srcFrames + f) * 2] = l; src[(srcFrames + f) * 2 + 1] = r
            }
            srcFrames += frames
        } catch (_: Exception) {
        } finally {
            try { bs.closeFrame() } catch (_: Exception) {}
        }
        resample()
    }

    private fun resample() {
        if (srcFrames == 0) return
        if (srcRate == RATE) {
            out = grow(out, (outFrames + srcFrames) * 2)
            System.arraycopy(src, 0, out, outFrames * 2, srcFrames * 2)
            outFrames += srcFrames; srcFrames = 0
            return
        }
        val step = srcRate.toDouble() / RATE
        out = grow(out, (outFrames + (srcFrames / step).toInt() + 4) * 2)
        while (pos + 1 < srcFrames) {
            val i = pos.toInt()
            val fr = (pos - i).toFloat()
            out[outFrames * 2] = src[i * 2] * (1 - fr) + src[i * 2 + 2] * fr
            out[outFrames * 2 + 1] = src[i * 2 + 1] * (1 - fr) + src[i * 2 + 3] * fr
            outFrames++
            pos += step
        }
        val drop = min(pos.toInt(), srcFrames)
        if (drop > 0) {
            System.arraycopy(src, drop * 2, src, 0, (srcFrames - drop) * 2)
            srcFrames -= drop
            pos -= drop
        }
    }

    fun release() { try { bs.close() } catch (_: Exception) {} }
}

/** Encoder MP3 (LAME versi Java). */
class Mp3Encoder(bitrate: Int, val channels: Int) {
    private val enc = LameEncoder(AudioFormat(RATE.toFloat(), 16, channels, true, false), bitrate,
        (if (channels == 1) MPEGMode.MONO else MPEGMode.STEREO).ordinal, 5, false)
    private val chunk = enc.getPCMBufferSize()
    private val outBuf = ByteArray(chunk * 2 + 8192)
    private var bytes = ByteArray(0)

    fun encode(pcm: ShortArray, count: Int, sink: (ByteArray) -> Unit) {
        if (bytes.size < count * 2) bytes = ByteArray(count * 2)
        for (i in 0 until count) {
            val v = pcm[i].toInt()
            bytes[i * 2] = (v and 0xFF).toByte()
            bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        var off = 0
        val total = count * 2
        while (off < total) {
            val n = min(chunk, total - off)
            val w = enc.encodeBuffer(bytes, off, n, outBuf)
            if (w > 0) sink(outBuf.copyOf(w))
            off += n
        }
    }

    fun finish(sink: (ByteArray) -> Unit) {
        try {
            val w = enc.encodeFinish(outBuf)
            if (w > 0) sink(outBuf.copyOf(w))
        } catch (_: Exception) {}
        try { enc.close() } catch (_: Exception) {}
    }
}

/** Daftar perangkat suara Windows (mic/line-in dan speaker). */
object Devices {
    val inFmt = AudioFormat(RATE.toFloat(), 16, 1, true, false)
    val outFmt = AudioFormat(RATE.toFloat(), 16, 2, true, false)

    private fun mixers(cls: Class<*>, fmt: AudioFormat): List<Mixer.Info> =
        AudioSystem.getMixerInfo().filter {
            try { AudioSystem.getMixer(it).isLineSupported(DataLine.Info(cls, fmt)) } catch (_: Exception) { false }
        }

    fun inputs(): List<String> = mixers(TargetDataLine::class.java, inFmt).map { it.name }
    fun outputs(): List<String> = mixers(SourceDataLine::class.java, outFmt).map { it.name }

    fun openInput(name: String): TargetDataLine {
        val info = DataLine.Info(TargetDataLine::class.java, inFmt)
        val m = AudioSystem.getMixerInfo().firstOrNull { it.name == name }
        val line = (if (m != null) AudioSystem.getMixer(m).getLine(info) else AudioSystem.getLine(info)) as TargetDataLine
        line.open(inFmt, 1024 * 2 * 8)
        line.start()
        return line
    }

    fun openOutput(name: String): SourceDataLine {
        val info = DataLine.Info(SourceDataLine::class.java, outFmt)
        val m = AudioSystem.getMixerInfo().firstOrNull { it.name == name }
        val line = (if (m != null) AudioSystem.getMixer(m).getLine(info) else AudioSystem.getLine(info)) as SourceDataLine
        line.open(outFmt, 1024 * 4 * 8)
        line.start()
        return line
    }
}
