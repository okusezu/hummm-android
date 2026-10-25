package com.okusezu.hummm.ui

import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.media3.session.MediaSessionService
import androidx.test.core.app.ApplicationProvider
import com.okusezu.hummm.HummmApp
import com.okusezu.hummm.MainActivity
import com.okusezu.hummm.playback.PlaybackService
import com.okusezu.hummm.playback.Spectrum
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the real app against live YouTube Music on the JVM and saves PNGs
 * to build/screenshots. Run with ./gradlew testDebugUnitTest -Plive
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-night-xxhdpi")
class ScreenshotTest {
    @get:Rule val rule = createEmptyComposeRule()
    private lateinit var activity: MainActivity
    private val out = File("build/screenshots").apply { mkdirs() }

    @Before fun bindService() {
        assumeTrue(System.getProperty("live") == "true")
        // Robolectric doesn't start services on bind; hand MediaController the session binder.
        val app = ApplicationProvider.getApplicationContext<HummmApp>()
        val service = Robolectric.buildService(PlaybackService::class.java).create().get()
        val binder = service.onBind(Intent(MediaSessionService.SERVICE_INTERFACE))
        shadowOf(app).setComponentNameAndServiceForBindService(ComponentName(app, PlaybackService::class.java), binder)
        ActivityScenario.launch(MainActivity::class.java).onActivity { activity = it }
    }

    private fun shot(name: String) {
        rule.waitForIdle()
        Thread.sleep(1500) // let artwork arrive
        rule.waitForIdle()
        capture(name)
    }

    private fun capture(name: String) {
        val view = activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bmp))
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Waits for background work: Compose's waitUntil doesn't drain results posted to the main looper. */
    private fun await(timeout: Long = 30_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeout
        while (!cond()) {
            check(System.currentTimeMillis() < end) { "timed out" }
            Thread.sleep(200)
            shadowOf(android.os.Looper.getMainLooper()).idle()
            rule.waitForIdle()
        }
    }

    private fun waitText(text: String, timeout: Long = 30_000) =
        rule.waitUntilAtLeastOneExists(hasText(text, substring = true), timeout)

    @Test fun tour() {
        waitText("Quick picks")
        shot("01_home")

        rule.onAllNodesWithText("search").onFirst().performClick()
        rule.onNode(hasSetTextAction()).performTextInput("radiohead")
        rule.onNode(hasSetTextAction()).performImeAction()
        waitText("Songs")
        shot("02_search")

        activity.viewModels().open(com.okusezu.hummm.data.Item(com.okusezu.hummm.data.Item.Kind.ALBUM, "MPREb_yXhSI4FCUo6", "OK Computer", "Album • Radiohead • 1997"))
        waitText("Airbag")
        shot("03_album")

        rule.onAllNodesWithText("Airbag").onFirst().performClick()
        await { activity.viewModels().current != null }
        rule.onAllNodesWithText("Airbag").onFirst().performClick() // mini player → full player
        Thread.sleep(4000)
        shot("04_mini_player")
        activity.viewModels().playerOpen = true
        shot("05_now_playing")
        activity.viewModels().settings.setArt(true)
        Thread.sleep(3000) // half-block cover download
        shot("05b_now_playing_art")
        activity.viewModels().settings.setArt(false)
        activity.viewModels().playerPage = PlayerPage.QUEUE
        shot("06_queue")
        activity.viewModels().playerPage = PlayerPage.LYRICS
        activity.viewModels().loadLyrics()
        await { activity.viewModels().lyrics is Load.Ok }
        shot("07_lyrics")
        activity.viewModels().playerPage = PlayerPage.NOW_PLAYING
        withFakeAudio {
            // The visualiser redraws every frame, so Compose never idles: step the clock by hand.
            rule.mainClock.autoAdvance = false
            activity.viewModels().visualiser = true
            val settings = com.okusezu.hummm.HummmApp.instance.settings
            val before = settings.visualStyle.value
            for (style in VisStyle.entries) {
                settings.setVisualStyle(style.label)
                repeat(120) { rule.mainClock.advanceTimeBy(16); shadowOf(android.os.Looper.getMainLooper()).idle(); Thread.sleep(16) }
                capture("07b_visualiser_${style.ordinal + 1}_${style.label}")
            }
            settings.setVisualStyle(before)
            activity.viewModels().visualiser = false
            rule.mainClock.autoAdvance = true
        }
        activity.viewModels().playerOpen = false

        rule.onAllNodesWithText("vibe").onFirst().performClick()
        rule.onAllNodesWithText("late night drive").onFirst().performClick()
        await(60_000) { !activity.viewModels().vibeBusy }
        shot("08_vibe")

        rule.onAllNodesWithText("library").onFirst().performClick()
        shot("09_library")
        activity.viewModels().push(Route.Settings)
        shot("10_settings")
    }

    /** Robolectric decodes nothing, so feed Spectrum a drum-and-chords loop in real time. */
    private fun withFakeAudio(block: () -> Unit) {
        val rate = 48_000
        Spectrum.flush(rate, 2, androidx.media3.common.C.ENCODING_PCM_16BIT)
        var running = true
        val feeder = Thread {
            var t = 0L
            val chunk = rate / 100
            while (running) {
                val buf = java.nio.ByteBuffer.allocate(chunk * 4).order(java.nio.ByteOrder.nativeOrder())
                repeat(chunk) {
                    val s = t.toDouble() / rate
                    val beat = s % 0.5
                    val kick = kotlin.math.exp(-beat * 18) * kotlin.math.sin(2 * Math.PI * (55 + 60 * kotlin.math.exp(-beat * 30)) * s)
                    val chord = listOf(220.0, 277.2, 329.6, 440.0, 1318.5, 2637.0).withIndex()
                        .sumOf { (i, f) -> 0.12 / (i + 1) * kotlin.math.sin(2 * Math.PI * f * s) }
                    val hat = if (s % 0.25 < 0.03) (Math.random() - 0.5) * 0.25 else 0.0
                    val l = (0.6 * kick + chord + hat).coerceIn(-1.0, 1.0)
                    val r = (0.6 * kick + 0.8 * chord + 1.3 * hat).coerceIn(-1.0, 1.0)
                    buf.putShort((l * 32000).toInt().toShort()).putShort((r * 32000).toInt().toShort())
                    t++
                }
                buf.flip()
                Spectrum.handleBuffer(buf)
                Thread.sleep(10)
            }
        }
        feeder.start()
        try { block() } finally { running = false; feeder.join() }
    }

    private fun MainActivity.viewModels(): AppViewModel =
        androidx.lifecycle.ViewModelProvider(this)[AppViewModel::class.java]
}
