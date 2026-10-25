package com.okusezu.hummm.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import com.okusezu.hummm.playback.Spectrum
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/*
 * The visualiser's styles. Each is a Scene laid out on the same character
 * grid as cava in a terminal, fed one AudioFrame per screen frame.
 */

/** In swipe order; [label] is also what Settings stores. */
enum class VisStyle(val label: String) {
    CAVA("cava"), MONO("mono"), WAVEFORM("waveform"), DOTS("dots"), CIRCLE("circle"), MATRIX("matrix"), FIRE("fire");

    fun next(step: Int): VisStyle = entries[(ordinal + step).mod(entries.size)]

    companion object {
        fun named(label: String): VisStyle = entries.firstOrNull { it.label == label } ?: CAVA
    }
}

/** The screen as whole character cells of [cw] × [ch] pixels, centred. */
class Grid(val cw: Float, val ch: Float, width: Int, height: Int) {
    val w = width.toFloat()
    val h = height.toFloat()
    val cols = (w / cw).toInt().coerceAtLeast(1)
    val rows = (h / ch).toInt().coerceAtLeast(1)
    val x0 = (w - cols * cw) / 2
    val y0 = (h - rows * ch) / 2
    val bottom = y0 + rows * ch

    /** Left edge for [cells] cells centred in the grid. */
    fun centred(cells: Int): Float = x0 + (cols - cells) / 2 * cw
}

/** The window of samples leaving the speaker now, per channel and mixed down. */
class AudioFrame(size: Int = 4096) {
    val l = FloatArray(size)
    val r = FloatArray(size)
    val mono = FloatArray(size)
    var rate = 44_100; private set
    var live = false; private set

    fun read() {
        rate = Spectrum.sampleRate
        live = Spectrum.read(l, r)
        if (live) for (i in mono.indices) mono[i] = (l[i] + r[i]) / 2
    }
}

class VisColors(val accent: Color, val dim: Color, val bright: Color)

/** Characters for the text styles, laid out once so a frame only has to draw them. */
class Glyphs(measurer: TextMeasurer, style: TextStyle, chars: String = "0123456789ABCDEF<>=+*#%&$@?/\\|") {
    val layouts: List<TextLayoutResult> = chars.map { measurer.measure(it.toString(), style) }
}

abstract class Scene(val g: Grid) {
    abstract fun step(dt: Float, audio: AudioFrame)
    abstract fun DrawScope.draw(c: VisColors)

    /** A bar [cells] wide growing in eighths of a cell, as ▁▂▃▄▅▆▇█ do in a terminal. */
    protected fun DrawScope.bar(x: Float, v: Float, cells: Int, color: Color) {
        val eighths = (v * g.rows * 8).roundToInt()
        if (eighths == 0) return
        val h = eighths * g.ch / 8
        drawRect(color, Offset(x, g.bottom - h), Size(cells * g.cw, h))
    }

    /** Centre of braille dot ([x], [y]) counted in dots (2 per cell across, 4 down) from the top left. */
    protected fun dot(x: Int, y: Int) = Offset(
        g.x0 + x / 2 * g.cw + (x % 2 + 0.5f) * g.cw / 2,
        g.y0 + y / 4 * g.ch + (y % 4 + 0.5f) * g.ch / 4,
    )

    protected fun DrawScope.dots(points: List<Offset>, color: Color) {
        if (points.isNotEmpty()) drawPoints(points, PointMode.Points, color, strokeWidth = g.cw * 0.3f, cap = StrokeCap.Round)
    }
}

fun scene(style: VisStyle, grid: Grid, glyphs: Glyphs): Scene = when (style) {
    VisStyle.CAVA -> StereoBars(grid)
    VisStyle.MONO -> MonoBars(grid)
    VisStyle.WAVEFORM -> Waveform(grid)
    VisStyle.DOTS -> Dots(grid)
    VisStyle.CIRCLE -> Circle(grid)
    VisStyle.MATRIX -> Matrix(grid, glyphs)
    VisStyle.FIRE -> Fire(grid)
}

