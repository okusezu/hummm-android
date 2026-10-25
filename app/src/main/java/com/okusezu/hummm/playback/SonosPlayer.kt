package com.okusezu.hummm.playback

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.Executors

/**
 * Plays the queue on a Sonos group. hummm keeps the queue, as it does on the
 * phone, and the speaker plays one song at a time: when it stops at the end of
 * a song the next one is loaded. Commands run in order on one background
 * thread; the speaker is polled every second for its state, position and
 * volume. If someone starts something else on the speaker from the Sonos app,
 * [onLost] is called and the service moves playback back to the phone.
 */
@OptIn(UnstableApi::class)
class SonosPlayer(
    private val client: SonosClient,
    /** The relay URL the speaker fetches a song from, or null off Wi-Fi. */
    private val relayUrl: (videoId: String) -> String?,
    private val onLost: (reason: String) -> Unit,
    looper: Looper = Looper.getMainLooper(),
) : SimpleBasePlayer(looper) {
    private val main = Handler(looper)
    private val io = Executors.newSingleThreadExecutor { Thread(it, "sonos").apply { isDaemon = true } }

    private val items = mutableListOf<MediaItem>()
    private val uids = mutableListOf<Any>()
    private var index = 0
    private var playWhenReady = false
    private var prepared = false
    private var state = Player.STATE_IDLE
    private var repeat = Player.REPEAT_MODE_OFF
    private var positionMs = 0L
    /** When [positionMs] was true; while playing the position runs on from here. */
    private var positionAt = SystemClock.elapsedRealtime()
    private var advancing = false
    private var durationMs = C.TIME_UNSET
    private var volume: Int? = null
    private var muted = false

    /** The queue entry loaded on the speaker, its URL, and whether it has been heard playing yet. */
    private var loaded: Any? = null
    private var loadedUrl: String? = null
    private var loadedAt = 0L
    private var heardPlaying = false
    /** Bumped by every command, so a status read that raced one is thrown away. */
    private var commandSeq = 0
    private var released = false

    init { main.post(::poll) }

    // ---- commands --------------------------------------------------------------

    /** Runs [block] on the Sonos thread after any earlier commands; failures are reported. */
    private fun send(what: String, quiet: Boolean = false, block: SonosClient.() -> Unit) {
        commandSeq++
        io.execute {
            try { client.block() } catch (e: Exception) {
                if (!quiet) main.post { if (!released) PlayerErrors.report("Sonos: couldn't $what (${e.message})") }
            }
        }
    }

    /** Puts queue entry [i] on the speaker, from [startMs]. */
    private fun load(i: Int, startMs: Long) {
        val item = items[i]
        val url = relayUrl(item.mediaId)
        if (url == null) {
            PlayerErrors.report("Sonos needs this phone on the same Wi-Fi as the speakers")
            return
        }
        val md = item.mediaMetadata
        val didl = SonosClient.didl(
            url, md.title?.toString().orEmpty(), md.artist?.toString().orEmpty(), md.albumTitle?.toString().orEmpty(),
            md.artworkUri?.toString().orEmpty(), md.durationMs ?: 0L,
        )
        loaded = uids[i]
        loadedUrl = url
        loadedAt = SystemClock.elapsedRealtime()
        heardPlaying = false
        state = Player.STATE_BUFFERING
        setPosition(startMs, running = false)
        durationMs = md.durationMs ?: C.TIME_UNSET
        val play = playWhenReady
        send("play “${md.title}”") {
            load(url, didl)
            if (startMs > 1000) runCatching { seek(startMs) }
            if (play) play()
        }
    }

    private fun unload() {
        loaded = null
        loadedUrl = null
    }

    private fun setPosition(ms: Long, running: Boolean) {
        positionMs = ms
        positionAt = SystemClock.elapsedRealtime()
        advancing = running
    }

    private fun currentPosition(): Long {
        val p = if (advancing) positionMs + (SystemClock.elapsedRealtime() - positionAt) else positionMs
        return if (durationMs > 0) p.coerceAtMost(durationMs) else p
    }

    override fun handleSetMediaItems(mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
        items.clear(); uids.clear()
        items += mediaItems
        repeat(mediaItems.size) { uids += Any() }
        index = if (startIndex == C.INDEX_UNSET) 0 else startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        setPosition(if (startPositionMs == C.TIME_UNSET) 0 else startPositionMs, running = false)
        if (items.isEmpty()) { unload(); if (prepared) state = Player.STATE_ENDED }
        else if (prepared) load(index, positionMs)
        return done()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        prepared = true
        if (items.isEmpty()) state = Player.STATE_ENDED else load(index, positionMs)
        return done()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        this.playWhenReady = playWhenReady
        if (loaded != null) {
            if (playWhenReady) send("play") { play() } else send("pause") { pause() }
            if (!playWhenReady) setPosition(currentPosition(), running = false)
        }
        return done()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        if (items.isEmpty()) return done()
        val start = if (positionMs == C.TIME_UNSET) 0 else positionMs
        val i = mediaItemIndex.coerceIn(0, items.size - 1)
        if (i != index || loaded != uids[i]) {
            index = i
            if (prepared) load(i, start) else setPosition(start, running = false)
        } else {
            setPosition(start, running = false)
            send("seek", quiet = true) { seek(start) }
        }
        return done()
    }

    override fun handleStop(): ListenableFuture<*> {
        if (loaded != null) send("stop", quiet = true) { stop() }
        unload()
        prepared = false
        state = Player.STATE_IDLE
        setPosition(currentPosition(), running = false)
        return done()
    }

    override fun handleRelease(): ListenableFuture<*> {
        released = true
        main.removeCallbacksAndMessages(null)
        io.shutdown() // lets queued commands (the stop) finish
        return done()
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        repeat = repeatMode
        return done()
    }

    override fun handleAddMediaItems(index: Int, mediaItems: MutableList<MediaItem>): ListenableFuture<*> {
        val at = index.coerceIn(0, items.size)
        items.addAll(at, mediaItems)
        uids.addAll(at, mediaItems.map { Any() })
        if (items.size == mediaItems.size) this.index = 0
        else if (at <= this.index) this.index += mediaItems.size
        return done()
    }

    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
        val to = toIndex.coerceAtMost(items.size)
        if (fromIndex >= to) return done()
        val removingCurrent = index in fromIndex until to
        repeat(to - fromIndex) { items.removeAt(fromIndex); uids.removeAt(fromIndex) }
        when {
            items.isEmpty() -> {
                index = 0
                if (loaded != null) send("stop", quiet = true) { stop() }
                unload()
                if (prepared) state = Player.STATE_ENDED
            }
            removingCurrent -> {
                index = fromIndex.coerceAtMost(items.size - 1)
                if (prepared) load(index, 0) else setPosition(0, running = false)
            }
            index >= to -> index -= to - fromIndex
        }
        return done()
    }

    override fun handleMoveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int): ListenableFuture<*> {
        val current = uids.getOrNull(index)
        val movedItems = items.subList(fromIndex, toIndex).toList()
        val movedUids = uids.subList(fromIndex, toIndex).toList()
        repeat(toIndex - fromIndex) { items.removeAt(fromIndex); uids.removeAt(fromIndex) }
        items.addAll(newIndex, movedItems)
        uids.addAll(newIndex, movedUids)
        if (current != null) index = uids.indexOf(current)
        return done()
    }

    override fun handleReplaceMediaItems(fromIndex: Int, toIndex: Int, mediaItems: MutableList<MediaItem>): ListenableFuture<*> {
        // Same songs in place (e.g. a "liked" flag changing) keep their entries so playback isn't disturbed.
        for (k in mediaItems.indices) {
            val i = fromIndex + k
            if (i < toIndex && items[i].mediaId == mediaItems[k].mediaId) items[i] = mediaItems[k]
            else return super.handleReplaceMediaItems(fromIndex, toIndex, mediaItems)
        }
        if (toIndex - fromIndex > mediaItems.size) handleRemoveMediaItems(fromIndex + mediaItems.size, toIndex)
        return done()
    }

    // ---- volume: 0..STEPS on the phone, 0..100 on the speaker ----------------------

    override fun handleSetDeviceVolume(deviceVolume: Int, flags: Int): ListenableFuture<*> = setVolume(deviceVolume * STEP)
    override fun handleIncreaseDeviceVolume(flags: Int): ListenableFuture<*> = setVolume((volume ?: 0) + STEP)
    override fun handleDecreaseDeviceVolume(flags: Int): ListenableFuture<*> = setVolume((volume ?: 0) - STEP)

    override fun handleSetDeviceMuted(muted: Boolean, flags: Int): ListenableFuture<*> {
        this.muted = muted
        send("mute", quiet = true) { setMuted(muted) }
        return done()
    }

    private fun setVolume(v: Int): ListenableFuture<*> {
        val level = v.coerceIn(0, 100)
        volume = level
        CastStatus.setVolume(level)
        val unmute = muted
        muted = false
        send("set the volume", quiet = true) { if (unmute) setMuted(false); setVolume(level) }
        return done()
    }

    /** One volume key press; for the activity's fallback when the session doesn't route the keys. */
    fun stepVolume(direction: Int) {
        if (direction > 0) increaseDeviceVolume(0) else decreaseDeviceVolume(0)
    }

    // ---- following the speaker -----------------------------------------------------

    private fun poll() {
        if (released) return
        val seq = commandSeq
        io.execute {
            val status = runCatching { client.status() }
            main.post {
                if (released) return@post
                status.onSuccess { if (seq == commandSeq) apply(it) }
                main.postDelayed(::poll, POLL_MS)
            }
        }
    }

    /** Folds a status read into the player's state. */
    fun apply(s: SonosClient.Status) {
        s.volume?.let { if (it != volume) { volume = it; CastStatus.setVolume(it) } }
        s.muted?.let { muted = it }
        val url = loadedUrl
        if (loaded == null || url == null) return invalidateState()
        val ours = sameSong(s.trackUri, url)
        if (s.trackUri.isNotEmpty() && !ours && heardPlaying) {
            unload()
            onLost("Sonos is playing something else now — back on this phone")
            return
        }
        if (!ours) return invalidateState() // not loaded yet
        if (s.durationMs > 0) durationMs = s.durationMs
        when (s.state) {
            "PLAYING" -> {
                heardPlaying = true
                state = Player.STATE_READY
                setPosition(s.positionMs, running = true)
            }
            "PAUSED_PLAYBACK" -> {
                heardPlaying = true
                state = Player.STATE_READY
                setPosition(s.positionMs, running = false)
            }
            "TRANSITIONING" -> state = Player.STATE_BUFFERING
            "STOPPED", "NO_MEDIA_PRESENT" -> when {
                heardPlaying && playWhenReady -> return songEnded()
                playWhenReady && SystemClock.elapsedRealtime() - loadedAt > LOAD_TIMEOUT_MS -> {
                    PlayerErrors.report("Sonos couldn't play “${items[index].mediaMetadata.title}”")
                    return songEnded()
                }
                heardPlaying -> { state = Player.STATE_READY; setPosition(s.positionMs, running = false) }
            }
        }
        invalidateState()
    }

    private fun songEnded() {
        val next = when {
            repeat == Player.REPEAT_MODE_ONE -> index
            index + 1 < items.size -> index + 1
            repeat == Player.REPEAT_MODE_ALL && items.isNotEmpty() -> 0
            else -> -1
        }
        if (next >= 0) {
            index = next
            load(next, 0)
        } else {
            unload()
            state = Player.STATE_ENDED
            setPosition(durationMs.coerceAtLeast(0), running = false)
        }
        invalidateState()
    }

    // ---- state ---------------------------------------------------------------------

    override fun getState(): State {
        val b = State.Builder()
            .setAvailableCommands(COMMANDS)
            .setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(if (items.isEmpty() && state != Player.STATE_IDLE) Player.STATE_ENDED else state)
            .setRepeatMode(repeat)
            .setDeviceInfo(INFO)
            .setDeviceVolume((volume ?: 0) / STEP)
            .setIsDeviceMuted(muted)
            .setPlaylist(items.mapIndexed { i, item ->
                val d = if (i == index && durationMs > 0) durationMs else item.mediaMetadata.durationMs ?: C.TIME_UNSET
                MediaItemData.Builder(uids[i])
                    .setMediaItem(item)
                    .setDurationUs(if (d == C.TIME_UNSET) C.TIME_UNSET else d * 1000)
                    .setIsSeekable(true)
                    .build()
            })
        if (items.isNotEmpty()) {
            b.setCurrentMediaItemIndex(index)
            b.setContentPositionMs(PositionSupplier { currentPosition() })
        }
        return b.build()
    }

    private fun done(): ListenableFuture<*> = Futures.immediateVoidFuture()

    /** The speaker may tidy the URL it reports; the relay's last path segment (<videoId>.m4a) is what matters. */
    private fun sameSong(reported: String, ours: String) =
        reported == ours || reported.substringBefore('?').substringAfterLast('/') == ours.substringAfterLast('/')

    private companion object {
        const val POLL_MS = 1000L
        /** How long a song may take to start before it's treated as unplayable. */
        const val LOAD_TIMEOUT_MS = 15_000L
        /** Volume per key press, in the speaker's 0..100. */
        const val STEP = 5
        val INFO = DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE).setMinVolume(0).setMaxVolume(100 / STEP).build()

        @Suppress("DEPRECATION")
        val COMMANDS = Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE, Player.COMMAND_PREPARE, Player.COMMAND_STOP, Player.COMMAND_RELEASE,
            Player.COMMAND_SEEK_TO_DEFAULT_POSITION, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_MEDIA_ITEM,
            Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD, Player.COMMAND_SET_REPEAT_MODE,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_TIMELINE, Player.COMMAND_GET_METADATA,
            Player.COMMAND_SET_MEDIA_ITEM, Player.COMMAND_CHANGE_MEDIA_ITEMS,
            Player.COMMAND_GET_DEVICE_VOLUME, Player.COMMAND_SET_DEVICE_VOLUME, Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS,
            Player.COMMAND_ADJUST_DEVICE_VOLUME, Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS,
        ).build()
    }
}
