package com.okusezu.hummm.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.random.Random

class CavaTest {
    private val rate = 48_000

    private fun tone(hz: Double, size: Int) = FloatArray(size) { (0.5 * sin(2 * PI * hz * it / rate)).toFloat() }

    /** The bar whose log-spaced range should hold [hz]. */
    private fun expectedBar(hz: Double, bars: Int) = (bars * ln(hz / 50) / ln(10_000.0 / 50)).toInt()

    @Test fun toneLightsItsBar() {
        val cava = Cava(12, rate, channels = 1)
        for (hz in listOf(80.0, 440.0, 2_000.0, 7_000.0)) {
            val out = FloatArray(12)
            cava.spectrum(tone(hz, cava.size), out)
            val loudest = out.indices.maxBy { out[it] }
            assertEquals("$hz Hz", expectedBar(hz, 12).toDouble(), loudest.toDouble(), 1.0)
        }
    }

    @Test fun autosensSettlesUnderTheTopAndSilenceFalls() {
        val cava = Cava(8, rate, channels = 1)
        val rnd = Random(1)
        val heights = FloatArray(8)
        repeat(600) {
            // Pink-ish: a few tones per octave with 1/√f amplitude.
            val s = FloatArray(cava.size)
            for (k in 0 until 24) {
                val hz = 50 * exp(ln(200.0) * k / 24)
                val a = 0.3 / kotlin.math.sqrt(hz / 50) * (0.5 + rnd.nextDouble())
                for (i in s.indices) s[i] += (a * sin(2 * PI * hz * i / rate + k)).toFloat()
            }
            cava.spectrum(s, heights)
            cava.smooth(heights, 1f / 60)
        }
        assertTrue("peaks near the top: ${heights.toList()}", heights.max() in 0.6f..1f)
        assertTrue("every band moves: ${heights.toList()}", heights.min() > 0.05f)
        repeat(240) { heights.fill(0f); cava.smooth(heights, 1f / 60) }
        assertTrue("falls to zero: ${heights.toList()}", heights.max() < 0.01f)
    }
}
