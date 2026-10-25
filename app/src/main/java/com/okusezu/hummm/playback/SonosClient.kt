package com.okusezu.hummm.playback

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/*
 * Sonos over the local network: the UPnP/SOAP interface every Sonos speaker
 * serves on port 1400 (what the Sonos desktop app, Home Assistant and SoCo
 * use). Sonos's official API is cloud-only and can't play a stream from the
 * phone, so this is the way in. Songs reach the speaker through [CastProxy],
 * exactly as they do for a Chromecast.
 */

/** A room, or rooms grouped to play together; commands go to the group's coordinator at [host]. */
data class SonosGroup(val id: String, val name: String, val host: String, val port: Int = 1400)

class SonosException(message: String) : IOException(message)

/** One group's coordinator. Every call blocks; use it off the main thread. */
class SonosClient(http: OkHttpClient, val host: String, val port: Int = 1400) {
    private val http = http.newBuilder().callTimeout(6, TimeUnit.SECONDS).build()

    /** What the speaker is doing, as read by [status]. */
    data class Status(
        /** PLAYING, PAUSED_PLAYBACK, STOPPED, TRANSITIONING or NO_MEDIA_PRESENT. */
        val state: String,
        val positionMs: Long,
        val durationMs: Long,
        /** The stream the speaker is on: one of ours, or something started from the Sonos app. */
        val trackUri: String,
        val volume: Int?,
        val muted: Boolean?,
    )

    fun play() { av("Play", "Speed" to "1") }
    fun pause() { av("Pause") }
    fun stop() { av("Stop") }
    fun seek(ms: Long) { av("Seek", "Unit" to "REL_TIME", "Target" to hms(ms)) }

    /** Loads [uri] (described by [didl]) as the speaker's only item; [play] starts it. */
    fun load(uri: String, didl: String) { av("SetAVTransportURI", "CurrentURI" to uri, "CurrentURIMetaData" to didl) }

    /** Group volume 0..100 (one room's volume when it plays alone). */
    fun volume(): Int = tag(group("GetGroupVolume"), "CurrentVolume")?.toIntOrNull() ?: throw SonosException("no volume")
    fun setVolume(v: Int) { group("SetGroupVolume", "DesiredVolume" to v.coerceIn(0, 100).toString()) }
    fun muted(): Boolean = tag(group("GetGroupMute"), "CurrentMute") == "1"
    fun setMuted(m: Boolean) { group("SetGroupMute", "DesiredMute" to if (m) "1" else "0") }

    fun status(): Status {
        val transport = av("GetTransportInfo")
        val position = av("GetPositionInfo")
        return Status(
            state = tag(transport, "CurrentTransportState").orEmpty(),
            positionMs = millis(tag(position, "RelTime")),
            durationMs = millis(tag(position, "TrackDuration")),
            trackUri = tag(position, "TrackURI").orEmpty(),
            // Volume is a nicety; a speaker mid-regroup can refuse it.
            volume = runCatching { volume() }.getOrNull(),
            muted = runCatching { muted() }.getOrNull(),
        )
    }

    /** Every group in this speaker's household. */
    fun groups(): List<SonosGroup> {
        val body = call("/ZoneGroupTopology/Control", "ZoneGroupTopology", "GetZoneGroupState")
        return parseZoneGroups(tag(body, "ZoneGroupState") ?: throw SonosException("no zone groups"))
    }

    private fun av(action: String, vararg args: Pair<String, String>) =
        call("/MediaRenderer/AVTransport/Control", "AVTransport", action, "InstanceID" to "0", *args)

    private fun group(action: String, vararg args: Pair<String, String>) =
        call("/MediaRenderer/GroupRenderingControl/Control", "GroupRenderingControl", action, "InstanceID" to "0", *args)

