package com.okusezu.hummm.ui

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * cava's processing, enough to look like it: per channel a Hann-windowed FFT,
 * bars on log-spaced cutoffs from 50 Hz to 10 kHz (each the band's peak bin), then cava's gravity
 * falloff, integral smoothing (noise_reduction = 0.77) and autosens, so the
 * loudest bar sits just under the top whatever the volume.
 */
class Cava(val bars: Int, val rate: Int, channels: Int = 2, val size: Int = 4096) {
    private val total = bars * channels
    private val window = FloatArray(size) { (0.5 - 0.5 * cos(2 * PI * it / (size - 1))).toFloat() }
    private val re = FloatArray(size)
    private val im = FloatArray(size)
    private val lo = IntArray(bars)
    private val hi = IntArray(bars)
    private val weight = FloatArray(bars)
    private val peak = FloatArray(total)
    private val fall = FloatArray(total)
    private val prev = FloatArray(total)
    private val mem = FloatArray(total)
    private var sens = 1f
    private var sensInit = true

    init {
        // Like cava, nudge each bar's lowest bin past the previous bar's so no two share one.
        val binHz = rate.toFloat() / size
        var last = 0
        for (n in 0 until bars) {
            val f0 = LOW * exp(ln(HIGH / LOW) * n / bars)
            val f1 = LOW * exp(ln(HIGH / LOW) * (n + 1) / bars)
            lo[n] = maxOf((f0 / binHz).toInt(), last + 1)
            hi[n] = maxOf((f1 / binHz).toInt(), lo[n])
            last = hi[n]
            weight[n] = sqrt(sqrt(f0 * f1)) // music falls off ~1/√f; this flattens it
        }
    }

    /** Raw bar heights for one channel's samples, written to out[offset until offset + bars]. */
    fun spectrum(samples: FloatArray, out: FloatArray, offset: Int = 0) {
        for (i in 0 until size) { re[i] = samples[i] * window[i]; im[i] = 0f }
        fft(re, im)
        for (n in 0 until bars) {
            // The loudest bin, not the mean, so a lone note in a wide treble band still shows.
            var top = 0f
            for (k in lo[n]..hi[n].coerceAtMost(size / 2 - 1)) top = maxOf(top, hypot(re[k], im[k]))
            out[offset + n] = top * weight[n] / size
        }
    }

    /** Smooths [raw] (all channels' bars side by side) into 0..1 heights in place; [dt] in seconds. */
    fun smooth(raw: FloatArray, dt: Float) {
        val frames = (dt * 60f).coerceIn(0.25f, 4f)
        var overshoot = false
        var silent = true
        for (n in 0 until total) {
            var v = raw[n] * sens
            if (raw[n] > 1e-6f) silent = false
            if (v < prev[n]) {
                v = (peak[n] * (1f - fall[n] * fall[n])).coerceAtLeast(0f)
                fall[n] += 0.028f * frames
            } else {
                peak[n] = v
                fall[n] = 0f
            }
            prev[n] = v
            v += mem[n] * NOISE_REDUCTION
            mem[n] = v
            if (v > 1f) { overshoot = true; v = 1f }
            raw[n] = v
        }
        if (overshoot) { sens *= 0.98f; sensInit = false }
        else if (!silent) { sens *= 1.002f; if (sensInit) sens *= 1.1f }
    }

    companion object {
        private const val LOW = 50f
        private const val HIGH = 10_000f
        private const val NOISE_REDUCTION = 0.77f

        /** In-place radix-2 FFT; sizes must be powers of two. */
        fun fft(re: FloatArray, im: FloatArray) {
            val n = re.size
            var j = 0
            for (i in 1 until n) {
                var bit = n shr 1
                while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
                j = j xor bit
                if (i < j) {
                    re[i] = re[j].also { re[j] = re[i] }
                    im[i] = im[j].also { im[j] = im[i] }
                }
            }
            var len = 2
            while (len <= n) {
                val ang = -2 * PI / len
                val wr = cos(ang).toFloat(); val wi = sin(ang).toFloat()
                for (i in 0 until n step len) {
                    var cr = 1f; var ci = 0f
                    for (k in 0 until len / 2) {
                        val a = i + k; val b = a + len / 2
                        val tr = re[b] * cr - im[b] * ci
                        val ti = re[b] * ci + im[b] * cr
                        re[b] = re[a] - tr; im[b] = im[a] - ti
                        re[a] += tr; im[a] += ti
                        val nr = cr * wr - ci * wi
                        ci = cr * wi + ci * wr
                        cr = nr
                    }
                }
                len = len shl 1
            }
        }
    }
}
