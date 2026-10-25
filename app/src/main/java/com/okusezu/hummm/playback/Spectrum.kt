package com.okusezu.hummm.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Taps the decoded PCM on its way to the AudioTrack so the visualiser can see
 * what's playing without RECORD_AUDIO (android.media.audiofx.Visualizer needs
 * it). Samples land in a per-channel ring; [read] returns the window that is
 * leaving the speaker now, i.e. delayed by the AudioTrack buffer, whose size
 * [bufferSizes] records when ExoPlayer picks it.
 */
@OptIn(UnstableApi::class)
object Spectrum : TeeAudioProcessor.AudioBufferSink {
    private const val RING = 1 shl 16 // ~1.4 s at 48 kHz
    /** Speaker/mixer latency on top of the AudioTrack buffer; a guess, phones vary. */
    private const val OUTPUT_LATENCY_US = 40_000L
    /** No samples for this long means paused or stopped: report silence. */
    private const val STALE_NS = 150_000_000L

    private val lock = Any()
    private val left = FloatArray(RING)
    private val right = FloatArray(RING)
    private var written = 0L
    private var lastWriteNs = 0L
    private var channels = 2
    private var encoding = C.ENCODING_PCM_16BIT

    @Volatile var sampleRate = 44_100; private set
    @Volatile private var bufferUs = 250_000L
    /** Only copy samples while the visualiser is on screen. */
    @Volatile var listening = false

    val bufferSizes = DefaultAudioSink.AudioTrackBufferSizeProvider { minBuf, enc, mode, frameSize, rate, bitrate, speed ->
        DefaultAudioSink.AudioTrackBufferSizeProvider.DEFAULT
            .getBufferSizeInBytes(minBuf, enc, mode, frameSize, rate, bitrate, speed)
            .also { if (mode == DefaultAudioSink.OUTPUT_MODE_PCM && frameSize > 0 && rate > 0) bufferUs = it.toLong() / frameSize * 1_000_000 / rate }
    }

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) = synchronized(lock) {
        sampleRate = sampleRateHz
        channels = channelCount
        this.encoding = encoding
        left.fill(0f)
        right.fill(0f)
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        if (!listening) return
        val b = buffer.order(ByteOrder.nativeOrder())
        synchronized(lock) {
            val ch = channels.coerceAtLeast(1)
            val width = when (encoding) {
                C.ENCODING_PCM_16BIT -> 2
                C.ENCODING_PCM_FLOAT -> 4
                else -> return
            }
            fun sample() = if (width == 2) b.short / 32768f else b.float
            while (b.remaining() >= width * ch) {
                val l = sample()
                val r = if (ch > 1) sample() else l
                repeat(ch - 2) { sample() }
                val i = (written and (RING - 1).toLong()).toInt()
                left[i] = l
                right[i] = r
                written++
            }
            lastWriteNs = System.nanoTime()
        }
    }

    /** Hands [size] frames ending at the playhead to [l] and [r]; false while nothing is playing. */
    fun read(l: FloatArray, r: FloatArray, size: Int = l.size): Boolean = synchronized(lock) {
        val now = System.nanoTime()
        if (written == 0L || now - lastWriteNs > STALE_NS) return false
        val rate = sampleRate.toLong()
        val lag = (bufferUs + OUTPUT_LATENCY_US) * rate / 1_000_000
        val since = (now - lastWriteNs) / 1000 * rate / 1_000_000
        val head = (written - lag + since).coerceAtMost(written)
        for (k in 0 until size) {
            val f = head - size + k
            if (f < 0 || written - f > RING) { l[k] = 0f; r[k] = 0f; continue }
            val i = (f and (RING - 1).toLong()).toInt()
            l[k] = left[i]
            r[k] = right[i]
        }
        true
    }
}
