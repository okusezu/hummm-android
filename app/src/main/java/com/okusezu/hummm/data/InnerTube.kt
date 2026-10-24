package com.okusezu.hummm.data

import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Raw access to YouTube Music's internal API (the one music.youtube.com uses),
 * modelled on ytmusicapi's request handling. All calls are blocking.
 *
 * [cookie] returns the signed-in browser cookie string, or null for guest mode.
 */
class InnerTube(
    private val http: OkHttpClient,
    private val cookie: () -> String?,
    var language: String = "en",
    var location: String = "",
) {
    @Volatile private var visitorId: String? = null

    val signedIn: Boolean get() = sapisid(cookie()) != null

    fun post(endpoint: String, body: JsonObject, query: String = "", mobile: Boolean = false): JsonElement {
        val cookie = cookie()
        val sapisid = sapisid(cookie)
        val payload = buildJsonObject {
            body.forEach { (k, v) -> put(k, v) }
            putJsonObject("context") {
                putJsonObject("client") {
                    if (mobile) {
                        // Timed lyrics are only served to the Android app.
                        put("clientName", "ANDROID_MUSIC")
                        put("clientVersion", "7.21.50")
                    } else {
                        put("clientName", "WEB_REMIX")
                        put("clientVersion", "1.${dateStamp()}.01.00")
                    }
                    put("hl", language)
                    if (location.isNotEmpty()) put("gl", location)
                }
                putJsonObject("user") {}
            }
        }
        val key = if (sapisid == null) "&key=$API_KEY" else ""
        val req = Request.Builder()
            .url("${ORIGIN}/youtubei/v1/$endpoint?alt=json$key$query")
            .post(payload.toString().toRequestBody(JSON_TYPE))
            .header("User-Agent", USER_AGENT)
            .header("Accept", "*/*")
            .header("Accept-Language", "$language;q=0.9")
            .header("Origin", ORIGIN)
            .header("X-Origin", ORIGIN)
            .header("X-Goog-AuthUser", "0")
            .apply {
                visitorId(sapisid != null)?.let { header("X-Goog-Visitor-Id", it) }
                if (sapisid != null) {
                    header("Cookie", cookie!!)
                    header("Authorization", sapisidHash(sapisid))
                } else {
                    header("Cookie", "SOCS=CAI")
                }
            }
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val msg = runCatching { Json.parseToJsonElement(text).path("error", "message").str }.getOrNull()
                throw IOException("YouTube Music returned HTTP ${resp.code}${msg?.let { ": $it" } ?: ""}")
            }
            return Json.parseToJsonElement(text)
        }
    }

    fun browse(browseId: String, params: String? = null, mobile: Boolean = false): JsonElement =
        post("browse", buildJsonObject {
            put("browseId", browseId)
            if (params != null) put("params", params)
        }, mobile = mobile)

    /** Follows a continuation token in either the legacy (ctoken) or 2025 (body) style. */
    fun continuation(endpoint: String, body: JsonObject, token: Continuation): JsonElement = when (token) {
        is Continuation.Legacy -> post(endpoint, body, "&ctoken=${token.token}&continuation=${token.token}&type=next")
        is Continuation.Command -> post(endpoint, buildJsonObject {
            body.forEach { (k, v) -> put(k, v) }
            put("continuation", token.token)
        })
    }

    private fun visitorId(signedIn: Boolean): String? {
        visitorId?.let { return it }
        if (signedIn) return null
        // Same trick as ytmusicapi: the home page embeds VISITOR_DATA in ytcfg.
        val html = runCatching {
            http.newCall(
                Request.Builder().url(ORIGIN).header("User-Agent", USER_AGENT).header("Cookie", "SOCS=CAI").build()
            ).execute().use { it.body?.string().orEmpty() }
        }.getOrDefault("")
        visitorId = Regex("\"VISITOR_DATA\"\\s*:\\s*\"([^\"]+)\"").find(html)?.groupValues?.get(1)
        return visitorId
    }

    sealed interface Continuation {
        val token: String
        data class Legacy(override val token: String) : Continuation
        data class Command(override val token: String) : Continuation

        companion object {
            /** The first "load more" token in [json], if any. */
            fun find(json: JsonElement?): Continuation? {
                json.findFirst("nextContinuationData")?.get("continuation").str?.let { return Legacy(it) }
                json.findFirst("continuationItemRenderer")
                    ?.path("continuationEndpoint", "continuationCommand", "token").str
                    ?.let { return Command(it) }
                return null
            }
        }
    }

    companion object {
        const val ORIGIN = "https://music.youtube.com"
        const val API_KEY = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30"
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"
        const val LOGIN_COOKIE = "__Secure-3PAPISID"
        private val JSON_TYPE = "application/json".toMediaType()

        fun sapisid(cookie: String?): String? = cookie
            ?.split(';')
            ?.map { it.trim() }
            ?.firstOrNull { it.startsWith("$LOGIN_COOKIE=") }
            ?.substringAfter('=')
            ?.takeIf { it.isNotEmpty() }

        /** Google's SAPISIDHASH request signature (see ytmusicapi.helpers.get_authorization). */
        fun sapisidHash(sapisid: String, now: Long = System.currentTimeMillis() / 1000): String {
            val digest = MessageDigest.getInstance("SHA-1").digest("$now $sapisid $ORIGIN".toByteArray())
            return "SAPISIDHASH ${now}_" + digest.joinToString("") { "%02x".format(it) }
        }

        private fun dateStamp(): String = SimpleDateFormat("yyyyMMdd", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date())
    }
}
