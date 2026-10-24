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

    private fun MainActivity.viewModels(): AppViewModel =
        androidx.lifecycle.ViewModelProvider(this)[AppViewModel::class.java]
}