/** cava's 0..1 bar heights for [bars] bands: left then right channel when [stereo], else mixed down. */
private class Bands(val bars: Int, private val stereo: Boolean) {
    val heights = FloatArray(if (stereo) bars * 2 else bars)
    private var cava: Cava? = null

    fun step(dt: Float, a: AudioFrame) {
        val c = cava?.takeIf { it.rate == a.rate } ?: Cava(bars, a.rate, if (stereo) 2 else 1).also { cava = it }
        when {
            !a.live -> heights.fill(0f)
            stereo -> { c.spectrum(a.l, heights, 0); c.spectrum(a.r, heights, bars) }
            else -> c.spectrum(a.mono, heights)
        }
        c.smooth(heights, dt)
    }

    /** For styles spread across the screen: the band for column [col] of [cols], bass in the middle. */
    fun mirrored(col: Int, cols: Int): Float {
        val d = abs(col - (cols - 1) / 2f) / (cols / 2f)
        return heights[(d * (bars - 1)).roundToInt().coerceIn(0, bars - 1)]
    }
}

/**
 * cava's default: stereo, so the left channel runs bass-in-the-middle out to
 * the left edge and the right channel mirrors it. Bars two cells wide, one apart.
 */
private class StereoBars(g: Grid) : Scene(g) {
    private val perSide = ((g.cols + 1) / 3 / 2).coerceAtLeast(1)
    private val bands = Bands(perSide, stereo = true)

    override fun step(dt: Float, audio: AudioFrame) = bands.step(dt, audio)

    override fun DrawScope.draw(c: VisColors) {
        val bars = perSide * 2
        val x0 = g.centred(bars * 3 - 1)
        for (b in 0 until bars) {
            val v = if (b < perSide) bands.heights[perSide - 1 - b] else bands.heights[b]
            bar(x0 + b * 3 * g.cw, v, 2, c.accent)
        }
    }
}

/** cava's mono mode: one mixed-down channel, bass on the left through to treble on the right. */
private class MonoBars(g: Grid) : Scene(g) {
    private val bands = Bands(((g.cols + 1) / 3).coerceAtLeast(1), stereo = false)

    override fun step(dt: Float, audio: AudioFrame) = bands.step(dt, audio)

    override fun DrawScope.draw(c: VisColors) {
        val x0 = g.centred(bands.bars * 3 - 1)
        for (b in 0 until bands.bars) bar(x0 + b * 3 * g.cw, bands.heights[b], 2, c.accent)
    }
}

/** An oscilloscope traced in braille, triggered on a rising zero crossing so the wave stands still. */
private class Waveform(g: Grid) : Scene(g) {
    private val dotsX = g.cols * 2
    private val dotsY = g.rows * 4
    private val ys = IntArray(dotsX) { dotsY / 2 }
    private var level = 0.05f
    private val points = ArrayList<Offset>()

    override fun step(dt: Float, audio: AudioFrame) {
        if (!audio.live) { ys.fill(dotsY / 2); return }
        val m = audio.mono
        val span = minOf(m.size / 2, 1536)
        var start = m.size - span
        for (i in m.size - span downTo 1) if (m[i - 1] <= 0f && m[i] > 0f) { start = i; break }
        var peak = 0f
        for (k in start until start + span) peak = maxOf(peak, abs(m[k]))
        // Autosens: jump up to a louder peak, ease back down when it gets quieter.
        level = maxOf(peak, level * 0.97f.pow(dt * 60), 0.02f)
        val mid = (dotsY - 1) / 2f
        for (x in 0 until dotsX) {
            val i0 = start + x * span / dotsX
            val i1 = maxOf(start + (x + 1) * span / dotsX, i0 + 1)
            var s = 0f
            for (i in i0 until i1) s += m[i]
            s /= i1 - i0
            ys[x] = (mid - s / level * mid * 0.9f).roundToInt().coerceIn(0, dotsY - 1)
        }
    }

