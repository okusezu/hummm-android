package com.okusezu.hummm.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParseTest {
    private fun fixture(name: String): JsonElement =
        Json.parseToJsonElement(javaClass.getResource("/fixtures/$name.json")!!.readText())

    @Test fun searchSongs() {
        val tracks = Parse.tracks(fixture("search_songs"))
        assertEquals(20, tracks.size)
        val t = tracks[0]
        assertEquals("mbVKGlh3gg4", t.videoId)
        assertEquals("True Love Waits (Live in Oslo)", t.title)
        assertEquals(listOf("Radiohead"), t.artists)
        assertEquals("I Might Be Wrong", t.album)
        assertEquals(305, t.duration)
        assertTrue(t.thumbnail.contains("=w544-h544"))
    }

    @Test fun searchItems() {
        val albums = Parse.entries(fixture("search_albums")).filterIsInstance<Item>()
        assertEquals("OK Computer", albums[0].title)
        assertEquals(Item.Kind.ALBUM, albums[0].kind)
        val artists = Parse.entries(fixture("search_artists")).filterIsInstance<Item>()
        assertEquals(Item.Kind.ARTIST, artists[0].kind)
        assertEquals("Radiohead", artists[0].title)
        val pls = Parse.entries(fixture("search_playlists")).filterIsInstance<Item>()
        assertTrue(pls.size >= 10 && pls.all { it.kind == Item.Kind.PLAYLIST })
        assertTrue(Parse.entries(fixture("search_featured")).filterIsInstance<Item>().isNotEmpty())
    }

    @Test fun albumFillsContext() {
        val json = fixture("album")
        val tracks = Parse.tracks(json, Parse.albumContext(json, "MPRE1"))
        assertTrue(tracks.size >= 12)
        assertEquals("Airbag", tracks[0].title)
        assertEquals(listOf("Radiohead"), tracks[0].artists)
        assertEquals("OK Computer", tracks[0].album)
        assertEquals(288, tracks[0].duration)
    }

    @Test fun playlistAndContinuation() {
        val json = fixture("playlist")
        val main = json.findFirst("musicPlaylistShelfRenderer") ?: json.findAll("musicShelfRenderer").first()
        val tracks = Parse.tracks(main)
        assertTrue(tracks.size > 50)
        assertEquals(emptyList<Track>(), tracks.filter { it.artists.isEmpty() })
    }

    @Test fun radioQueue() {
        val json = fixture("next_radio")
        val tracks = Parse.tracks(json.findFirst("playlistPanelRenderer"))
        assertEquals(50, tracks.size)
        assertEquals("The Bends", tracks[1].album)
        assertEquals(258, tracks[1].duration)
        val tabs = Parse.watchTabs(json)
        assertTrue(tabs["lyrics"]!!.startsWith("MPLY"))
        assertTrue(tabs["related"]!!.startsWith("MPTR"))
    }

    @Test fun homeSections() {
        val sections = Parse.sections(fixture("home"))
        assertEquals("Quick picks", sections[0].title)
        assertTrue(sections[0].entries.all { it is Track })
        assertTrue(sections.size >= 2)
    }

    @Test fun artistPage() {
        val json = fixture("artist")
        assertTrue(Parse.artistSongsPlaylist(json)!!.startsWith("VL"))
        val related = Parse.sections(json).flatMap { it.entries }.filterIsInstance<Item>().filter { it.kind == Item.Kind.ARTIST }
        assertTrue(related.isNotEmpty())
    }

    @Test fun lyrics() {
        val timed = Parse.timedLyrics(fixture("lyrics_mobile"))!!
        assertTrue(timed.timed)
        assertEquals(19660L, timed.lines[1].startMs)
        assertEquals("When you were here before", timed.lines[1].text)
        val plain = Parse.plainLyrics(fixture("lyrics_web"))!!
        assertTrue(!plain.timed && plain.lines.first().text.startsWith("When you were"))
    }

    @Test fun relatedAndMoods() {
        assertTrue(Parse.tracks(fixture("related")).any { it.album.isNotEmpty() })
        val moods = Parse.moods(fixture("moods"))
        assertTrue("chill" in moods)
        val pls = Parse.entries(fixture("mood_playlists")).filterIsInstance<Item>().filter { it.kind == Item.Kind.PLAYLIST }
        assertTrue(pls.size > 20)
    }

    @Test fun sapisidHash() {
        assertEquals("abc", InnerTube.sapisid("a=1; __Secure-3PAPISID=abc; b=2"))
        val h = InnerTube.sapisidHash("abc", 1700000000)
        assertTrue(h.matches(Regex("SAPISIDHASH 1700000000_[0-9a-f]{40}")))
    }
}
