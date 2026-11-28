package id.my.radioislamsragen.studio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import java.io.IOException
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

const val RATE = 44100

private fun grow(a: FloatArray, need: Int): FloatArray =
    if (a.size >= need) a else a.copyOf(max(need, a.size * 2))

/** Mendekode satu file audio menjadi PCM float stereo 44.1 kHz. */
class Deck(ctx: Context, uri: Uri) {
    private val ex = MediaExtractor()
    private val codec: MediaCodec
    val durationUs: Long
    var positionUs = 0L
        private set

    private var srcRate = RATE
    private var srcCh = 2
    private var isFloat = false
    private var inEos = false
    private var outEos = false
    private var src = FloatArray(32768)
    private var srcFrames = 0
    private var pos = 0.0
    private var out = FloatArray(32768)
    private var outFrames = 0
    private val info = MediaCodec.BufferInfo()

    init {
        ex.setDataSource(ctx, uri, null)
        var fmt: MediaFormat? = null
        for (i in 0 until ex.trackCount) {
            val f = ex.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                ex.selectTrack(i); fmt = f; break
            }
        }
        if (fmt == null) { ex.release(); throw IOException("Bukan file audio") }
        durationUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
        srcRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        srcCh = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        try {
            codec.configure(fmt, null, null, 0)
            codec.start()
        } catch (e: Exception) {
            codec.release(); ex.release(); throw e
        }
    }

    /** Mengisi [dst] (stereo berselang) sebanyak [frames]; kembalian = frame yang terisi. */
    fun read(dst: FloatArray, frames: Int): Int {
        var guard = 0
        while (outFrames < frames && !outEos && guard < 400) { pump(); guard++ }
        if (outEos) resample()
        val n = min(frames, outFrames)
        System.arraycopy(out, 0, dst, 0, n * 2)
        System.arraycopy(out, n * 2, out, 0, (outFrames - n) * 2)
        outFrames -= n
        positionUs += n * 1_000_000L / RATE
        return n
    }

    private fun pump() {
        if (!inEos) {
            val ii = codec.dequeueInputBuffer(2000)
            if (ii >= 0) {
                val b = codec.getInputBuffer(ii)!!
                val sz = ex.readSampleData(b, 0)
                if (sz < 0) {
                    codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    inEos = true
                } else {
                    codec.queueInputBuffer(ii, 0, sz, max(0L, ex.sampleTime), 0)
                    ex.advance()
                }
            }
        }
        val oi = codec.dequeueOutputBuffer(info, 2000)
        if (oi >= 0) {
            if (info.size > 0) {
                val b = codec.getOutputBuffer(oi)!!
                b.position(info.offset); b.limit(info.offset + info.size)
                val bb = b.slice().order(ByteOrder.nativeOrder())
                if (isFloat) {
                    val fb = bb.asFloatBuffer()
                    val frames = fb.remaining() / srcCh
                    src = grow(src, (srcFrames + frames) * 2)
                    for (f in 0 until frames) {
                        val l = fb.get(f * srcCh)
                        val r = if (srcCh > 1) fb.get(f * srcCh + 1) else l
                        src[(srcFrames + f) * 2] = l; src[(srcFrames + f) * 2 + 1] = r
                    }
                    srcFrames += frames
                } else {
                    val sb = bb.asShortBuffer()
                    val frames = sb.remaining() / srcCh
                    src = grow(src, (srcFrames + frames) * 2)
                    for (f in 0 until frames) {
                        val l = sb.get(f * srcCh) / 32768f
                        val r = if (srcCh > 1) sb.get(f * srcCh + 1) / 32768f else l
                        src[(srcFrames + f) * 2] = l; src[(srcFrames + f) * 2 + 1] = r
                    }
                    srcFrames += frames
                }
            }
            codec.releaseOutputBuffer(oi, false)
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outEos = true
        } else if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            val f = codec.outputFormat
            srcRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            srcCh = max(1, f.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
            isFloat = Build.VERSION.SDK_INT >= 24 && f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
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

    fun release() {
        try { codec.stop() } catch (_: Exception) {}
        try { codec.release() } catch (_: Exception) {}
        try { ex.release() } catch (_: Exception) {}
    }
}

/** Encoder AAC-LC bawaan Android; keluaran berupa paket ADTS siap kirim. */
class AacEncoder(bitrateK: Int, val channels: Int) {
    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val info = MediaCodec.BufferInfo()
    private var frames = 0L

    init {
        val f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, channels)
        f.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        f.setInteger(MediaFormat.KEY_BIT_RATE, bitrateK * 1000)
        f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    fun encode(pcm: ShortArray, count: Int, sink: (ByteArray) -> Unit) {
        var off = 0
        var tries = 0
        while (off < count && tries < 40) {
            val ii = codec.dequeueInputBuffer(5000)
            if (ii >= 0) {
                val b = codec.getInputBuffer(ii)!!
                b.clear()
                val n = min(count - off, b.remaining() / 2 / channels * channels)
                b.order(ByteOrder.nativeOrder()).asShortBuffer().put(pcm, off, n)
                val pts = frames * 1_000_000L / RATE
                codec.queueInputBuffer(ii, 0, n * 2, pts, 0)
                frames += n / channels
                off += n
            } else tries++
            drain(sink)
        }
        drain(sink)
    }

    private fun drain(sink: (ByteArray) -> Unit) {
        while (true) {
            val oi = codec.dequeueOutputBuffer(info, 0)
            if (oi >= 0) {
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                    val b = codec.getOutputBuffer(oi)!!
                    b.position(info.offset); b.limit(info.offset + info.size)
                    val len = info.size + 7
                    val p = ByteArray(len)
                    p[0] = 0xFF.toByte()
                    p[1] = 0xF1.toByte()
                    p[2] = ((1 shl 6) or (4 shl 2) or (channels shr 2)).toByte()
                    p[3] = (((channels and 3) shl 6) or (len shr 11)).toByte()
                    p[4] = ((len and 0x7FF) shr 3).toByte()
                    p[5] = (((len and 7) shl 5) or 0x1F).toByte()
                    p[6] = 0xFC.toByte()
                    b.get(p, 7, info.size)
                    sink(p)
                }
                codec.releaseOutputBuffer(oi, false)
            } else if (oi == MediaCodec.INFO_TRY_AGAIN_LATER) break
        }
    }

    fun release() {
        try { codec.stop() } catch (_: Exception) {}
        try { codec.release() } catch (_: Exception) {}
    }
}

