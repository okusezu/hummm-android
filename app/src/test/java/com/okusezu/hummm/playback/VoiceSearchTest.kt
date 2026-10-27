package com.okusezu.hummm.playback

import android.os.Bundle
import android.provider.MediaStore
import com.okusezu.hummm.data.InnerTube
import com.okusezu.hummm.data.Music
import com.okusezu.hummm.playback.VoiceSearch.Kind
import com.okusezu.hummm.playback.VoiceSearch.Request
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** How a spoken "play X on hummm" turns into a search. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoiceSearchTest {
    private fun extras(vararg pairs: Pair<String, String>) = Bundle().apply { pairs.forEach { (k, v) -> putString(k, v) } }

    @Test fun plainQueryIsASong() {
        assertEquals(Request(Kind.SONG, "creep"), VoiceSearch.request("  creep ", null))
    }

    @Test fun focusPicksTheKindAndPrefersStructuredExtras() {
        assertEquals(
            Request(Kind.ARTIST, "Radiohead"),
            VoiceSearch.request("radiohead", extras(
                MediaStore.EXTRA_MEDIA_FOCUS to MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE,
                MediaStore.EXTRA_MEDIA_ARTIST to "Radiohead",
            )),
        )
        assertEquals(
            Request(Kind.ALBUM, "OK Computer Radiohead"),
            VoiceSearch.request("ok computer", extras(
                MediaStore.EXTRA_MEDIA_FOCUS to MediaStore.Audio.Albums.ENTRY_CONTENT_TYPE,
                MediaStore.EXTRA_MEDIA_ALBUM to "OK Computer",
                MediaStore.EXTRA_MEDIA_ARTIST to "Radiohead",
            )),
        )
        assertEquals(
            Request(Kind.SONG, "Creep Radiohead"),
            VoiceSearch.request("creep by radiohead", extras(
                MediaStore.EXTRA_MEDIA_FOCUS to MediaStore.Audio.Media.ENTRY_CONTENT_TYPE,
                MediaStore.EXTRA_MEDIA_TITLE to "Creep",
                MediaStore.EXTRA_MEDIA_ARTIST to "Radiohead",
            )),
        )
        assertEquals(
            Request(Kind.PLAYLIST, "chill vibes"),
            VoiceSearch.request("chill vibes", extras(MediaStore.EXTRA_MEDIA_FOCUS to MediaStore.Audio.Playlists.ENTRY_CONTENT_TYPE)),
        )
    }

    @Test fun playSomeMusicHasNoText() {
        assertEquals("", VoiceSearch.request("", null).text)
        assertEquals("", VoiceSearch.request("music", extras(MediaStore.EXTRA_MEDIA_FOCUS to "vnd.android.cursor.item/*")).text)
    }

    /** Run with -Plive. */
    @Test fun liveRequestsQueueSomething() {
        assumeTrue(System.getProperty("live") == "true")
        val music = Music(InnerTube(OkHttpClient(), { null }))
        val song = VoiceSearch.tracks(music, Request(Kind.SONG, "creep radiohead"))
        println("song: ${song.first().title} – ${song.first().artist}, ${song.size} queued")
        assertTrue(song.first().title.contains("Creep", ignoreCase = true) && song.size > 10)
        val artist = VoiceSearch.tracks(music, Request(Kind.ARTIST, "Radiohead"))
        assertTrue(artist.isNotEmpty() && artist.all { "Radiohead" in it.artists })
        assertTrue(VoiceSearch.tracks(music, Request(Kind.ALBUM, "OK Computer Radiohead")).size >= 10)
        assertTrue(VoiceSearch.tracks(music, Request(Kind.ANYTHING, "")).isNotEmpty())
    }
}
