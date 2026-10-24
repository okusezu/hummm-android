package com.okusezu.hummm.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/*
 * Turns InnerTube responses into Tracks, Items and Sections.
 *
 * Rather than hard-coding one path per page like ytmusicapi, these parsers
 * look for the three item renderers YouTube Music uses everywhere (list rows,
 * cards and queue entries), which keeps them working across page layouts.
 */
object Parse {
    private const val LIST_ITEM = "musicResponsiveListItemRenderer"
    private const val TWO_ROW = "musicTwoRowItemRenderer"
    private const val PANEL = "playlistPanelVideoRenderer"
    private val ITEM_KEYS = setOf(LIST_ITEM, TWO_ROW, PANEL)
    private val SHELF_KEYS = setOf(
        "musicCarouselShelfRenderer", "musicShelfRenderer", "musicPlaylistShelfRenderer",
        "gridRenderer", "musicImmersiveCarouselShelfRenderer",
    )
    private val DURATION = Regex("""^\d+(:\d+)+$""")
    private val TYPE_WORDS = setOf("song", "video", "album", "single", "ep", "playlist", "artist", "episode")

    /** Album context used to fill in tracks on an album page, which omit it per row. */
    data class AlbumContext(val title: String, val id: String, val thumbnail: String, val artists: List<Pair<String, String?>>)

    // ---- items ---------------------------------------------------------------

    /** Every item in [json] (in document order), parsed. */
    fun entries(json: JsonElement?, album: AlbumContext? = null): List<Entry> {
        val out = mutableListOf<Entry>()
        fun walk(e: JsonElement?) {
            when (e) {
                is JsonObject -> for ((k, v) in e) {
                    if (k in ITEM_KEYS) entry(k, v, album)?.let(out::add) else walk(v)
                }
                is JsonArray -> e.forEach(::walk)
                else -> {}
            }
        }
        walk(json)
        return out
    }

    fun tracks(json: JsonElement?, album: AlbumContext? = null): List<Track> =
        entries(json, album).filterIsInstance<Track>().distinctBy { it.videoId }

    /** Shelves (carousels, lists, grids) with their titles, in page order. */
    fun sections(json: JsonElement?): List<Section> {
        val out = mutableListOf<Section>()
        fun walk(e: JsonElement?) {
            when (e) {
                is JsonObject -> for ((k, v) in e) {
                    if (k in SHELF_KEYS) {
                        val entries = entries(v["contents"] ?: v["items"])
                        if (entries.isNotEmpty()) out += Section(shelfTitle(v), entries)
                    } else walk(v)
                }
                is JsonArray -> e.forEach(::walk)
                else -> {}
            }
        }
        walk(json)
        return out
    }

    private fun shelfTitle(shelf: JsonElement): String =
        (shelf.path("header", "musicCarouselShelfBasicHeaderRenderer", "title")
            ?: shelf.path("header", "gridHeaderRenderer", "title")
            ?: shelf.path("header", "musicImmersiveCarouselShelfBasicHeaderRenderer", "title")
            ?: shelf["title"]).text

    private fun entry(kind: String, r: JsonElement, album: AlbumContext?): Entry? = when (kind) {
        LIST_ITEM -> listItem(r, album)
        TWO_ROW -> twoRow(r)
        PANEL -> panel(r)
        else -> null
    }

