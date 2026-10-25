package com.okusezu.hummm.ui

import android.app.Application
import android.content.ComponentName
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.okusezu.hummm.HummmApp
import com.okusezu.hummm.data.InnerTube
import com.okusezu.hummm.data.Item
import com.okusezu.hummm.data.Lyrics
import com.okusezu.hummm.data.Music
import com.okusezu.hummm.data.NotSignedInException
import com.okusezu.hummm.data.Section
import com.okusezu.hummm.data.Track
import com.okusezu.hummm.playback.Discovery
import com.okusezu.hummm.playback.PlaybackService
import com.okusezu.hummm.playback.QueueState
import com.okusezu.hummm.playback.clearUpcoming
import com.okusezu.hummm.playback.currentTrack
import com.okusezu.hummm.playback.cycleRepeat
import com.okusezu.hummm.playback.enqueue
import com.okusezu.hummm.playback.enqueueNext
import com.okusezu.hummm.playback.playTracks
import com.okusezu.hummm.playback.replaceUpcoming
import com.okusezu.hummm.playback.toggleShuffle
import com.okusezu.hummm.playback.tracks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Result of a background load. */
sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ok<T>(val value: T) : Load<T>
    data class Err(val message: String, val needsSignIn: Boolean = false) : Load<Nothing>
}

enum class Tab { HOME, SEARCH, VIBE, LIBRARY }

sealed interface Route {
    data class Detail(val item: Item) : Route
    data class LibraryList(val kind: String) : Route
    data object Settings : Route
    data object Login : Route
    data object Equalizer : Route
}

