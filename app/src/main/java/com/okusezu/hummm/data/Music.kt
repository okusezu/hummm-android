package com.okusezu.hummm.data

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * YouTube Music features, ported from hummm's music.py. Everything here is
 * blocking network I/O; callers run it on Dispatchers.IO.
 *
 * Guest mode covers search, radio, lyrics, feed and discovery. Library,
 * likes and saving playlists need a signed-in cookie.
 */
class Music(val api: InnerTube) {
    val authed: Boolean get() = api.signedIn
    @Volatile private var moodCache: Map<String, String>? = null

    fun requireAuth() {
        if (!authed) throw NotSignedInException()
    }

    // ---- search & browse -----------------------------------------------------

    fun search(query: String): List<Section> {
        fun run(filter: String, limit: Int): List<Entry> =
            Parse.entries(api.post("search", buildJsonObject {
                put("query", query)
                put("params", SEARCH_PARAMS.getValue(filter))
            })).take(limit)
        return listOf(
            Section("Songs", run("songs", 15)),
            Section("Albums", run("albums", 6)),
            Section("Artists", run("artists", 4)),
            Section("Playlists", run("playlists", 6)),
        ).filter { it.entries.isNotEmpty() }
    }

    fun searchSongs(query: String, limit: Int = 30): List<Track> =
        Parse.tracks(api.post("search", buildJsonObject {
            put("query", query)
            put("params", SEARCH_PARAMS.getValue("songs"))
        })).take(limit)

    fun itemTracks(item: Item): List<Track> = when (item.kind) {
        Item.Kind.ALBUM -> album(item.id)
        Item.Kind.PLAYLIST -> playlist(item.id)
        Item.Kind.ARTIST -> artistSongs(item.id)
    }

    fun album(browseId: String): List<Track> {
        val json = api.browse(browseId)
        return Parse.tracks(json, Parse.albumContext(json, browseId))
    }

    fun playlist(id: String, limit: Int = 200): List<Track> {
        val bare = id.removePrefix("VL")
        if (bare == "LM") return likedSongs()
        return browseAll("VL$bare", limit)
    }

    /** Browses [browseId] and follows "load more" continuations until [limit] tracks. */
    private fun browseAll(browseId: String, limit: Int): List<Track> {
        val body = buildJsonObject { put("browseId", browseId) }
        var json = api.post("browse", body)
        val out = LinkedHashMap<String, Track>()
        // The first page also carries suggestion shelves; only take the main list.
        val main = json.findFirst("musicPlaylistShelfRenderer")
            ?: json.findAll("musicShelfRenderer").firstOrNull()
            ?: json
        Parse.tracks(main).forEach { out.putIfAbsent(it.videoId, it) }
        var token = InnerTube.Continuation.find(main)
        var pages = 0
        while (token != null && out.size < limit && pages++ < 20) {
            json = api.continuation("browse", body, token)
            val before = out.size
            Parse.tracks(json).forEach { out.putIfAbsent(it.videoId, it) }
            if (out.size == before) break
            token = InnerTube.Continuation.find(json)
        }
        return out.values.take(limit)
    }

    fun artist(channelId: String): JsonElement = api.browse(channelId)