    private fun listItem(r: JsonElement, album: AlbumContext?): Entry? {
        val cols = r["flexColumns"].arr.map { it.path("musicResponsiveListItemFlexColumnRenderer", "text") }
        val title = cols.getOrNull(0).text
        val thumb = bestThumb(r.path("thumbnail", "musicThumbnailRenderer", "thumbnail", "thumbnails"))
        val videoId = r.path("playlistItemData", "videoId").str
            ?: cols.getOrNull(0).runs.firstNotNullOfOrNull { it.path("navigationEndpoint", "watchEndpoint", "videoId").str }
            ?: r.findFirst("playNavigationEndpoint")?.path("watchEndpoint", "videoId").str
        if (videoId == null) {
            val browseId = r.path("navigationEndpoint", "browseEndpoint", "browseId").str ?: return null
            val itemKind = kindOf(browseId) ?: return null
            return Item(itemKind, browseId, title, cols.drop(1).map { it.text }.filter { it.isNotBlank() }.joinToString(" • "), thumb)
        }
        val detailRuns = cols.drop(1).flatMap { it.runs }
        val links = linkedRuns(detailRuns)
        var artists = links.artists
        if (artists.isEmpty()) {
            // Playlist rows sometimes name the artist without linking it.
            artists = cols.getOrNull(1).text.split(" • ").map { it.trim() }
                .firstOrNull { it.isNotEmpty() && it.lowercase() !in TYPE_WORDS && !DURATION.matches(it) && !it.endsWith(" plays") && !it.endsWith(" views") }
                ?.let { listOf(it to null) } ?: emptyList()
        }
        if (artists.isEmpty() && album != null) artists = album.artists
        val durationText = r["fixedColumns"].arr.firstOrNull()?.path("musicResponsiveListItemFixedColumnRenderer", "text").text
            ?.takeIf { DURATION.matches(it) }
            ?: detailRuns.mapNotNull { it["text"].str }.firstOrNull { DURATION.matches(it) }
        return Track(
            videoId = videoId,
            title = title.ifEmpty { "Untitled" },
            artists = artists.map { it.first },
            artistIds = artists.mapNotNull { it.second },
            album = links.album?.first ?: album?.title.orEmpty(),
            albumId = links.album?.second ?: album?.id.orEmpty(),
            duration = parseDuration(durationText),
            thumbnail = thumb.ifEmpty { album?.thumbnail.orEmpty() },
            liked = r.findFirst("likeStatus").str == "LIKE",
        )
    }

    private fun twoRow(r: JsonElement): Entry? {
        val title = r["title"].text
        val thumb = bestThumb(r.path("thumbnailRenderer", "musicThumbnailRenderer", "thumbnail", "thumbnails"))
        val subtitleRuns = r["subtitle"].runs
        val nav = r["navigationEndpoint"]
        nav.path("watchEndpoint", "videoId").str?.let { vid ->
            val links = linkedRuns(subtitleRuns)
            val artists = links.artists.ifEmpty {
                r["subtitle"].text.split(" • ").map { it.trim() }
                    .firstOrNull { it.isNotEmpty() && it.lowercase() !in TYPE_WORDS }
                    ?.let { listOf(it to null) } ?: emptyList()
            }
            return Track(vid, title, artists.map { it.first }, artists.mapNotNull { it.second },
                links.album?.first.orEmpty(), links.album?.second.orEmpty(), 0, thumb)
        }
        val browseId = nav.path("browseEndpoint", "browseId").str ?: return null
        val kind = kindOf(browseId) ?: return null
        return Item(kind, browseId, title, r["subtitle"].text, thumb)
    }

    private fun panel(r: JsonElement): Track? {
        val vid = r["videoId"].str ?: return null
        val byline = r["longBylineText"].runs.ifEmpty { r["shortBylineText"].runs }
        val links = linkedRuns(byline)
        val artists = links.artists.ifEmpty {
            byline.firstOrNull()?.get("text").str?.let { listOf(it to null) } ?: emptyList()
        }
        return Track(
            videoId = vid,
            title = r["title"].text.ifEmpty { "Untitled" },
            artists = artists.map { it.first },
            artistIds = artists.mapNotNull { it.second },
            album = links.album?.first.orEmpty(),
            albumId = links.album?.second.orEmpty(),
            duration = parseDuration(r["lengthText"].text),
            thumbnail = bestThumb(r.path("thumbnail", "thumbnails")),
            liked = r.findFirst("likeStatus").str == "LIKE",
        )
    }

    private class Links(val artists: List<Pair<String, String?>>, val album: Pair<String, String>?)

    private fun linkedRuns(runs: List<JsonElement>): Links {
        val artists = mutableListOf<Pair<String, String?>>()
        var album: Pair<String, String>? = null
        for (run in runs) {
            val id = run.path("navigationEndpoint", "browseEndpoint", "browseId").str ?: continue
            val text = run["text"].str ?: continue
            when (kindOf(id)) {
                Item.Kind.ARTIST -> artists += text to id
                Item.Kind.ALBUM -> if (album == null) album = text to id
                else -> {}
            }
        }
        return Links(artists, album)
    }

    // ---- pages -----------------------------------------------------------------