    override fun DrawScope.draw(c: VisColors) {
        points.clear()
        for (x in 0 until dotsX) {
            // Join each column to the last so steep edges stay a continuous line.
            val from = if (x == 0) ys[0] else minOf(ys[x - 1], ys[x])
            val to = if (x == 0) ys[0] else maxOf(ys[x - 1], ys[x])
            for (y in from..to) points += dot(x, y)
        }
        dots(points, c.accent)
    }
}

/** Mono spectrum as columns of braille dots, with peak dots that hang, then drop. */
private class Dots(g: Grid) : Scene(g) {
    private val bands = Bands((g.cols / 2).coerceAtLeast(1), stereo = false)
    private val peaks = FloatArray(bands.bars)
    private val hold = FloatArray(bands.bars)
    private val points = ArrayList<Offset>()
    private val caps = ArrayList<Offset>()

    override fun step(dt: Float, audio: AudioFrame) {
        bands.step(dt, audio)
        for (n in 0 until bands.bars) {
            val v = bands.heights[n]
            when {
                v >= peaks[n] -> { peaks[n] = v; hold[n] = 0.5f }
                hold[n] > 0f -> hold[n] -= dt
                else -> peaks[n] = (peaks[n] - 0.6f * dt).coerceAtLeast(v)
            }
        }
    }

    override fun DrawScope.draw(c: VisColors) {
        points.clear(); caps.clear()
        val dotsY = g.rows * 4
        val first = ((g.centred(bands.bars * 2 - 1) - g.x0) / g.cw).roundToInt() * 2
        for (n in 0 until bands.bars) {
            val x = first + n * 4
            val top = (bands.heights[n] * dotsY).roundToInt()
            for (k in 0 until top) { points += dot(x, dotsY - 1 - k); points += dot(x + 1, dotsY - 1 - k) }
            val p = (peaks[n] * dotsY).roundToInt()
            if (p > top + 1) { caps += dot(x, dotsY - p); caps += dot(x + 1, dotsY - p) }
        }
        dots(points, c.accent)
        dots(caps, c.bright)
    }
}

/**
 * Stereo bars radiating from a ring, bass at the top: the right channel runs
 * clockwise down the right side, the left channel mirrors it.
 */
private class Circle(g: Grid) : Scene(g) {
    private val perSide = 36
    private val bands = Bands(perSide, stereo = true)

    override fun step(dt: Float, audio: AudioFrame) = bands.step(dt, audio)

    override fun DrawScope.draw(c: VisColors) {
        val centre = Offset(g.w / 2, g.h / 2)
        val r = radius(g.w, g.h)
        val len = minOf(g.w, g.h) * 0.28f
        val stroke = (PI * r / perSide * 0.55).toFloat()
        for (side in 0..1) for (k in 0 until perSide) {
            val v = bands.heights[side * perSide + k]
            val theta = PI * (k + 0.5) / perSide
            val dir = Offset((sin(theta) * if (side == 0) -1 else 1).toFloat(), -cos(theta).toFloat())
            val l = maxOf(v * len, stroke / 2)
            drawLine(c.accent, centre + dir * r, centre + dir * (r + l), stroke, StrokeCap.Butt)
        }
        drawCircle(c.dim, r * 0.94f, centre, style = Stroke(g.cw * 0.12f))
    }

    companion object {
        /** The ring's radius in pixels; the cover art sits inside it. */
        fun radius(w: Float, h: Float) = minOf(w, h) * 0.2f
    }
}

/** Ring radius for the circle style, so the screen can fit the cover inside it. */
fun circleRadius(w: Float, h: Float) = Circle.radius(w, h)

/**
 * Falling streams of characters. Each column's speed and trail follow its
 * band (bass in the middle); with nothing playing they drizzle slowly.
 */
