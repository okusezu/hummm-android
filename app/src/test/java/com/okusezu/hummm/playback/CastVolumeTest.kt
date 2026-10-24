package com.okusezu.hummm.playback

import android.os.Looper
import androidx.media3.common.DeviceInfo
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The phone's volume keys reach the Chromecast only if the media session
 * reports remote playback with a device volume; check the wrapper gives it that.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CastVolumeTest {
    @Test fun sessionPresentsRemoteVolume() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val player = CastVolumePlayer(ExoPlayer.Builder(context).build())

        assertEquals(DeviceInfo.PLAYBACK_TYPE_REMOTE, player.deviceInfo.playbackType)
        assertEquals(CastVolume.STEPS, player.deviceInfo.maxVolume)
        assertTrue(player.isCommandAvailable(Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS))
        assertTrue(player.availableCommands.contains(Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS))

        // What a controller of the session sees is what Media3 hands Android's
        // volume routing (a remote VolumeProvider with this range).
        val session = MediaSession.Builder(context, player).build()
        val future = MediaController.Builder(context, session.token).buildAsync()
        try {
            shadowOf(Looper.getMainLooper()).idle()
            val controller = future.get()
            assertEquals(DeviceInfo.PLAYBACK_TYPE_REMOTE, controller.deviceInfo.playbackType)
            assertEquals(CastVolume.STEPS, controller.deviceInfo.maxVolume)
            assertTrue(controller.isCommandAvailable(Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS))
            controller.release()
        } finally {
            session.release()
            player.release()
        }
    }
}