    fun albumContext(json: JsonElement, albumId: String): AlbumContext {
        val header = json.findFirst("musicResponsiveHeaderRenderer") ?: json.findFirst("musicDetailHeaderRenderer")
        val artists = header?.get("straplineTextOne").runs.mapNotNull { run ->
            run["text"].str?.let { it to run.path("navigationEndpoint", "browseEndpoint", "browseId").str }
        }.ifEmpty { linkedRuns(header?.get("subtitle").runs).artists }
        return AlbumContext(
            title = header?.get("title").text.orEmpty(),
            id = albumId,
            thumbnail = bestThumb(header.findFirst("thumbnails")),
            artists = artists,
        )
    }

    /** The "Songs"/"Top songs" playlist of an artist page, if it links to one. */
    fun artistSongsPlaylist(json: JsonElement): String? =
        json.findAll("musicShelfRenderer").firstNotNullOfOrNull { shelf ->
            shelf.path("bottomEndpoint", "browseEndpoint", "browseId").str
                ?: shelf["title"].runs.firstNotNullOfOrNull { it.path("navigationEndpoint", "browseEndpoint", "browseId").str }
        }

    /** Watch-next tab browse ids: "lyrics" and "related". */
    fun watchTabs(json: JsonElement): Map<String, String> {
        val out = mutableMapOf<String, String>()
        for (tab in json.findAll("tabRenderer")) {
            val ep = tab.path("endpoint", "browseEndpoint") ?: continue
            val id = ep["browseId"].str ?: continue
            when (ep.path("browseEndpointContextSupportedConfigs", "browseEndpointContextMusicConfig", "pageType").str) {
                "MUSIC_PAGE_TYPE_TRACK_LYRICS" -> out["lyrics"] = id
                "MUSIC_PAGE_TYPE_TRACK_RELATED" -> out["related"] = id
            }
        }
        return out
    }

    fun timedLyrics(json: JsonElement): Lyrics? {
        val data = json.findFirst("timedLyricsModel")?.get("lyricsData") ?: return null
        val lines = data["timedLyricsData"].arr
        if (lines.isEmpty()) return null
        val source = data["sourceMessage"].str.orEmpty()
        val timed = lines.all { it["cueRange"] != null }
        return Lyrics(lines.map {
            LyricLine(if (timed) it.path("cueRange", "startTimeMilliseconds").str?.toLongOrNull() else null, it["lyricLine"].str.orEmpty())
        }, source)
    }

    fun plainLyrics(json: JsonElement): Lyrics? {
        val shelf = json.findFirst("musicDescriptionShelfRenderer") ?: return null
        val text = shelf["description"].text
        if (text.isBlank()) return null
        return Lyrics(text.lines().map { LyricLine(null, it) }, shelf["footer"].text)
    }

    /** Mood/genre name (lowercased) → browse params, from FEmusic_moods_and_genres. */
    fun moods(json: JsonElement): Map<String, String> =
        json.findAll("musicNavigationButtonRenderer").mapNotNull { b ->
            val params = b.path("clickCommand", "browseEndpoint", "params").str ?: return@mapNotNull null
            b["buttonText"].text.lowercase() to params
        }.toMap()

    fun accountName(json: JsonElement): String? = json.findFirst("accountName").text.takeIf { it.isNotBlank() }

    // ---- helpers ---------------------------------------------------------------

    fun kindOf(browseId: String): Item.Kind? = when {
        browseId.startsWith("MPRE") -> Item.Kind.ALBUM
        browseId.startsWith("UC") -> Item.Kind.ARTIST
        browseId.startsWith("VL") || browseId.startsWith("PL") || browseId.startsWith("RD") ||
            browseId.startsWith("OLAK") || browseId == "LM" -> Item.Kind.PLAYLIST
        else -> null
    }

    fun parseDuration(text: String?): Int {
        if (text.isNullOrBlank()) return 0
        return text.split(":").fold(0) { acc, p -> acc * 60 + (p.trim().toIntOrNull() ?: return 0) }
    }

    /** Largest thumbnail, resized to a square cover suitable for phone screens. */
    fun bestThumb(thumbs: JsonElement?): String {
        val best = thumbs.arr.maxByOrNull { (it["width"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: 0 }
        val url = best?.get("url").str ?: return ""
        return url.replace(Regex("=w\\d+-h\\d+"), "=w544-h544")
    }
}