enum class PlayerPage { NOW_PLAYING, QUEUE, LYRICS }

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as HummmApp
    val music: Music get() = app.music
    val settings get() = app.settings

    // ---- navigation ------------------------------------------------------------
    var tab by mutableStateOf(Tab.HOME)
        private set
    val stack = mutableStateListOf<Route>()
    var playerOpen by mutableStateOf(false)
    var playerPage by mutableStateOf(PlayerPage.NOW_PLAYING)
    var visualiser by mutableStateOf(false)
    /** Queue row highlighted for the mode-bar actions (the desktop's cursor), if any. */
    var queueCursor by mutableStateOf<Int?>(null)
    /** Lyrics keep the current line centred until the user scrolls them. */
    var lyricsFollow by mutableStateOf(true)

    fun selectTab(t: Tab) {
        if (t == tab) stack.clear()
        tab = t
        stack.clear()
    }

    fun push(r: Route) { stack.add(r) }

    /** Returns false when there was nothing to go back from. */
    fun back(): Boolean = when {
        playerOpen && visualiser -> { visualiser = false; true }
        playerOpen && queueCursor != null -> { queueCursor = null; true }
        playerOpen && playerPage != PlayerPage.NOW_PLAYING -> { playerPage = PlayerPage.NOW_PLAYING; true }
        playerOpen -> { playerOpen = false; true }
        stack.isNotEmpty() -> { stack.removeAt(stack.lastIndex); true }
        tab != Tab.HOME -> { tab = Tab.HOME; true }
        else -> false
    }

    fun open(item: Item) { playerOpen = false; push(Route.Detail(item)) }

    // ---- messages --------------------------------------------------------------
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages
    fun say(msg: String) { _messages.tryEmit(msg) }

    // ---- player state ------------------------------------------------------------
    var controller by mutableStateOf<MediaController?>(null)
        private set
    var current by mutableStateOf<Track?>(null)
        private set
    var queue by mutableStateOf<List<Track>>(emptyList())
        private set
    var queueIndex by mutableStateOf(-1)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var buffering by mutableStateOf(false)
        private set
    var positionMs by mutableStateOf(0L)
        private set
    var durationMs by mutableStateOf(0L)
        private set
    var repeatMode by mutableStateOf(Player.REPEAT_MODE_OFF)
        private set
    val shuffle get() = QueueState.shuffle
    val likedIds = mutableStateListOf<String>()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = syncFrom(player)
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            lyrics = null
            current?.let { onTrackChanged(it) }
        }
        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            controller?.let(::syncFrom)
        }
    }

    init {
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        viewModelScope.launch {
            val c = runCatching { MediaController.Builder(app, token).buildAsync().await() }.getOrElse {
                say("Couldn't start the player: ${it.message}")
                return@launch
            }
            controller = c
            c.addListener(listener)
            syncFrom(c)
            while (isActive) {
                // Position isn't pushed by the player; poll it while the app is open.
                positionMs = c.currentPosition
                if (c.duration > 0) durationMs = c.duration
                delay(250)
            }
        }
    }

    override fun onCleared() {
        controller?.release()
    }

    private fun syncFrom(p: Player) {
        val before = current?.videoId
        current = p.currentTrack
        queue = p.tracks
        queueIndex = p.currentMediaItemIndex
        isPlaying = p.isPlaying
        buffering = p.playbackState == Player.STATE_BUFFERING
        repeatMode = p.repeatMode
        positionMs = p.currentPosition
        durationMs = if (p.duration > 0) p.duration else (current?.duration ?: 0) * 1000L
        if (before != current?.videoId) current?.let { onTrackChanged(it) }
    }

    private var likeJob: Job? = null
    private fun onTrackChanged(t: Track) {
        if (t.liked && t.videoId !in likedIds) likedIds += t.videoId
        if (playerPage == PlayerPage.LYRICS) loadLyrics()
        if (!music.authed) return
        likeJob?.cancel()
        likeJob = viewModelScope.launch {
            val liked = withContext(Dispatchers.IO) { runCatching { music.likeStatus(t) }.getOrNull() } ?: return@launch
            if (liked && t.videoId !in likedIds) likedIds += t.videoId
            if (!liked) likedIds -= t.videoId
        }
    }

    // ---- playback actions -----------------------------------------------------
    private inline fun withPlayer(block: (MediaController) -> Unit) {
        controller?.let(block) ?: say("The player is still starting…")
    }

    fun playList(tracks: List<Track>, start: Int = 0) = withPlayer { it.playTracks(tracks, start) }
    fun playShuffled(tracks: List<Track>) = withPlayer {
        if (!QueueState.shuffle.value) it.toggleShuffle()
        it.playTracks(tracks, tracks.indices.random())
    }
    fun enqueue(tracks: List<Track>) = withPlayer {
        it.enqueue(tracks)
        say(if (tracks.size == 1) "Queued “${tracks[0].title}”" else "Queued ${tracks.size} songs")
    }
    fun enqueueNext(tracks: List<Track>) = withPlayer {
        it.enqueueNext(tracks)
        say(if (tracks.size == 1) "“${tracks[0].title}” plays next" else "${tracks.size} songs play next")
    }
    fun togglePlay() = withPlayer { if (it.isPlaying) it.pause() else { if (it.playbackState == Player.STATE_IDLE) it.prepare(); it.play() } }
    fun next() = withPlayer {
        if (it.hasNextMediaItem()) it.seekToNextMediaItem()
        else if (settings.autoDiscover.value) viewModelScope.launch { Discovery.discoverNow(it, 10, thenPlay = true) }
    }
    fun previous() = withPlayer { it.seekToPrevious() }
    fun seekFraction(f: Float) = withPlayer { if (durationMs > 0) it.seekTo((durationMs * f).toLong()) }
    fun seekTo(ms: Long) = withPlayer { it.seekTo(ms) }
    fun toggleShuffle() = withPlayer { say(if (it.toggleShuffle()) "Shuffle on" else "Shuffle off") }
    fun cycleRepeat() = withPlayer {
        say(when (it.cycleRepeat()) { Player.REPEAT_MODE_ALL -> "Repeat all"; Player.REPEAT_MODE_ONE -> "Repeat one"; else -> "Repeat off" })
    }
    fun jumpTo(i: Int) = withPlayer { it.seekTo(i, 0); it.play() }
    fun removeAt(i: Int) = withPlayer { it.removeMediaItem(i) }
    fun move(from: Int, to: Int) = withPlayer { if (to in 0 until it.mediaItemCount) it.moveMediaItem(from, to) }
    fun clearUpcoming() = withPlayer { it.clearUpcoming(); say("Cleared upcoming songs") }

    // ---- background work -------------------------------------------------------
    fun <T> launchIO(label: String, block: suspend () -> T, done: (T) -> Unit) {
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { block() } }
            r.onSuccess(done).onFailure { say(errorText(label, it)) }
        }
    }

    suspend fun <T> load(block: () -> T): Load<T> = withContext(Dispatchers.IO) {
        runCatching { Load.Ok(block()) }.getOrElse {
            Load.Err(errorText(null, it), it is NotSignedInException)
        }
    }

    private fun errorText(label: String?, e: Throwable): String {
        val msg = when (e) {
            is NotSignedInException -> e.message!!
            is java.net.UnknownHostException -> "You're offline"
            is java.io.IOException -> e.message ?: "Network error"
            else -> e.message ?: e.javaClass.simpleName
        }
        return if (label == null) msg else "$label failed: $msg"
    }

    fun radio(seed: Track? = current) {
        val t = seed ?: return say("Nothing is playing")
        say("Starting radio from “${t.title}”…")
        launchIO("Radio", { music.radio(t) }) { tracks ->
            withPlayer { p ->
                if (p.currentTrack?.videoId == t.videoId) p.replaceUpcoming(tracks)
                else p.playTracks(listOf(t) + tracks)
            }
            say("Radio: ${tracks.size} songs like “${t.title}”")
        }
    }

    fun toggleLike(t: Track? = current) {
        val track = t ?: return
        if (!music.authed) return say("Liking songs needs sign-in. Open Library → Settings")
        val like = track.videoId !in likedIds
        if (like) likedIds += track.videoId else likedIds -= track.videoId
        launchIO("Like", { music.rate(track, like) }) { say(if (like) "♥ Liked “${track.title}”" else "Removed like") }
    }

    fun savePlaylist(name: String, tracks: List<Track>) =
        launchIO("Save", { music.savePlaylist(name, tracks) }) { say("Saved ${tracks.size} songs to “$name”") }

    fun addToPlaylist(playlist: Item, tracks: List<Track>) =
        launchIO("Add to playlist", { music.addToPlaylist(playlist.id, tracks) }) {
            say("Added ${tracks.size} song${if (tracks.size == 1) "" else "s"} to “${playlist.title}”")
        }

    /** Opens the album/artist of [t]. */
    fun goToAlbum(t: Track) {
        if (t.albumId.isEmpty()) return say("No album for this song")
        open(Item(Item.Kind.ALBUM, t.albumId, t.album))
    }

    fun goToArtist(t: Track) {
        val id = t.artistIds.firstOrNull() ?: return say("No artist page for this song")
        open(Item(Item.Kind.ARTIST, id, t.artists.firstOrNull().orEmpty()))
    }

    // ---- home / search ----------------------------------------------------------
    var feed by mutableStateOf<Load<List<Section>>>(Load.Loading)
        private set

    fun loadFeed() {
        feed = Load.Loading
        viewModelScope.launch { feed = load { music.feed() } }
    }

    var query by mutableStateOf("")
    var results by mutableStateOf<Load<List<Section>>?>(null)
        private set
    private var searchJob: Job? = null

    fun search(q: String = query) {
        if (q.isBlank()) return
        searchJob?.cancel()
        results = Load.Loading
        searchJob = viewModelScope.launch { results = load { music.search(q.trim()) } }
    }

    /** Small cache so going back to a page doesn't refetch it. */
    private val pageCache = object : LinkedHashMap<String, Any>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Any>?) = size > 24
    }

    @Suppress("UNCHECKED_CAST")
    suspend fun <T : Any> cached(key: String, block: () -> T): Load<T> {
        pageCache[key]?.let { return Load.Ok(it as T) }
        val r = load(block)
        if (r is Load.Ok) pageCache[key] = r.value
        return r
    }

    fun invalidate(key: String) { pageCache.remove(key) }

    // ---- vibe & discovery -------------------------------------------------------
    var vibeStatus by mutableStateOf<String?>(null)
        private set
    var vibeBusy by mutableStateOf(false)
        private set

    fun startVibe(text: String) {
        if (text.isBlank() || vibeBusy) return
        vibeBusy = true
        vibeStatus = "Finding songs for “$text”…"
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { music.vibe(text) } }
            vibeBusy = false
            r.onSuccess { (desc, tracks) ->
                if (tracks.isEmpty()) {
                    vibeStatus = "Nothing matched “$text”. Try other words"
                } else {
                    playList(tracks)
                    vibeStatus = "Playing ${tracks.size} songs for “$text”\nvia $desc"
                }
            }.onFailure { vibeStatus = errorText("Vibe", it) }
        }
    }

    fun discoverNow(n: Int = 10) = withPlayer { viewModelScope.launch { Discovery.discoverNow(it, n) } }

    // ---- lyrics --------------------------------------------------------------------
    var lyrics by mutableStateOf<Load<Lyrics?>?>(null)
        private set
    private var lyricsFor: String? = null

    fun loadLyrics() {
        val t = current ?: return
        if (lyricsFor == t.videoId && lyrics is Load.Ok) return
        lyricsFor = t.videoId
        lyrics = Load.Loading
        viewModelScope.launch {
            val r = load { music.lyrics(t) }
            if (current?.videoId == t.videoId) lyrics = r
        }
    }

    // ---- account ---------------------------------------------------------------------
    var account by mutableStateOf(settings.account.value)
        private set

    /** Verifies [cookie] with YouTube Music, then stores it. */
    fun signIn(cookie: String, done: (Boolean) -> Unit) {
        if (InnerTube.sapisid(cookie) == null) {
            say("That login is missing YouTube's sign-in cookie. Sign in fully and try again")
            return done(false)
        }
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { Music(InnerTube(app.http, { cookie })).accountName() }
            }
            r.onSuccess { name ->
                settings.signIn(cookie, name)
                account = name
                pageCache.clear()
                loadFeed()
                say("Signed in as $name")
                done(true)
            }.onFailure {
                say("YouTube Music didn't accept this login: ${it.message}")
                done(false)
            }
        }
    }

    fun signOut() {
        settings.signOut()
        account = null
        likedIds.clear()
        pageCache.clear()
        android.webkit.CookieManager.getInstance().removeAllCookies(null)
        loadFeed()
        say("Signed out")
    }

    init {
        loadFeed()
    }
}
