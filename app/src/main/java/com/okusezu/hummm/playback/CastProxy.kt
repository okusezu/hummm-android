package com.okusezu.hummm.playback

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import java.io.IOException
import java.io.OutputStream
import java.net.Inet4Address
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import kotlin.concurrent.thread

/**
 * Serves songs to a Chromecast over the local network.
 *
 * A Chromecast can't use the phone's googlevideo URLs itself: they are tied to
 * the client that resolved them and expire after a few hours. So cast queue
 * items point here instead (http://<phone>:<port>/<token>/<videoId>.m4a) and
 * each request is resolved when the Chromecast actually asks for it, then
 * relayed with Range passed through so it can buffer and seek. The random
 * token keeps other devices on the network from using the relay.
 */
class CastProxy(
    private val http: OkHttpClient,
    private val resolve: (videoId: String) -> String,
    private val invalidate: (videoId: String) -> Unit,
) {
    private val token = UUID.randomUUID().toString().replace("-", "")
    private var server: ServerSocket? = null
    private var workers: ExecutorService? = null

    val running: Boolean get() = server?.isClosed == false

    @Synchronized
    fun start() {
        if (running) return
        val socket = ServerSocket(0)
        val pool = Executors.newCachedThreadPool()
        server = socket
        workers = pool
        thread(name = "cast-proxy", isDaemon = true) {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                pool.execute {
                    // An exception escaping a worker thread would crash the app.
                    try { serve(client) } catch (e: Exception) { Log.d(TAG, "request failed: ${e.message}") }
                }
            }
        }
    }

    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        workers?.shutdownNow()
        server = null
        workers = null
    }

    /** The URL a Chromecast uses for [videoId], or null when the relay isn't running. */
    fun url(host: String, videoId: String): String? =
        server?.takeIf { !it.isClosed }?.let { "http://$host:${it.localPort}/$token/$videoId.m4a" }

    private fun serve(client: Socket) = client.use { socket ->
        socket.soTimeout = 30_000
        val reader = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val requestLine = reader.readLine() ?: return
        val headers = generateSequence { reader.readLine()?.takeIf { it.isNotEmpty() } }
            .associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
        val method = requestLine.substringBefore(' ')
        val path = requestLine.substringAfter(' ').substringBefore(' ')
        val out = socket.getOutputStream().buffered()

        val parts = path.trim('/').split('/')
        val id = parts.takeIf { it.size == 2 && it[0] == token }?.get(1)?.substringBefore('.')
        if (method != "GET" && method != "HEAD" || id == null || !VIDEO_ID.matches(id)) {
            return respond(out, "404 Not Found")
        }
        val upstream = try {
            fetch(id, headers["range"])
        } catch (e: Exception) {
            Log.w(TAG, "couldn't fetch $id: ${e.message}")
            return respond(out, "502 Bad Gateway")
        }
        upstream.use { r ->
            val head = StringBuilder()
                .append("HTTP/1.1 ").append(if (r.code == 206) "206 Partial Content" else "200 OK").append("\r\n")
                .append("Content-Type: ").append(r.header("Content-Type") ?: "audio/mp4").append("\r\n")
            r.header("Content-Length")?.let { head.append("Content-Length: ").append(it).append("\r\n") }
            r.header("Content-Range")?.let { head.append("Content-Range: ").append(it).append("\r\n") }
            head.append("Accept-Ranges: bytes\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n")
            out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
            if (method == "GET") r.body?.byteStream()?.copyTo(out)
            out.flush()
        }
    }

    /** Opens the stream, resolving it afresh once if the cached URL has expired (403). */
    private fun fetch(id: String, range: String?): Response {
        repeat(2) { attempt ->
            val req = Request.Builder().url(resolve(id)).apply { range?.let { header("Range", it) } }.build()
            val r = http.newCall(req).execute()
            if (r.isSuccessful) return r
            r.close()
            if (r.code != 403 || attempt == 1) throw IOException("googlevideo returned HTTP ${r.code}")
            invalidate(id)
        }
        throw IOException("unreachable")
    }

    private fun respond(out: OutputStream, status: String) {
        out.write("HTTP/1.1 $status\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }

    companion object {
        private const val TAG = "CastProxy"
        private val VIDEO_ID = Regex("[A-Za-z0-9_-]{6,20}")

        /** The phone's IPv4 address on the current (Wi-Fi) network, which the Chromecast can reach. */
        fun lanAddress(context: Context): String? {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
            val props = cm.getLinkProperties(cm.activeNetwork) ?: return null
            return props.linkAddresses.map { it.address }
                .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
        }
    }
}
