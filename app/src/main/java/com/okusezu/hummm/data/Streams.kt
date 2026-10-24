package com.okusezu.hummm.data

import java.util.concurrent.ConcurrentHashMap
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo

/**
 * Resolves a video id to a playable audio URL. This is the job yt-dlp does
 * for mpv on the desktop; here NewPipeExtractor handles YouTube's signature
 * and throttling puzzles.
 */
class Streams(private val http: OkHttpClient) {
    private class Cached(val url: String, val expires: Long)
    private val cache = ConcurrentHashMap<String, Cached>()

    init {
        synchronized(Streams::class.java) {
            if (!initialized) {
                NewPipe.init(OkHttpDownloader(http))
                initialized = true
            }
        }
    }

    /** Blocking. [quality] is one of [QUALITIES]. */
    fun audioUrl(videoId: String, quality: String): String = resolve(videoId, quality) { pick(it, quality) }

    /**
     * Blocking. The stream a Chromecast plays: AAC in MP4 (M4A), which every
     * Cast device decodes, falling back to whatever exists.
     */
    fun castUrl(videoId: String): String = resolve(videoId, CAST) { streams ->
        streams.filter { it.format == MediaFormat.M4A }.maxByOrNull { it.averageBitrate }
            ?: pick(streams, "high")
    }

    private fun resolve(videoId: String, kind: String, choose: (List<AudioStream>) -> AudioStream?): String {
        val key = "$videoId/$kind"
        cache[key]?.takeIf { it.expires > System.currentTimeMillis() }?.let { return it.url }
        val info = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$videoId")
        val streams = info.audioStreams.filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.isUrl }
        val chosen = choose(streams) ?: throw IllegalStateException("no playable audio stream for this song")
        // googlevideo URLs carry their own expiry; stay well inside it.
        val expireSec = Regex("[?&]expire=(\\d+)").find(chosen.content)?.groupValues?.get(1)?.toLongOrNull()
        val expires = expireSec?.let { it * 1000 - 30 * 60_000 } ?: (System.currentTimeMillis() + 60 * 60_000)
        cache[key] = Cached(chosen.content, expires)
        return chosen.content
    }

    fun invalidate(videoId: String) {
        cache.keys.removeIf { it.startsWith("$videoId/") }
    }

    private fun pick(streams: List<AudioStream>, quality: String): AudioStream? {
        if (streams.isEmpty()) return null
        fun rate(s: AudioStream) = if (s.averageBitrate > 0) s.averageBitrate else s.bitrate / 1000
        // Prefer original-language tracks over dubbed ones when YouTube offers both.
        val pool = streams.filter { it.audioTrackType == null || it.audioTrackType?.name == "ORIGINAL" }.ifEmpty { streams }
        return when (quality) {
            "low" -> pool.minByOrNull(::rate)
            "standard" -> pool.filter { rate(it) in 1..128 }.maxByOrNull(::rate) ?: pool.minByOrNull(::rate)
            else -> pool.maxByOrNull(::rate)
        }
    }

    private class OkHttpDownloader(private val http: OkHttpClient) : Downloader() {
        override fun execute(request: Request): Response {
            val body = request.dataToSend()?.toRequestBody()
            val builder = okhttp3.Request.Builder()
                .url(request.url())
                .method(request.httpMethod(), body)
                .header("User-Agent", USER_AGENT)
            for ((name, values) in request.headers()) {
                builder.removeHeader(name)
                values.forEach { builder.addHeader(name, it) }
            }
            http.newCall(builder.build()).execute().use { resp ->
                if (resp.code == 429) throw ReCaptchaException("YouTube is rate-limiting this network", request.url())
                return Response(resp.code, resp.message, resp.headers.toMultimap(), resp.body?.string(), resp.request.url.toString())
            }
        }
    }

    companion object {
        val QUALITIES = listOf("high", "standard", "low")
        private const val CAST = "cast"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"
        @Volatile private var initialized = false
    }
}
