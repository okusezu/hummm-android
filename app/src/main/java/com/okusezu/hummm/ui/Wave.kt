package com.okusezu.hummm.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/*
 * hummm's mark: a sound wave whose outline swells into two rounded lobes (in
 * the spirit of the Arctic Monkeys "AM" cover), as drawn in braille in the
 * desktop app's header. While music plays a gentle shimmer runs through it.
 */

/** 0..1 amplitude at x (0..1): flat lead-in, two lobes meeting low in the middle, flat tail. */
private fun envelope(x: Float): Float {
    val lo = 0.125f; val mid = 0.5f; val hi = 0.875f
    if (x < lo || x > hi) return 0f
    val t = if (x < mid) (x - lo) / (mid - lo) else (x - mid) / (hi - mid)
    return sin(PI * t).toFloat().coerceAtLeast(0f).pow(0.7f)
}

@Composable
private fun phase(live: Boolean): Float {
    if (!live) return 0f
    val t = rememberInfiniteTransition(label = "wave")
    val p by t.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart), label = "phase")
    return p
}

/** The logo. Vertical bars whose heights trace the two lobes. */
@Composable
fun WaveLogo(live: Boolean, modifier: Modifier = Modifier, width: Dp = 64.dp, height: Dp = 22.dp, color: Color = MaterialTheme.colorScheme.primary) {
    val p = phase(live)
    Canvas(modifier.size(width, height)) {
        val bars = 16
        val gap = size.width / bars
        val stroke = gap * 0.55f
        for (i in 0 until bars) {
            val x = (i + 0.5f) / bars
            var e = envelope(x)
            if (live && e > 0) e *= 0.78f + 0.22f * sin(i * 1.7f - p * 2) * sin(i * 0.45f + p)
            val h = (size.height * e).coerceAtLeast(stroke)
            val cx = gap * (i + 0.5f)
            drawLine(color, Offset(cx, (size.height - h) / 2), Offset(cx, (size.height + h) / 2), stroke, StrokeCap.Butt)
        }
    }
}

/**
 * Seek bar: the played part is a travelling wave, the rest a flat line,
 * like the desktop app's progress bar, with a block cursor. Tap or drag to seek.
 */
@Composable
fun WaveSeekBar(
    progress: Float,
    live: Boolean,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    track: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
) {
    val p = phase(live)
    var dragging by remember { mutableStateOf<Float?>(null) }
    val shown = dragging ?: progress.coerceIn(0f, 1f)
    Box(
        modifier
            .fillMaxWidth()
            .height(36.dp)
            .pointerInput(Unit) {
                detectTapGestures { onSeek((it.x / size.width).coerceIn(0f, 1f)) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { dragging = (it.x / size.width).coerceIn(0f, 1f) },
                    onDragEnd = { dragging?.let(onSeek); dragging = null },
                    onDragCancel = { dragging = null },
                    onHorizontalDrag = { change, _ -> dragging = (change.position.x / size.width).coerceIn(0f, 1f) },
                )
            }
    ) {
        Canvas(Modifier.fillMaxWidth().height(36.dp)) {
            val mid = size.height / 2
            val end = size.width * shown
            val amp = if (live || dragging != null) size.height * 0.14f else size.height * 0.05f
            val path = Path().apply {
                moveTo(0f, mid)
                var x = 0f
                while (x <= end) {
                    lineTo(x, mid + amp * sin(x / 9.dp.toPx() - p * 2))
                    x += 2f
                }
            }
            drawLine(track, Offset(end, mid), Offset(size.width, mid), 2.dp.toPx(), StrokeCap.Butt)
            drawPath(path, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Butt))
            val w = 8.dp.toPx(); val h = if (dragging != null) 22.dp.toPx() else 18.dp.toPx()
            drawRect(color, Offset((end - w / 2).coerceIn(0f, size.width - w), mid - h / 2), Size(w, h))
        }
    }
}

/** Busy indicator: a braille spinner, as a terminal program would show. */
@Composable
fun Spinner(modifier: Modifier = Modifier, label: String = "", color: Color = MaterialTheme.colorScheme.primary) {
    val t = rememberInfiniteTransition(label = "spin")
    val f by t.animateFloat(0f, SPIN.length.toFloat(), infiniteRepeatable(tween(SPIN.length * 80, easing = LinearEasing)), label = "frame")
    Text(SPIN[f.toInt().coerceIn(0, SPIN.lastIndex)] + if (label.isEmpty()) "" else " $label", modifier, color = color, style = MaterialTheme.typography.bodyLarge)
}

private const val SPIN = "⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏"
