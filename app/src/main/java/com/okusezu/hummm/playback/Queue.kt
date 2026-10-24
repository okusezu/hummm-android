package com.okusezu.hummm.playback

import android.os.Bundle
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import com.okusezu.hummm.data.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/*
 * The play queue lives in the player's own playlist, so the lock screen,
 * notification and headset buttons all see the same queue as the app. These
 * helpers port hummm's queue.py semantics onto any Player (the service's
 * ExoPlayer or the UI's MediaController).
 */

const val URI_SCHEME = "hummm"

fun Track.toMediaItem(): MediaItem = MediaItem.Builder()
    .setMediaId(videoId)
    .setUri("$URI_SCHEME://$videoId")
    .setCustomCacheKey(videoId)
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album.ifEmpty { null })
            .setArtworkUri(thumbnail.ifEmpty { null }?.toUri())
            .setDurationMs(if (duration > 0) duration * 1000L else null)
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .setExtras(Bundle().apply {
                putStringArrayList("artists", ArrayList(artists))
                putStringArrayList("artistIds", ArrayList(artistIds))
                putString("albumId", albumId)
                putInt("duration", duration)
                putBoolean("liked", liked)
            })
            .build()
    )
    .build()

fun MediaItem.toTrack(): Track {
    val md = mediaMetadata
    val x = md.extras
    return Track(
        videoId = mediaId,
        title = md.title?.toString().orEmpty(),
        artists = x?.getStringArrayList("artists") ?: listOfNotNull(md.artist?.toString()),
        artistIds = x?.getStringArrayList("artistIds").orEmpty(),
        album = md.albumTitle?.toString().orEmpty(),
        albumId = x?.getString("albumId").orEmpty(),
        duration = x?.getInt("duration") ?: 0,
        thumbnail = md.artworkUri?.toString().orEmpty(),
        liked = x?.getBoolean("liked") ?: false,
    )
}

/** Session-only state the player itself doesn't track. */
object QueueState {
    private val _shuffle = MutableStateFlow(false)
    val shuffle: StateFlow<Boolean> = _shuffle.asStateFlow()
    /** Original order, kept while shuffled so it can be restored. */
    internal var unshuffled: List<Track>? = null

    internal fun setShuffle(on: Boolean) { _shuffle.value = on }
}

val Player.tracks: List<Track> get() = (0 until mediaItemCount).map { getMediaItemAt(it).toTrack() }
val Player.upcomingCount: Int get() = if (mediaItemCount == 0) 0 else mediaItemCount - currentMediaItemIndex - 1
val Player.currentTrack: Track? get() = currentMediaItem?.toTrack()
val Player.queueIds: Set<String> get() = (0 until mediaItemCount).map { getMediaItemAt(it).mediaId }.toSet()

/** Replaces the queue and starts playing [start]. Shuffle keeps [start] first. */
fun Player.playTracks(tracks: List<Track>, start: Int = 0) {
    if (tracks.isEmpty()) return
    QueueState.unshuffled = null
    var list = tracks
    var index = start.coerceIn(tracks.indices)
    if (QueueState.shuffle.value) {
        QueueState.unshuffled = tracks
        list = listOf(tracks[index]) + (tracks.take(index) + tracks.drop(index + 1)).shuffled()
        index = 0
    }
    setMediaItems(list.map { it.toMediaItem() }, index, 0)
    prepare()
    play()
}

fun Player.enqueue(tracks: List<Track>) {
    val wasEmpty = mediaItemCount == 0
    addMediaItems(tracks.map { it.toMediaItem() })
    if (wasEmpty) { prepare(); play() }
}

fun Player.enqueueNext(tracks: List<Track>) {
    if (mediaItemCount == 0) return enqueue(tracks)
    addMediaItems(currentMediaItemIndex + 1, tracks.map { it.toMediaItem() })
}

fun Player.replaceUpcoming(tracks: List<Track>) {
    val from = currentMediaItemIndex + 1
    if (from < mediaItemCount) removeMediaItems(from, mediaItemCount)
    addMediaItems(tracks.map { it.toMediaItem() })
}

fun Player.clearUpcoming() {
    val from = currentMediaItemIndex + 1
    if (from < mediaItemCount) removeMediaItems(from, mediaItemCount)
}

fun Player.toggleShuffle(): Boolean {
    val on = !QueueState.shuffle.value
    QueueState.setShuffle(on)
    if (mediaItemCount == 0) return on
    val head = tracks.take(currentMediaItemIndex + 1)
    val rest = tracks.drop(currentMediaItemIndex + 1)
    if (on) {
        QueueState.unshuffled = tracks
        replaceUpcoming(rest.shuffled())
    } else {
        // Restore original order for tracks still queued, keeping the current one.
        val original = QueueState.unshuffled
        if (original != null) {
            val played = head.map { it.videoId }.toSet()
            val restIds = rest.map { it.videoId }.toSet()
            replaceUpcoming(original.filter { it.videoId in restIds && it.videoId !in played })
        }
        QueueState.unshuffled = null
    }
    return on
}

/** off → all → one, like the desktop app's `r`. */
fun Player.cycleRepeat(): Int {
    repeatMode = when (repeatMode) {
        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
        else -> Player.REPEAT_MODE_OFF
    }
    return repeatMode
}
