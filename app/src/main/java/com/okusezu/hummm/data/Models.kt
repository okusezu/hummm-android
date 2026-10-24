package com.okusezu.hummm.data

/** Anything that can appear in a list: a playable song or a browsable item. */
sealed interface Entry

data class Track(
    val videoId: String,
    val title: String,
    val artists: List<String> = emptyList(),
    val artistIds: List<String> = emptyList(),
    val album: String = "",
    val albumId: String = "",
    val duration: Int = 0,
    val thumbnail: String = "",
    var liked: Boolean = false,
) : Entry {
    val artist: String get() = artists.joinToString(", ").ifEmpty { "Unknown artist" }
    val url: String get() = "https://music.youtube.com/watch?v=$videoId"
}

/** A non-track browsable result: album, playlist or artist. */
data class Item(
    val kind: Kind,
    val id: String,
    val title: String,
    val subtitle: String = "",
    val thumbnail: String = "",
) : Entry {
    enum class Kind { ALBUM, PLAYLIST, ARTIST }
}

data class Section(val title: String, val entries: List<Entry>)

data class LyricLine(val startMs: Long?, val text: String)

data class Lyrics(val lines: List<LyricLine>, val source: String) {
    val timed: Boolean get() = lines.isNotEmpty() && lines.all { it.startMs != null }
}

class NotSignedInException : Exception("Not signed in. Sign in from Library → Settings first")

val METRICS = listOf("same artist", "similar artists", "same genre", "exploring", "pure discovery")