    private fun call(path: String, service: String, action: String, vararg args: Pair<String, String>): String {
        val urn = "urn:schemas-upnp-org:service:$service:1"
        val envelope = buildString {
            append("""<?xml version="1.0" encoding="utf-8"?>""")
            append("""<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">""")
            append("<s:Body><u:").append(action).append(""" xmlns:u="""").append(urn).append("\">")
            for ((k, v) in args) append('<').append(k).append('>').append(escape(v)).append("</").append(k).append('>')
            append("</u:").append(action).append("></s:Body></s:Envelope>")
        }
        val req = Request.Builder()
            .url("http://$host:$port$path")
            .header("SOAPACTION", "\"$urn#$action\"")
            .post(envelope.toRequestBody("text/xml; charset=\"utf-8\"".toMediaType()))
            .build()
        http.newCall(req).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                // UPnP reports failures as HTTP 500 with a numeric errorCode, e.g. 701 "transition not available".
                throw SonosException("$action failed: ${tag(body, "errorCode")?.let { "UPnP error $it" } ?: "HTTP ${r.code}"}")
            }
            return body
        }
    }

    companion object {
        private const val SSDP_ST = "urn:schemas-upnp-org:device:ZonePlayer:1"

        /**
         * Asks the network for Sonos speakers (SSDP) and returns the hosts that
         * answered. Needs a Wi-Fi multicast lock held on Android.
         */
        fun discover(timeoutMs: Int = 2500): List<Pair<String, Int>> {
            val search = (
                "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\n" +
                    "MX: 1\r\nST: $SSDP_ST\r\n\r\n"
                ).toByteArray()
            val found = LinkedHashSet<Pair<String, Int>>()
            // Replies come back unicast, so a plain socket is enough.
            DatagramSocket().use { socket ->
                socket.soTimeout = 300
                val group = InetSocketAddress(InetAddress.getByName("239.255.255.250"), 1900)
                val end = System.currentTimeMillis() + timeoutMs
                var sent = 0
                val buf = ByteArray(2048)
                while (System.currentTimeMillis() < end) {
                    // UDP gets lost: ask three times, spread out.
                    if (sent < 3 && System.currentTimeMillis() >= end - timeoutMs + sent * 600L) {
                        runCatching { socket.send(DatagramPacket(search, search.size, group)) }
                        sent++
                    }
                    val packet = DatagramPacket(buf, buf.size)
                    try { socket.receive(packet) } catch (_: SocketTimeoutException) { continue }
                    val text = String(packet.data, 0, packet.length, Charsets.ISO_8859_1)
                    if (!text.contains("Sonos", ignoreCase = true) && !text.contains(SSDP_ST)) continue
                    val location = text.lineSequence()
                        .firstOrNull { it.startsWith("LOCATION:", ignoreCase = true) }
                        ?.substringAfter(':')?.trim() ?: continue
                    runCatching { URL(location) }.getOrNull()?.let { found += it.host to (if (it.port > 0) it.port else 1400) }
                }
            }
            return found.toList()
        }

        /** Groups from a ZoneGroupState document, leaving out hidden members (bonded subs and surrounds) and bridges. */
        fun parseZoneGroups(xml: String): List<SonosGroup> =
            Regex("""<ZoneGroup\s([^>]*)>(.*?)</ZoneGroup>""", RegexOption.DOT_MATCHES_ALL).findAll(xml).mapNotNull { g ->
                val attrs = attributes(g.groupValues[1])
                val members = Regex("""<ZoneGroupMember\s([^>]*?)/?>""").findAll(g.groupValues[2])
                    .map { attributes(it.groupValues[1]) }.toList()
                val coordinator = members.firstOrNull { it["UUID"] == attrs["Coordinator"] } ?: return@mapNotNull null
                val location = runCatching { URL(coordinator["Location"]) }.getOrNull() ?: return@mapNotNull null
                val names = (listOf(coordinator) + members)
                    .filter { it["Invisible"] != "1" && it["IsZoneBridge"] != "1" }
                    .mapNotNull { it["ZoneName"] }.distinct()
                if (names.isEmpty()) return@mapNotNull null
                SonosGroup(
                    id = attrs["ID"] ?: attrs["Coordinator"].orEmpty(),
                    name = names.joinToString(" + "),
                    host = location.host,
                    port = if (location.port > 0) location.port else 1400,
                )
            }.toList()

        /** DIDL-Lite describing a song, so the Sonos app shows its title, artist and cover. */
        fun didl(uri: String, title: String, artist: String, album: String, artUrl: String, durationMs: Long): String = buildString {
            append("""<DIDL-Lite xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/" """)
            append("""xmlns:r="urn:schemas-rinconnetworks-com:metadata-1-0/" xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/">""")
            append("""<item id="hummm" parentID="-1" restricted="true">""")
            append("""<res protocolInfo="http-get:*:audio/mp4:*"""")
            if (durationMs > 0) append(""" duration="""").append(hms(durationMs)).append(".000\"")
            append('>').append(escape(uri)).append("</res>")
            append("<dc:title>").append(escape(title)).append("</dc:title>")
            if (artist.isNotEmpty()) append("<dc:creator>").append(escape(artist)).append("</dc:creator>")
            if (album.isNotEmpty()) append("<upnp:album>").append(escape(album)).append("</upnp:album>")
            if (artUrl.isNotEmpty()) append("<upnp:albumArtURI>").append(escape(artUrl)).append("</upnp:albumArtURI>")
            append("<upnp:class>object.item.audioItem.musicTrack</upnp:class></item></DIDL-Lite>")
        }

        fun hms(ms: Long): String {
            val s = (ms / 1000).coerceAtLeast(0)
            return "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
        }

        /** "0:03:12" (or "NOT_IMPLEMENTED", for streams) to milliseconds. */
        fun millis(hms: String?): Long {
            val parts = hms?.split(':')?.map { it.substringBefore('.').toLongOrNull() ?: return 0 } ?: return 0
            return parts.fold(0L) { acc, p -> acc * 60 + p } * 1000
        }

        /** The text of the first <name> element, unescaped. */
        fun tag(xml: String, name: String): String? =
            Regex("<(?:\\w+:)?$name(?:\\s[^>]*)?>(.*?)</(?:\\w+:)?$name>", RegexOption.DOT_MATCHES_ALL)
                .find(xml)?.groupValues?.get(1)?.let(::unescape)

        private fun attributes(s: String): Map<String, String> =
            Regex("""([\w:]+)="([^"]*)"""").findAll(s).associate { it.groupValues[1] to unescape(it.groupValues[2]) }

        fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

        fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&apos;", "'").replace("&amp;", "&")
    }
}
