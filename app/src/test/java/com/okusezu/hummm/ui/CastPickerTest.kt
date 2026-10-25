package com.okusezu.hummm.ui

import android.graphics.Bitmap
import androidx.compose.material3.Surface
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.okusezu.hummm.playback.Sonos
import com.okusezu.hummm.playback.SonosGroup
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/** The cast picker's Sonos section, captured to app/build/screenshots like the screenshot tour. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-night-xxhdpi")
class CastPickerTest {
    @get:Rule val rule = createComposeRule()

    @Test fun listsSonosGroupsAndPicksOne() {
        val office = SonosGroup("RINCON_A:1", "Office + Kitchen", "10.0.0.8")
        Sonos.setGroupsForTest(listOf(office, SonosGroup("RINCON_C:7", "Warehouse", "10.0.0.10")))
        rule.setContent { HummmTheme { Surface { CastPicker(onDismiss = {}) } } }
        rule.waitForIdle()
        rule.onNodeWithText("Office + Kitchen", substring = true).performClick()
        assertEquals(office, Sonos.target.value)
        Sonos.disconnect()

        // A text field in a dialog never settles under Robolectric (fine on a phone): stop the clock first.
        rule.mainClock.autoAdvance = false
        rule.onNodeWithText("add a speaker by IP address", substring = true).performClick()
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithText("speaker IP", substring = true).assertExists()
        // Drawing the dialog by hand leaves Compose unable to settle, so it comes last.
        save("11_cast_picker")
    }

    /** captureToImage can't grab a dialog under Robolectric, so draw its window by hand. */
    private fun save(name: String) {
        val view = ShadowDialog.getLatestDialog().window!!.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bmp))
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
