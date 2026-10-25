package com.okusezu.hummm.playback

import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/** A Sonos speaker's SOAP interface, enough to play the speaker's part in tests. */
class FakeSonos : AutoCloseable {
    private val server = ServerSocket(0)
    val port: Int get() = server.localPort

    /** Every SOAP action received, in order, with its arguments. */
    val calls = CopyOnWriteArrayList<Pair<String, Map<String, String>>>()

    @Volatile var state = "STOPPED"
    @Volatile var uri = ""
    @Volatile var positionMs = 0L
    @Volatile var durationMs = 200_000L
    @Volatile var volume = 30
    @Volatile var muted = false
    /** An action to refuse with UPnP error 701 ("transition not available"), as a busy speaker does. */
    @Volatile var refuse: String? = null

    val topology = """<ZoneGroupState><ZoneGroups>
        <ZoneGroup Coordinator="RINCON_A" ID="RINCON_A:1">
          <ZoneGroupMember UUID="RINCON_A" Location="http://127.0.0.1:$port/xml/device_description.xml" ZoneName="Office"/>
          <ZoneGroupMember UUID="RINCON_B" Location="http://10.0.0.9:1400/xml/device_description.xml" ZoneName="Kitchen &amp; Bar"/>
        </ZoneGroup>
        <ZoneGroup Coordinator="RINCON_C" ID="RINCON_C:7">
          <ZoneGroupMember UUID="RINCON_C" Location="http://10.0.0.10:1400/xml/device_description.xml" ZoneName="Lounge">
            <Satellite UUID="RINCON_S" Location="http://10.0.0.11:1400/xml/device_description.xml" ZoneName="Lounge" Invisible="1"/>
          </ZoneGroupMember>
          <ZoneGroupMember UUID="RINCON_D" Location="http://10.0.0.12:1400/xml/device_description.xml" ZoneName="Sub" Invisible="1"/>
        </ZoneGroup>
        <ZoneGroup Coordinator="RINCON_E" ID="RINCON_E:2">
          <ZoneGroupMember UUID="RINCON_E" Location="http://10.0.0.13:1400/xml/device_description.xml" ZoneName="BOOST" IsZoneBridge="1"/>
        </ZoneGroup>
      </ZoneGroups></ZoneGroupState>"""

    init {
        thread(isDaemon = true, name = "fake-sonos") {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                socket.use { s ->
                    val input = s.getInputStream().bufferedReader(Charsets.UTF_8)
                    input.readLine() ?: return@use
                    val headers = generateSequence { input.readLine()?.takeIf { it.isNotEmpty() } }
                        .associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
                    val body = CharArray(headers["content-length"]?.toInt() ?: 0).also { var n = 0; while (n < it.size) n += input.read(it, n, it.size - n) }.let(::String)
                    val action = headers["soapaction"].orEmpty().trim('"').substringAfter('#')
                    val args = Regex("<(\\w+)>(.*?)</\\1>", RegexOption.DOT_MATCHES_ALL)
                        .findAll(body.substringAfter("<u:$action").substringAfter('>').substringBefore("</u:$action>"))
                        .associate { it.groupValues[1] to SonosClient.unescape(it.groupValues[2]) }
                    calls += action to args
                    val reply = if (action == refuse) null else respond(action, args)
                    val status = if (reply == null) "500 Internal Server Error" else "200 OK"
                    val out = reply ?: "<s:Envelope><s:Body><s:Fault><detail><UPnPError><errorCode>701</errorCode></UPnPError></detail></s:Fault></s:Body></s:Envelope>"
                    val bytes = out.toByteArray()
                    s.getOutputStream().write("HTTP/1.1 $status\r\nContent-Type: text/xml\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray() + bytes)
                }
            }
        }
    }

    private fun respond(action: String, a: Map<String, String>): String? {
        val out = when (action) {
            "SetAVTransportURI" -> { uri = a["CurrentURI"].orEmpty(); state = "STOPPED"; positionMs = 0; "" }
            "Play" -> { state = "PLAYING"; "" }
            "Pause" -> { state = "PAUSED_PLAYBACK"; "" }
            "Stop" -> { state = "STOPPED"; "" }
            "Seek" -> { positionMs = SonosClient.millis(a["Target"]); "" }
            "GetTransportInfo" -> "<CurrentTransportState>$state</CurrentTransportState>"
            "GetPositionInfo" -> "<TrackDuration>${SonosClient.hms(durationMs)}</TrackDuration>" +
                "<TrackURI>${SonosClient.escape(uri)}</TrackURI><RelTime>${SonosClient.hms(positionMs)}</RelTime>"
            "GetGroupVolume" -> "<CurrentVolume>$volume</CurrentVolume>"
            "SetGroupVolume" -> { volume = a["DesiredVolume"]!!.toInt(); "" }
            "GetGroupMute" -> "<CurrentMute>${if (muted) 1 else 0}</CurrentMute>"
            "SetGroupMute" -> { muted = a["DesiredMute"] == "1"; "" }
            "GetZoneGroupState" -> "<ZoneGroupState>${SonosClient.escape(topology)}</ZoneGroupState>"
            else -> return null
        }
        return "<s:Envelope><s:Body><u:${action}Response>$out</u:${action}Response></s:Body></s:Envelope>"
    }

    fun actions(): List<String> = calls.map { it.first }.filter { !it.startsWith("Get") }

    /** Waits for the player's background thread to send [n] commands (status reads aside). */
    fun awaitActions(n: Int, timeoutMs: Long = 5000): List<String> {
        val end = System.currentTimeMillis() + timeoutMs
        while (actions().size < n && System.currentTimeMillis() < end) Thread.sleep(10)
        return actions()
    }

    override fun close() = server.close()
}
