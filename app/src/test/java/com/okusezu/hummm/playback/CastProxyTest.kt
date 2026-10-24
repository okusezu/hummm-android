package com.okusezu.hummm.playback

import com.okusezu.hummm.data.Streams
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Plays the Chromecast's part against the relay. Live: run with -Plive. */
class CastProxyTest {
    private val http = OkHttpClient()

    @Test fun relaysRangeRequestsAsM4a() {
        assumeTrue(System.getProperty("live") == "true")
        val streams = Streams(http)
        val proxy = CastProxy(http, streams::castUrl, streams::invalidate)
        proxy.start()
        try {
            val url = proxy.url("127.0.0.1", "9RfVp-GhKfs")
            assertNotNull(url)

            http.newCall(Request.Builder().url(url!!).header("Range", "bytes=0-1023").build()).execute().use {
                assertEquals(206, it.code)
                assertEquals("audio/mp4", it.header("Content-Type"))
                assertNotNull(it.header("Content-Range"))
                val bytes = it.body!!.bytes()
                assertEquals(1024, bytes.size)
                assertEquals("ftyp", String(bytes, 4, 4)) // an MP4 file's first box
            }

            // A seek: the receiver asks for a range from the middle.
            http.newCall(Request.Builder().url(url).header("Range", "bytes=500000-500099").build()).execute().use {
                assertEquals(206, it.code)
                assertEquals(100, it.body!!.bytes().size)
            }

            http.newCall(Request.Builder().url(url).head().build()).execute().use { assertEquals(200, it.code) }

            val wrongToken = url.replace(Regex("/[0-9a-f]{32}/"), "/0123456789abcdef0123456789abcdef/")
            http.newCall(Request.Builder().url(wrongToken).build()).execute().use { assertEquals(404, it.code) }
        } finally {
            proxy.stop()
        }
    }
}
