package com.okusezu.hummm.ui

import android.app.Activity
import android.content.ContextWrapper
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.okusezu.hummm.playback.CastStatus
import com.okusezu.hummm.playback.Spectrum
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Full-screen cava: stereo by default like cava's config, so the left channel
 * runs bass-in-the-middle out to the left edge and the right channel mirrors it.
 * Bars are two cells wide with a one-cell gap and grow in eighths of a cell,
 * exactly as cava's ▁▂▃▄▅▆▇█ stack in a terminal. Tap or go back to close.
 */
@Composable
fun Visualiser(onClose: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val casting by CastStatus.device.collectAsState()
    val cell = rememberTextMeasurer().measure("█", MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp)).size
    var hint by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { delay(2500); hint = false }
    ImmersiveWhileShown()
    DisposableEffect(Unit) {
        Spectrum.listening = true
        onDispose { Spectrum.listening = false }
    }

    BoxWithConstraints(
        Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClose)
    ) {
        val px = with(LocalDensity.current) { constraints.maxWidth to constraints.maxHeight }
        val cols = px.first / cell.width
        val rows = px.second / cell.height
        // bar_width 2, bar_spacing 1, an even number so the halves mirror.
        val perSide = ((cols + 1) / 3 / 2).coerceAtLeast(1)
        val state = remember(perSide) { CavaState(perSide) }
        var tick by remember { mutableLongStateOf(0L) }
        LaunchedEffect(state) {
            var last = 0L
            while (true) withFrameNanos { now ->
                val dt = if (last == 0L) 1f / 60 else (now - last) / 1e9f
                last = now
                state.step(dt)
                tick = now
            }
        }
        Canvas(Modifier.fillMaxSize()) {
            tick // redraw every frame
            val bars = perSide * 2
            val used = bars * 3 - 1
            val x0 = (cols - used) / 2 * cell.width.toFloat() + (px.first % cell.width) / 2f
            val bottom = rows * cell.height + (px.second % cell.height) / 2f
            for (b in 0 until bars) {
                // Left half reversed: bass meets in the middle.
                val v = if (b < perSide) state.heights[perSide - 1 - b] else state.heights[b]
                val eighths = (v * rows * 8).roundToInt()
                if (eighths == 0) continue
                val h = eighths * cell.height / 8f
                drawRect(accent, Offset(x0 + b * 3 * cell.width, bottom - h), Size(2f * cell.width, h))
            }
        }
        val note = when {
            casting != null -> "casting — the visualiser needs audio playing on this phone"
            hint -> "tap to close"
            else -> null
        }
        if (note != null) Text(
            note, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(12.dp),
            color = dim, style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** Bars per side; left channel first, then right. */
private class CavaState(val perSide: Int) {
    val heights = FloatArray(perSide * 2)
    private var cava: Cava? = null
    private var l = FloatArray(0)
    private var r = FloatArray(0)

    fun step(dt: Float) {
        val rate = Spectrum.sampleRate
        val c = cava?.takeIf { it.rate == rate } ?: Cava(perSide, rate).also { cava = it; l = FloatArray(it.size); r = FloatArray(it.size) }
        if (Spectrum.read(l, r)) {
            c.spectrum(l, heights, 0)
            c.spectrum(r, heights, perSide)
        } else heights.fill(0f)
        c.smooth(heights, dt)
    }
}

/** Hides the status and navigation bars (swipe to peek) and keeps the screen on. */
@Composable
private fun ImmersiveWhileShown() {
    val view = LocalView.current
    DisposableEffect(view) {
        var ctx = view.context
        while (ctx is ContextWrapper && ctx !is Activity) ctx = ctx.baseContext
        val window = (ctx as? Activity)?.window
        val bars = window?.let { WindowCompat.getInsetsController(it, view) }
        bars?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars?.hide(WindowInsetsCompat.Type.systemBars())
        view.keepScreenOn = true
        onDispose {
            bars?.show(WindowInsetsCompat.Type.systemBars())
            view.keepScreenOn = false
        }
    }
}