    fun artistSongs(channelId: String): List<Track> {
        val json = artist(channelId)
        Parse.artistSongsPlaylist(json)?.let { pl ->
            runCatching { playlist(pl, 60) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        return Parse.tracks(json.findAll("musicShelfRenderer").firstOrNull())
    }

    /** Artist page shelves: top songs, albums, singles, related artists… */
    fun artistSections(channelId: String): List<Section> = Parse.sections(artist(channelId))

    // ---- library (auth) ------------------------------------------------------

    fun librarySongs(): List<Track> { requireAuth(); return browseAll("FEmusic_liked_videos", 500) }
    fun likedSongs(): List<Track> { requireAuth(); return browseAll("VLLM", 500) }

    fun libraryAlbums(): List<Item> = libraryItems("FEmusic_liked_albums", Item.Kind.ALBUM)
    fun libraryArtists(): List<Item> = libraryItems("FEmusic_library_corpus_track_artists", Item.Kind.ARTIST)
    fun libraryPlaylists(): List<Item> = libraryItems("FEmusic_liked_playlists", Item.Kind.PLAYLIST)

    private fun libraryItems(browseId: String, kind: Item.Kind): List<Item> {
        requireAuth()
        return Parse.entries(api.browse(browseId)).filterIsInstance<Item>().filter { it.kind == kind }
    }

    fun rate(track: Track, like: Boolean) {
        requireAuth()
        api.post(if (like) "like/like" else "like/removelike", buildJsonObject {
            putJsonObject("target") { put("videoId", track.videoId) }
        })
        track.liked = like
    }

    fun savePlaylist(name: String, tracks: List<Track>): String {
        requireAuth()
        val resp = api.post("playlist/create", buildJsonObject {
            put("title", name.replace(Regex("[<>]"), ""))
            put("description", "Saved from hummm")
            put("privacyStatus", "PRIVATE")
            putJsonArray("videoIds") { tracks.forEach { add(it.videoId) } }
        })
        return resp["playlistId"].str ?: throw IllegalStateException("YouTube Music refused to create the playlist")
    }

    fun playlistsForAdd(): List<Item> = libraryPlaylists().filter { it.id.removePrefix("VL") !in setOf("LM", "SE") }

    fun addToPlaylist(playlistId: String, tracks: List<Track>) {
        requireAuth()
        api.post("browse/edit_playlist", buildJsonObject {
            put("playlistId", playlistId.removePrefix("VL"))
            putJsonArray("actions") {
                tracks.forEach { t ->
                    addJsonObject {
                        put("action", "ACTION_ADD_VIDEO")
                        put("addedVideoId", t.videoId)
                        put("dedupeOption", "DEDUPE_OPTION_SKIP")
                    }
                }
            }
        })
    }

    fun accountName(): String {
        val name = Parse.accountName(api.post("account/account_menu", JsonObject(emptyMap())))
        return name ?: throw IllegalStateException("YouTube Music didn't accept this login (signed out or expired?)")
    }

    /** Whether the signed-in user likes [track] (the watch-next queue carries it). */
    fun likeStatus(track: Track): Boolean {
        val json = watch(track.videoId, radio = false)
        val item = json.findAll("playlistPanelVideoRenderer").firstOrNull { it["videoId"].str == track.videoId }
        track.liked = item.findFirst("likeStatus").str == "LIKE"
        return track.liked
    }

    // ---- feed ----------------------------------------------------------------

    fun feed(): List<Section> {
        val body = buildJsonObject { put("browseId", "FEmusic_home") }
        var json = api.post("browse", body)
        val sections = Parse.sections(json).toMutableList()
        // The home page arrives in a few chunks; grab a couple more.
        repeat(2) {
            val token = InnerTube.Continuation.find(json.findFirst("sectionListRenderer") ?: json) ?: return@repeat
            json = runCatching { api.continuation("browse", body, token) }.getOrNull() ?: return@repeat
            sections += Parse.sections(json)
        }
        return sections.filter { it.title.isNotBlank() }
    }

    // ---- lyrics ----------------------------------------------------------------

    fun lyrics(track: Track): Lyrics? {
        val browse = Parse.watchTabs(watch(track.videoId, radio = false))["lyrics"] ?: return null
        runCatching { Parse.timedLyrics(api.browse(browse, mobile = true)) }.getOrNull()?.let { return it }
        return Parse.plainLyrics(api.browse(browse))
    }

    // ---- radio, discovery & vibes -------------------------------------------

    private fun watch(videoId: String, radio: Boolean): JsonElement = api.post("next", buildJsonObject {
        put("enablePersistentPlaylistPanel", true)
        put("isAudioOnly", true)
        put("tunerSettingValue", "AUTOMIX_SETTING_NORMAL")
        put("videoId", videoId)
        put("playlistId", "RDAMVM$videoId")
        if (radio) put("params", "wAEB")
        else putJsonObject("watchEndpointMusicSupportedConfigs") {
            putJsonObject("watchEndpointMusicConfig") {
                put("hasPersistentPlaylistPanel", true)
                put("musicVideoType", "MUSIC_VIDEO_TYPE_ATV")
            }
        }
    })

    fun radio(track: Track, limit: Int = 40): List<Track> =
        Parse.tracks(watch(track.videoId, radio = true).findFirst("playlistPanelRenderer"))
            .filter { it.videoId != track.videoId }.take(limit)

    fun discover(seed: Track?, metric: String, n: Int, exclude: Set<String>): List<Track> {
        var pool: List<Track> = when {
            seed == null || metric == "pure discovery" -> moodPool(moods().keys.randomOrNull() ?: "chill")
            metric == "same artist" && seed.artistIds.isNotEmpty() -> artistSongs(seed.artistIds[0])
            metric == "similar artists" && seed.artistIds.isNotEmpty() -> {
                val related = Parse.sections(artist(seed.artistIds[0]))
                    .flatMap { it.entries }
                    .filterIsInstance<Item>()
                    .filter { it.kind == Item.Kind.ARTIST && it.id != seed.artistIds[0] }
                    .shuffled()
                related.take(3).flatMap { runCatching { artistSongs(it.id).take(10) }.getOrDefault(emptyList()) }
                    .ifEmpty { radio(seed) }
            }
            metric == "exploring" -> {
                val related = Parse.watchTabs(watch(seed.videoId, radio = false))["related"]
                // Skip covers/tutorial videos: real songs carry album info.
                val tracks = related?.let { Parse.tracks(api.browse(it)).filter { t -> t.album.isNotEmpty() } }
                tracks?.ifEmpty { null } ?: radio(seed)
            }
            else -> radio(seed) // "same genre": YT Music's radio stays close in genre/style
        }
        pool = pool.shuffled()
        val seen = exclude.toMutableSet()
        return pool.filter { seen.add(it.videoId) }.take(n)
    }

    private fun moods(): Map<String, String> {
        moodCache?.let { return it }
        val m = runCatching { Parse.moods(api.browse("FEmusic_moods_and_genres")) }.getOrDefault(emptyMap())
        if (m.isNotEmpty()) moodCache = m
        return m
    }

    private fun moodPool(mood: String): List<Track> {
        moods()[mood]?.let { params ->
            val playlists = Parse.entries(api.browse("FEmusic_moods_and_genres_category", params))
                .filterIsInstance<Item>().filter { it.kind == Item.Kind.PLAYLIST }.shuffled()
            for (p in playlists.take(2)) {
                val tracks = runCatching { playlist(p.id, 60) }.getOrDefault(emptyList())
                if (tracks.isNotEmpty()) return tracks
            }
        }
        return searchSongs(MOOD_FALLBACK_QUERIES[mood] ?: mood)
    }

    /** Turns a free-text vibe into a queue. Returns (description, tracks). */
    fun vibe(text: String, n: Int = 25): Pair<String, List<Track>> {
        val lowered = " ${text.lowercase()} "
        val moods = VIBE_KEYWORDS.filter { (_, words) -> words.any { " $it " in lowered || " $it" in lowered } }.keys.toList()
        val primary = mutableListOf<Track>()
        val secondary = mutableListOf<Track>()
        val sources = mutableListOf<String>()

        // 1. A playlist literally matching the vibe usually captures it best.
        //    Prefer the candidate whose title shares the most words with it.
        val words = Regex("\\w+").findAll(lowered).map { it.value }.toSet() - STOP_WORDS
        data class Candidate(val overlap: Int, val rank: Int, val item: Item)
        val candidates = mutableListOf<Candidate>()
        for ((rankBase, filter) in listOf(0 to "featured_playlists", 5 to "community_playlists")) {
            val hits = runCatching {
                Parse.entries(api.post("search", buildJsonObject {
                    put("query", text)
                    put("params", SEARCH_PARAMS.getValue(filter))
                })).filterIsInstance<Item>().filter { it.kind == Item.Kind.PLAYLIST }
            }.getOrDefault(emptyList())
            hits.take(5).forEachIndexed { rank, p ->
                val titleWords = Regex("\\w+").findAll(p.title.lowercase()).map { it.value }.toSet()
                candidates += Candidate((words intersect titleWords).size, rankBase + rank, p)
            }
        }
        for (c in candidates.sortedWith(compareBy({ -it.overlap }, { it.rank })).take(2)) {
            val tracks = runCatching { playlist(c.item.id, 80) }.getOrDefault(emptyList())
            if (tracks.isNotEmpty()) {
                primary += tracks
                sources += "playlist “${c.item.title.trim()}”"
                break
            }
        }

        // 2. YouTube Music's own mood/genre playlists for matched keywords.
        for (mood in moods.take(2)) {
            val tracks = runCatching { moodPool(mood) }.getOrDefault(emptyList())
            if (tracks.isNotEmpty()) {
                secondary += tracks
                sources += "mood “$mood”"
            }
        }

        // 3. Plain song search as a fallback.
        if (primary.size + secondary.size < n) {
            secondary += searchSongs(text)
            sources += "song search"
        }

        // The matching playlist carries the vibe; moods/search fill the rest.
        primary.shuffle()
        secondary.shuffle()
        val wantPrimary = if (secondary.isEmpty()) n else (n * 0.7).toInt()
        val seen = mutableSetOf<String>()
        val out = (primary.take(wantPrimary) + secondary + primary.drop(wantPrimary))
            .filter { seen.add(it.videoId) }
            .take(n)
            .shuffled()
        return sources.joinToString(" + ") to out
    }

    companion object {
        val SEARCH_PARAMS = mapOf(
            "songs" to "EgWKAQIIAWoMEA4QChADEAQQCRAF",
            "albums" to "EgWKAQIYAWoMEA4QChADEAQQCRAF",
            "artists" to "EgWKAQIgAWoMEA4QChADEAQQCRAF",
            "playlists" to "Eg-KAQwIABAAGAAgACgBMABqChAEEAMQCRAFEAo%3D",
            "featured_playlists" to "EgeKAQQoADgBagwQDhAKEAMQBBAJEAU%3D",
            "community_playlists" to "EgeKAQQoAEABagwQDhAKEAMQBBAJEAU%3D",
        )

        private val STOP_WORDS = setOf("a", "the", "and", "for", "of", "in", "on", "with", "music", "songs", "vibes")

        // Keywords a user might type mapped onto YouTube Music's own mood/genre names.
        // Each vibe maps to at most two moods, so e.g. "late night drive" lands on
        // commute/chill rather than sleep.
        val VIBE_KEYWORDS: Map<String, List<String>> = linkedMapOf(
            "commute" to listOf("night drive", "road trip", "drive", "driving", "cruise", "road", "commute", "car", "travel"),
            "chill" to listOf("late night", "chill", "relax", "calm", "lofi", "lo-fi", "mellow", "sunday", "coffee", "lazy", "cozy"),
            "focus" to listOf("focus", "coding", "code", "study", "work", "concentrat", "deep work", "reading"),
            "workout" to listOf("gym", "workout", "sport", "run", "pump", "lift", "cardio", "training"),
            "energy booster" to listOf("energy", "hype", "banger", "fresh", "morning", "wake"),
            "party" to listOf("party", "dance", "club", "groove", "groovy", "friday", "rave"),
            "sleep" to listOf("sleep", "bedtime", "insomnia", "ambient", "lullaby"),
            "sad" to listOf("sad", "lonely", "cry", "heartbreak", "rainy", "rain", "blue", "melancholy"),
            "romance" to listOf("love", "romance", "romantic", "date", "dinner", "valentine"),
            "feel good" to listOf("happy", "good", "sunny", "summer", "sunset", "smile", "feel good"),
            "jazz" to listOf("jazz", "jazzy", "blues"),
            "hip-hop" to listOf("hip hop", "hip-hop", "rap", "trap", "phonk"),
            "rock" to listOf("rock", "heavy", "metal", "grunge", "punk"),
        )

        val MOOD_FALLBACK_QUERIES = mapOf(
            "chill" to "chill vibes", "focus" to "focus music", "workout" to "workout hits",
            "energy booster" to "energy booster", "party" to "party hits", "sleep" to "late night chill",
            "sad" to "sad songs", "romance" to "love songs", "feel good" to "feel good hits",
            "commute" to "driving songs", "jazz" to "jazz classics", "hip-hop" to "hip hop hits",
            "rock" to "rock anthems",
        )
    }
}