private class Matrix(g: Grid, private val glyphs: Glyphs) : Scene(g) {
    private val bands = Bands(((g.cols + 1) / 2).coerceAtLeast(1), stereo = false)
    private val head = FloatArray(g.cols) { Random.nextFloat() * g.rows }
    private val trail = FloatArray(g.cols) { 4f }
    private val chars = Array(g.rows) { IntArray(g.cols) { Random.nextInt(glyphs.layouts.size) } }

    override fun step(dt: Float, audio: AudioFrame) {
        bands.step(dt, audio)
        for (col in 0 until g.cols) {
            val e = bands.mirrored(col, g.cols)
            head[col] += (5f + 45f * e) * dt
            trail[col] = maxOf(trail[col] - 8f * dt, 4f + e * g.rows * 0.6f)
            if (head[col] - trail[col] > g.rows) head[col] = -Random.nextFloat() * g.rows * 0.4f
        }
        repeat(maxOf(1, g.cols * g.rows / 40)) {
            chars[Random.nextInt(g.rows)][Random.nextInt(g.cols)] = Random.nextInt(glyphs.layouts.size)
        }
    }

    override fun DrawScope.draw(c: VisColors) {
        for (col in 0 until g.cols) {
            val top = head[col].toInt()
            val n = trail[col].toInt()
            for (t in 0..n) {
                val row = top - t
                if (row < 0 || row >= g.rows) continue
                drawText(
                    glyphs.layouts[chars[row][col]],
                    color = if (t == 0) c.bright else c.accent,
                    topLeft = Offset(g.x0 + col * g.cw, g.y0 + row * g.ch),
                    alpha = 1f - t.toFloat() / (n + 1),
                )
            }
        }
    }
}

/**
 * Flames of shaded half-cells, the classic demo-scene fire: the bottom row
 * burns steadily and the heat rises, drifting and cooling. Each band (bass in
 * the middle) sets how fast its columns cool, so the music sets flame height
 * at once; pulsing the bottom row instead sends bands of heat floating up.
 */
private class Fire(g: Grid) : Scene(g) {
    private val fw = g.cols
    private val fh = g.rows * 2
    private val heat = FloatArray(fw * fh)
    private val bands = Bands(((g.cols + 1) / 2).coerceAtLeast(1), stereo = false)
    /** Average cooling per row at full strength, so the flame reaches ~3/4 of the way up. */
    private val cooling = 1f / (fh * 0.75f)
    private val loss = FloatArray(fw)
    private var pending = 0f

    override fun step(dt: Float, audio: AudioFrame) {
        bands.step(dt, audio)
        pending = minOf(pending + dt, 4 * TICK)
        while (pending >= TICK) { pending -= TICK; spread() }
    }

    private fun spread() {
        val base = (fh - 1) * fw
        for (x in 0 until fw) {
            val e = bands.mirrored(x, fw)
            heat[base + x] = if (e < 0.02f) 0f else 0.85f + 0.15f * Random.nextFloat()
            loss[x] = 2 * cooling / e.coerceAtLeast(0.04f)
        }
        for (y in 0 until fh - 1) for (x in 0 until fw) {
            val from = (x + Random.nextInt(-1, 2)).coerceIn(0, fw - 1)
            heat[y * fw + x] = (heat[(y + 1) * fw + from] - Random.nextFloat() * loss[x]).coerceAtLeast(0f)
        }
    }

    override fun DrawScope.draw(c: VisColors) {
        val hot = lerp(c.accent, c.bright, 0.6f)
        val size = Size(g.cw, g.ch / 2)
        for (y in 0 until fh) for (x in 0 until fw) {
            val v = heat[y * fw + x]
            if (v < 0.06f) continue
            // Eight shades; fewer turn the heat's gradient into flat bands.
            val level = (v * 8).toInt().coerceAtMost(7)
            val color = if (level >= 6) lerp(c.accent, hot, (level - 5) / 2f) else c.accent
            drawRect(color, Offset(g.x0 + x * g.cw, g.y0 + y * g.ch / 2), size, 0.15f + 0.85f * level / 7)
        }
    }

    private companion object {
        const val TICK = 1f / 60
    }
}
