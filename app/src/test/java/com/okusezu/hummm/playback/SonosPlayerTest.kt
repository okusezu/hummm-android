package com.okusezu.hummm.playback

import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** SonosPlayer against a fake speaker: hummm's queue playing one song at a time. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SonosPlayerTest {
    private val fake = FakeSonos()
    private val client = SonosClient(OkHttpClient(), "127.0.0.1", fake.port)
    private var lost: String? = null
    private val player = SonosPlayer(client, { id -> "http://192.168.1.5:4000/tok/$id.m4a" }, onLost = { lost = it })

    @After fun tearDown() {
        player.release()
        fake.close()
    }

    private fun song(id: String) = MediaItem.Builder().setMediaId(id).setUri("hummm://$id")
        .setMediaMetadata(MediaMetadata.Builder().setTitle("song $id").setArtist("artist").setDurationMs(200_000).build()).build()

    /** What the poll loop does every second, done by hand. */
    private fun poll() = player.apply(client.status())

    private fun start() {
        player.setMediaItems(listOf(song("aaaaaaaa"), song("bbbbbbbb")), 0, 0)
        player.playWhenReady = true
        player.prepare()
        assertEquals(listOf("SetAVTransportURI", "Play"), fake.awaitActions(2))
        assertEquals("http://192.168.1.5:4000/tok/aaaaaaaa.m4a", fake.uri)
    }

    @Test fun playsTheQueueOneSongAtATime() {
        start()
        fake.positionMs = 30_000
        poll()
        assertEquals(Player.STATE_READY, player.playbackState)
        assertTrue(player.currentPosition >= 30_000)

        // The speaker reaches the end of the song and stops: the next one goes on.
        fake.state = "STOPPED"
        poll()
        assertEquals(1, player.currentMediaItemIndex)
        assertEquals(listOf("SetAVTransportURI", "Play", "SetAVTransportURI", "Play"), fake.awaitActions(4))
        assertEquals("http://192.168.1.5:4000/tok/bbbbbbbb.m4a", fake.uri)

        // And after the last song the queue has ended.
        poll()
        fake.state = "STOPPED"
        poll()
        assertEquals(Player.STATE_ENDED, player.playbackState)
    }

    @Test fun pauseSeekAndSkipAreSentToTheSpeaker() {
        start()
        poll()
        player.pause()
        player.seekTo(90_000)
        player.seekToNextMediaItem()
        assertEquals(listOf("SetAVTransportURI", "Play", "Pause", "Seek", "SetAVTransportURI"), fake.awaitActions(5))
        assertEquals("0:01:30", fake.calls.first { it.first == "Seek" }.second["Target"])
        assertEquals("http://192.168.1.5:4000/tok/bbbbbbbb.m4a", fake.uri)
        // Paused, so the next song is loaded but not started.
        Thread.sleep(200)
        assertEquals("STOPPED", fake.state)
    }

    @Test fun volumeKeysMoveTheGroupVolumeInFives() {
        start()
        poll()
        assertEquals(6, player.deviceVolume) // 30 of 100
        player.increaseDeviceVolume(0)
        fake.awaitActions(3)
        assertEquals(35, fake.volume)
        assertEquals(35, CastStatus.volume.value)
    }

    @Test fun somethingElseStartedFromTheSonosAppHandsBack() {
        start()
        poll()
        assertNull(lost)
        fake.uri = "x-sonos-spotify:spotify%3atrack%3a123"
        fake.state = "PLAYING"
        poll()
        assertTrue(lost!!.contains("something else"))
    }

    @Test fun pollingRunsOnItsOwn() {
        start()
        fake.positionMs = 12_000
        // Let the real poll loop go round a couple of times.
        repeat(30) {
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))
            Thread.sleep(20)
        }
        assertEquals(Player.STATE_READY, player.playbackState)
        assertTrue(player.currentPosition >= 12_000)
    }
}
