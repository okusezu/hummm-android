package com.okusezu.hummm.playback

import androidx.media3.common.Player
import com.okusezu.hummm.HummmApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Discovery: finds songs related to what's playing (by the chosen metric) and
 * appends them to the queue. Auto-discover tops the queue up whenever it is
 * about to run dry; the playback service drives that so it keeps working with
 * the app closed.
 */
object Discovery {
    sealed interface Status {
        data object Idle : Status
        data class Busy(val metric: String) : Status
        data class Done(val count: Int, val metric: String) : Status
        data class Failed(val message: String) : Status
    }

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    /** Must be called on the player's (main) thread. */
    suspend fun discoverNow(player: Player, n: Int, thenPlay: Boolean = false) {
        if (_status.value is Status.Busy) return
        val app = HummmApp.instance
        val metric = app.settings.metric.value
        val seed = player.currentTrack ?: player.tracks.lastOrNull()
        val exclude = player.queueIds
        _status.value = Status.Busy(metric)
        val result = withContext(Dispatchers.IO) {
            runCatching { app.music.discover(seed, metric, n, exclude) }
        }
        result.onSuccess { tracks ->
            if (tracks.isEmpty()) {
                _status.value = Status.Failed("discovery found nothing new")
                return
            }
            val start = player.mediaItemCount
            player.enqueue(tracks)
            if (thenPlay && start < player.mediaItemCount) {
                player.seekTo(start, 0)
                player.play()
            }
            _status.value = Status.Done(tracks.size, metric)
        }.onFailure {
            _status.value = Status.Failed("discovery failed: ${it.message}")
        }
    }

    fun shouldTopUp(player: Player): Boolean =
        HummmApp.instance.settings.autoDiscover.value && player.mediaItemCount > 0 &&
            player.upcomingCount <= 1 && _status.value !is Status.Busy
}
