package com.okusezu.hummm.ui

import android.app.Activity
import android.content.ContextWrapper
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil.compose.AsyncImage
import com.okusezu.hummm.HummmApp
import com.okusezu.hummm.playback.CastStatus
import com.okusezu.hummm.playback.Spectrum
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * Full-screen visualiser in the style of cava in a terminal. Swipe left or
 * right to change style (see [VisStyle]; the last one is remembered), tap or
 * go back to close.
 */
@Composable
fun Visualiser(artUrl: String, onClose: () -> Unit) {
    val settings = HummmApp.instance.settings
    val style = VisStyle.named(settings.visualStyle.collectAsState().value)
    val art by settings.art.collectAsState()
    val colors = VisColors(
        MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.colorScheme.onSurface,
    )
    val casting by CastStatus.device.collectAsState()
    val text = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp)
    val measurer = rememberTextMeasurer()
    val cell = remember(measurer) { measurer.measure("█", text).size }
    val glyphs = remember(measurer) { Glyphs(measurer, text) }
    // The style's name shows briefly when it changes; the first time, how to use the screen.
    var label by remember { mutableStateOf<String?>("swipe for styles · tap to close") }
    var labelShown by remember { mutableIntStateOf(0) }
    LaunchedEffect(labelShown) { delay(if (labelShown == 0) 2500 else 1500); label = null }
    ImmersiveWhileShown()
    DisposableEffect(Unit) {
        Spectrum.listening = true
        onDispose { Spectrum.listening = false }
    }

    BoxWithConstraints(
        Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .pointerInput(Unit) { detectTapGestures(onTap = { onClose() }) }
            .pointerInput(Unit) {
                var dragged = 0f
                detectHorizontalDragGestures(
                    onDragStart = { dragged = 0f },
                    onDragEnd = {
                        if (abs(dragged) > 48.dp.toPx()) {
                            val next = VisStyle.named(settings.visualStyle.value).next(if (dragged < 0) 1 else -1)
                            settings.setVisualStyle(next.label)
                            label = "◂ ${next.label} ▸   ${next.ordinal + 1}/${VisStyle.entries.size}"
                            labelShown++
                        }
                    },
                ) { change, amount -> change.consume(); dragged += amount }
            }
    ) {
        val grid = remember(constraints.maxWidth, constraints.maxHeight, cell) {
            Grid(cell.width.toFloat(), cell.height.toFloat(), constraints.maxWidth, constraints.maxHeight)
        }
        val audio = remember { AudioFrame() }
        val scene = remember(style, grid) { scene(style, grid, glyphs) }
        var tick by remember { mutableLongStateOf(0L) }
        LaunchedEffect(scene) {
            var last = 0L
            while (true) withFrameNanos { now ->
                val dt = if (last == 0L) 1f / 60 else (now - last) / 1e9f
                last = now
                audio.read()
                scene.step(dt, audio)
                tick = now
            }
        }
        Canvas(Modifier.fillMaxSize()) {
            tick // redraw every frame
            with(scene) { draw(colors) }
        }
        if (style == VisStyle.CIRCLE && art && artUrl.isNotEmpty()) {
            val d = with(LocalDensity.current) { (circleRadius(grid.w, grid.h) * 2 * 0.86f).toDp() }
            AsyncImage(
                model = artUrl, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.align(Alignment.Center).size(d).clip(CircleShape),
            )
        }
        val note = when {
            casting != null -> "casting — the visualiser needs audio playing on this phone"
            else -> label
        }
        if (note != null) Text(
            note, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(12.dp),
            color = colors.dim, style = MaterialTheme.typography.bodySmall,
        )
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
