package com.okusezu.hummm.data

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Hits the real service. Run with -Dlive=true (./gradlew testDebugUnitTest -Plive). */
class LiveTest {
    private val http = OkHttpClient()
    private val music = Music(InnerTube(http, { null }))

    private fun live() = assumeTrue(System.getProperty("live") == "true")

    @Test fun searchRadioLyricsFeed() {
        live()
        val songs = music.search("radiohead creep").first { it.title == "Songs" }.entries.filterIsInstance<Track>()
        assertTrue(songs.isNotEmpty())
        val seed = songs.first()
        assertTrue(music.radio(seed).size > 10)
        assertTrue(music.lyrics(seed)?.lines?.isNotEmpty() == true)
        assertTrue(music.feed().isNotEmpty())
        val album = music.search("ok computer").first { it.title == "Albums" }.entries.first() as Item
        assertTrue(music.itemTracks(album).size >= 10)
        val pl = music.search("chill vibes").first { it.title == "Playlists" }.entries.first() as Item
        assertTrue(music.itemTracks(pl).isNotEmpty())
        val big = music.search("500 greatest songs of all time").first { it.title == "Playlists" }.entries.first() as Item
        println("big playlist ${big.title}: " + music.itemTracks(big).size)
        println("artistSongs: " + music.artistSongs(seed.artistIds.first()).size)
        for (m in METRICS) println("discover $m: " + music.discover(seed, m, 10, emptySet()).size)
        val (desc, vibe) = music.vibe("late night drive")
        println("vibe: $desc → ${vibe.size}")
        assertTrue(vibe.size >= 10)
    }

    @Test fun streamResolvesAndServesAudio() {
        live()
        val streams = Streams(http)
        for (q in Streams.QUALITIES) {
            val url = streams.audioUrl("9RfVp-GhKfs", q)
            val resp = http.newCall(Request.Builder().url(url).header("Range", "bytes=0-65535").build()).execute()
            println("$q: HTTP ${resp.code} ${resp.header("Content-Type")} ${resp.body?.bytes()?.size} bytes")
            assertEquals(206, resp.code)
        }
    }
}
