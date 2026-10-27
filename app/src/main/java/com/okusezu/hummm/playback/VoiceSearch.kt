package com.okusezu.hummm.playback

import android.app.SearchManager
import android.os.Bundle
import android.provider.MediaStore
import com.okusezu.hummm.data.Item
import com.okusezu.hummm.data.Music
import com.okusezu.hummm.data.Track

/**
 * "Play X on hummm" from Gemini / Google Assistant. The assistant sends a
 * free-text query plus optional hints (MediaStore.EXTRA_MEDIA_FOCUS and the
 * artist/album/title extras), either as a MEDIA_PLAY_FROM_SEARCH intent or a
 * playFromSearch on the media session; both end up here.
 */
object VoiceSearch {
    enum class Kind { SONG, ARTIST, ALBUM, PLAYLIST, ANYTHING }

    data class Request(val kind: Kind, val text: String)

    fun request(query: String?, extras: Bundle?): Request {
        val q = query.orEmpty().trim()
        fun extra(key: String) = extras?.getString(key)?.trim().orEmpty()
        val artist = extra(MediaStore.EXTRA_MEDIA_ARTIST)
        val album = extra(MediaStore.EXTRA_MEDIA_ALBUM)
        val title = extra(MediaStore.EXTRA_MEDIA_TITLE)
        val playlist = extra(EXTRA_MEDIA_PLAYLIST)
        return when (extras?.getString(MediaStore.EXTRA_MEDIA_FOCUS)) {
            MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE -> Request(Kind.ARTIST, artist.ifEmpty { q })
            MediaStore.Audio.Albums.ENTRY_CONTENT_TYPE -> Request(Kind.ALBUM, listOf(album, artist).joined().ifEmpty { q })
            MediaStore.Audio.Playlists.ENTRY_CONTENT_TYPE -> Request(Kind.PLAYLIST, playlist.ifEmpty { q })
            MediaStore.Audio.Media.ENTRY_CONTENT_TYPE -> Request(Kind.SONG, listOf(title, artist).joined().ifEmpty { q })
            "vnd.android.cursor.item/*" -> Request(Kind.ANYTHING, "") // "play some music"
            else -> Request(Kind.SONG, q)
        }
    }

    /** What to queue for [request]; blocking network I/O. Empty when nothing matched. */
    fun tracks(music: Music, request: Request): List<Track> {
        if (request.text.isEmpty()) {
            if (music.authed) return music.likedSongs().shuffled()
            return music.discover(null, "pure discovery", 25, emptySet())
        }
        fun first(kind: Item.Kind): Item? =
            music.search(request.text).flatMap { it.entries }.filterIsInstance<Item>().firstOrNull { it.kind == kind }
        return when (request.kind) {
            Kind.ARTIST -> first(Item.Kind.ARTIST)?.let { music.artistSongs(it.id) }
            Kind.ALBUM -> first(Item.Kind.ALBUM)?.let { music.album(it.id) }
            Kind.PLAYLIST -> first(Item.Kind.PLAYLIST)?.let { music.playlist(it.id) }
            else -> null
        } ?: music.searchSongs(request.text, 1).firstOrNull()?.let { listOf(it) + music.radio(it) }.orEmpty()
    }

    private fun List<String>.joined() = filter { it.isNotEmpty() }.joinToString(" ")

    /** MediaStore.EXTRA_MEDIA_PLAYLIST, which is API 31+. */
    private const val EXTRA_MEDIA_PLAYLIST = "android.intent.extra.playlist"

    /** For a MEDIA_PLAY_FROM_SEARCH intent. */
    const val QUERY = SearchManager.QUERY
}
