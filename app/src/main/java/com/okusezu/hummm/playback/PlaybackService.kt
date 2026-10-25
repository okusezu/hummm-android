package com.okusezu.hummm.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.audiofx.Equalizer
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.okusezu.hummm.HummmApp
import com.okusezu.hummm.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Background playback: ExoPlayer plus a MediaSession, which gives the
 * notification, lock-screen, Bluetooth and Android Auto controls (the phone's
 * equivalent of the desktop app's MPRIS service). While a Chromecast session
 * is up the session drives a CastPlayer instead, and while a Sonos group is
 * chosen a [SonosPlayer]; both are fed through [CastProxy].
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private lateinit var exo: ExoPlayer
    private var castPlayer: CastPlayer? = null
    /** [castPlayer] with Chromecast volume; this is what the session drives while casting. */
    private var castVolume: CastVolumePlayer? = null
    private var sonos: SonosPlayer? = null
    private var proxy: CastProxy? = null
    private var castLocks: Pair<PowerManager.WakeLock, WifiManager.WifiLock>? = null
    /** Whichever player the session currently drives. */
    private val player: Player get() = session?.player ?: exo
    private var session: MediaSession? = null
    private var equalizer: Equalizer? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var retriedId: String? = null

    override fun onCreate() {
        super.onCreate()
        val app = HummmApp.instance

        // Queue items carry hummm://<videoId>; the real (expiring) googlevideo
        // URL is only looked up when ExoPlayer actually opens the stream.
        val http = OkHttpDataSource.Factory(app.http)
        val resolving = ResolvingDataSource.Factory(http) { spec ->
            if (spec.uri.scheme == URI_SCHEME) {
                val id = spec.uri.host ?: spec.uri.toString().substringAfter("://")
                spec.withUri(Uri.parse(app.streams.audioUrl(id, app.settings.quality.value)))
            } else spec
        }
        // The default sink plus a tee that feeds the visualiser (see Spectrum).
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf(TeeAudioProcessor(Spectrum)))
                    .setAudioTrackBufferSizeProvider(Spectrum.bufferSizes)
                    .build()
        }
        exo = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(resolving))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        exo.addListener(listener)

        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, exo)
            .setSessionActivity(open)
            .setCallback(callback)
            .build()

        scope.launch { app.settings.eq.collect { applyEq(it) } }
        audioSessionChanged(exo.audioSessionId)
        setUpCast()
        setUpSonos()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away while paused shouldn't leave a stale notification.
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        Sonos.disconnect()
        sonos?.release()
        equalizer?.release()
        castVolume?.attach(null)
        castPlayer?.run {
            setSessionAvailabilityListener(null)
            release()
        }
        stopRelay()
        CastStatus.set(null)
        exo.release()
        session?.release()
        session = null
        super.onDestroy()
    }

    // ---- Chromecast ------------------------------------------------------------

    private fun setUpCast() {
        val app = HummmApp.instance
        proxy = CastProxy(app.http, app.streams::castUrl, app.streams::invalidate)
        val cast = app.castContext ?: return
        val converter = CastItemConverter(::relayUrl)
        val castPlayer = CastPlayer(cast, converter).also { castPlayer = it }
        val withVolume = CastVolumePlayer(castPlayer).also { castVolume = it }
        castPlayer.addListener(listener)
        castPlayer.setSessionAvailabilityListener(object : SessionAvailabilityListener {
            override fun onCastSessionAvailable() {
                switchTo(withVolume, castName())
                Sonos.disconnect() // one speaker at a time
            }

            override fun onCastSessionUnavailable() {
                if (player === withVolume) switchTo(exo, null)
            }
        })
        if (castPlayer.isCastSessionAvailable) switchTo(withVolume, castName())
    }

    private fun castName() =
        HummmApp.instance.castContext?.sessionManager?.currentCastSession?.castDevice?.friendlyName ?: "Chromecast"

    /** Where a Chromecast or Sonos speaker fetches [videoId] from, or null when the phone isn't on a LAN. */
    private fun relayUrl(videoId: String): String? = CastProxy.lanAddress(this)?.let { proxy?.url(it, videoId) }

    // ---- Sonos ---------------------------------------------------------------

    private fun setUpSonos() {
        scope.launch {
            Sonos.target.collect { group ->
                val old = sonos
                if (group == null) {
                    if (old != null && player === old) {
                        switchTo(exo, null)
                        if (Sonos.pauseOnReturn) exo.pause()
                    }
                    Sonos.pauseOnReturn = false
                    old?.release()
                    sonos = null
                    return@collect
                }
                // A Chromecast hands over first; its session ending must not pull the queue back to the phone.
                if (player === castVolume) {
                    switchTo(exo, null)
                    HummmApp.instance.castContext?.sessionManager?.endCurrentSession(true)
                }
                val next = SonosPlayer(SonosClient(HummmApp.instance.http, group.host, group.port), ::relayUrl, onLost = { reason ->
                    PlayerErrors.report(reason)
                    Sonos.disconnect(pause = true)
                })
                next.addListener(listener)
                sonos = next
                switchTo(next, group.name)
                old?.release()
            }
        }
    }

    /** Moves the queue, position and play state onto [target] and hands it to the session; [name] is the speaker shown in the UI. */
    private fun switchTo(target: Player, name: String?) {
        val from = player
        if (from === target) return
        if (target !== exo) {
            startRelay()
            if (CastProxy.lanAddress(this) == null) PlayerErrors.report("Playing on $name needs this phone on the same Wi-Fi")
        }
        val items = (0 until from.mediaItemCount).map { from.getMediaItemAt(it) }
        val index = from.currentMediaItemIndex
        val position = if (from.playbackState == Player.STATE_ENDED) 0 else from.currentPosition
        val playing = from.playWhenReady
        target.repeatMode = from.repeatMode
        from.stop()
        from.clearMediaItems()
        session?.player = target
        if (items.isNotEmpty()) {
            target.setMediaItems(items, index, position)
            target.playWhenReady = playing
            target.prepare()
        }
        if (target === exo) stopRelay()
        castVolume?.attach(if (target === castVolume) HummmApp.instance.castContext?.sessionManager?.currentCastSession else null)
        CastStatus.stepVolume = (target as? SonosPlayer)?.let { it::stepVolume } ?: CastVolume::step
        CastStatus.set(if (target === exo) null else name)
    }

    /** The phone must keep serving audio with the screen off while the Chromecast plays. */
    private fun startRelay() {
        proxy?.start()
        if (castLocks != null) return
        val wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hummm:cast")
        @Suppress("DEPRECATION")
        val wifi = getSystemService(WifiManager::class.java).createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "hummm:cast")
        wake.acquire()
        wifi.acquire()
        castLocks = wake to wifi
    }

    private fun stopRelay() {
        proxy?.stop()
        castLocks?.let { (wake, wifi) -> if (wake.isHeld) wake.release(); if (wifi.isHeld) wifi.release() }
        castLocks = null
    }

    private val callback = object : MediaSession.Callback {
        // Controllers can't send URIs, only ids; rebuild playable items here.
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = Futures.immediateFuture(
            mediaItems.map { item ->
                item.buildUpon().setUri("$URI_SCHEME://${item.mediaId}").setCustomCacheKey(item.mediaId).build()
            }.toMutableList()
        )
    }

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            retriedId = null
            topUp()
        }

        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) topUp()
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED && HummmApp.instance.settings.autoDiscover.value) {
                scope.launch { Discovery.discoverNow(player, 10, thenPlay = true) }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val id = player.currentMediaItem?.mediaId ?: return
            val expired = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode == 403
            val retryable = expired || error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
            if (player === exo && retriedId != id && retryable) {
                // Stream URL expired or the network blipped: resolve again once.
                retriedId = id
                HummmApp.instance.streams.invalidate(id)
                val pos = player.currentPosition
                player.prepare()
                player.seekTo(pos)
                player.play()
                return
            }
            PlayerErrors.report("Couldn't play “${player.currentMediaItem?.mediaMetadata?.title}”: ${error.message}")
            if (player.hasNextMediaItem()) {
                player.seekToNextMediaItem()
                player.prepare()
                player.play()
            }
        }

        override fun onAudioSessionIdChanged(audioSessionId: Int) = audioSessionChanged(audioSessionId)
    }

    private fun topUp() {
        if (Discovery.shouldTopUp(player)) scope.launch { Discovery.discoverNow(player, 10) }
    }

    // ---- equalizer -----------------------------------------------------------

    private fun audioSessionChanged(id: Int) {
        equalizer?.release()
        equalizer = null
        if (id == C.AUDIO_SESSION_ID_UNSET) return
        equalizer = runCatching { Equalizer(0, id) }.getOrNull()
        equalizer?.let { eq ->
            val (lo, hi) = eq.bandLevelRange.let { it[0].toInt() to it[1].toInt() }
            EqualizerInfo.publish(
                EqualizerInfo.Bands(
                    centersHz = (0 until eq.numberOfBands).map { eq.getCenterFreq(it.toShort()) / 1000 },
                    minLevel = lo,
                    maxLevel = hi,
                )
            )
        }
        applyEq(HummmApp.instance.settings.eq.value)
    }

    private fun applyEq(levels: List<Int>) {
        val eq = equalizer ?: return
        runCatching {
            if (levels.none { it != 0 } || levels.size != eq.numberOfBands.toInt()) {
                eq.enabled = false
                return
            }
            levels.forEachIndexed { band, level -> eq.setBandLevel(band.toShort(), level.toShort()) }
            eq.enabled = true
        }
    }
}

/** What the device's equalizer offers; published by the service for the EQ screen. */
object EqualizerInfo {
    data class Bands(val centersHz: List<Int>, val minLevel: Int, val maxLevel: Int)

    private val _bands = MutableStateFlow<Bands?>(null)
    val bands: StateFlow<Bands?> = _bands.asStateFlow()
    internal fun publish(b: Bands) { _bands.value = b }
}

/** Playback errors the service wants shown in the UI. */
object PlayerErrors {
    private val _last = MutableStateFlow<String?>(null)
    val last: StateFlow<String?> = _last.asStateFlow()
    fun report(msg: String) { _last.value = msg }
    fun consume() { _last.value = null }
}