/** Menyimpan rekaman (AAC/ADTS 128 kbps stereo) ke folder Musik/RaisStream. */
class RecWriter(ctx: android.content.Context) {
    val name: String = "RaisStream_" + java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", java.util.Locale.US).format(java.util.Date()) + ".aac"
    private val resolver = ctx.contentResolver
    private var uri: Uri? = null
    private val out: java.io.OutputStream
    private val enc = AacEncoder(128, 2)

    init {
        if (Build.VERSION.SDK_INT >= 29) {
            val v = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Audio.Media.DISPLAY_NAME, name)
                put(android.provider.MediaStore.Audio.Media.MIME_TYPE, "audio/aac")
                put(android.provider.MediaStore.Audio.Media.RELATIVE_PATH, android.os.Environment.DIRECTORY_MUSIC + "/RaisStream")
                put(android.provider.MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val u = resolver.insert(android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, v)
                ?: throw IOException("Tidak bisa membuat file rekaman")
            uri = u
            out = java.io.BufferedOutputStream(resolver.openOutputStream(u) ?: throw IOException("Tidak bisa menulis rekaman"), 65536)
        } else {
            val dir = java.io.File(ctx.getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC), "RaisStream").apply { mkdirs() }
            out = java.io.BufferedOutputStream(java.io.FileOutputStream(java.io.File(dir, name)), 65536)
        }
    }

    fun write(pcmStereo: ShortArray, count: Int) = enc.encode(pcmStereo, count) { out.write(it) }

    fun close() {
        try { out.flush(); out.close() } catch (_: Exception) {}
        enc.release()
        uri?.let {
            try {
                resolver.update(it, android.content.ContentValues().apply { put(android.provider.MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            } catch (_: Exception) {}
        }
    }
}
