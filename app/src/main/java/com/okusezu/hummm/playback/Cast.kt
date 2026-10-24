package com.okusezu.hummm.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.cast.DefaultMediaItemConverter
import androidx.media3.cast.MediaItemConverter
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaQueueItem
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/*
 * Chromecast support. Songs play on Google's Default Media Receiver; the
 * playback service swaps its ExoPlayer for a CastPlayer while a Cast session
 * is up, so the queue, notification and in-app controls carry on unchanged.
 */

/** Named in the manifest; the Cast SDK reads its options from here. */
class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions = CastOptions.Builder()
        .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
        // Media3's session already provides the notification and lock-screen controls.
        .setCastMediaOptions(CastMediaOptions.Builder().setNotificationOptions(null).setMediaSessionEnabled(false).build())
        .setResumeSavedSession(true)
        .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}

/** What the UI shows: the Cast device playing (null when playing on the phone) and its volume in %. */
object CastStatus {
    private val _device = MutableStateFlow<String?>(null)
    val device: StateFlow<String?> = _device.asStateFlow()
    internal fun set(name: String?) { _device.value = name }

    private val _volume = MutableStateFlow<Int?>(null)
    val volume: StateFlow<Int?> = _volume.asStateFlow()
    internal fun setVolume(percent: Int?) { _volume.value = percent }
}

/**
 * Queue items carry hummm://<videoId>; on the Chromecast they become relay
 * URLs ([CastProxy]), and items read back from the receiver turn back into
 * hummm:// so they still play on the phone after casting ends.
 */
@OptIn(UnstableApi::class)
class CastItemConverter(private val relayUrl: (videoId: String) -> String?) : MediaItemConverter {
    private val base = DefaultMediaItemConverter()

    override fun toMediaQueueItem(mediaItem: MediaItem): MediaQueueItem {
        val url = relayUrl(mediaItem.mediaId) ?: mediaItem.localConfiguration?.uri?.toString().orEmpty()
        return base.toMediaQueueItem(mediaItem.buildUpon().setUri(url).setMimeType(MimeTypes.AUDIO_MP4).build())
    }

    override fun toMediaItem(mediaQueueItem: MediaQueueItem): MediaItem {
        val item = base.toMediaItem(mediaQueueItem)
        return item.buildUpon().setUri("$URI_SCHEME://${item.mediaId}").setMimeType(null).build()
    }
}
