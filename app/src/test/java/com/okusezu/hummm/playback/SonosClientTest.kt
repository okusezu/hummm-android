package com.okusezu.hummm.playback

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SonosClientTest {
    @Test fun groupsListVisibleRoomsAndSkipBridgesAndBondedSpeakers() {
        FakeSonos().use { fake ->
            val groups = SonosClient(OkHttpClient(), "127.0.0.1", fake.port).groups()
            assertEquals(
                listOf(
                    SonosGroup("RINCON_A:1", "Office + Kitchen & Bar", "127.0.0.1", fake.port),
                    SonosGroup("RINCON_C:7", "Lounge", "10.0.0.10", 1400),
                ),
                groups,
            )
        }
    }

    @Test fun loadsPlaysAndReadsBackStatus() {
        FakeSonos().use { fake ->
            val sonos = SonosClient(OkHttpClient(), "127.0.0.1", fake.port)
            val url = "http://192.168.1.5:4000/tok/abc123.m4a"
            sonos.load(url, SonosClient.didl(url, "Rock & Roll <live>", "AC/DC", "", "https://img/x.jpg", 192_000))
            sonos.play()
            sonos.seek(61_000)
            sonos.setVolume(55)

            val load = fake.calls.first { it.first == "SetAVTransportURI" }.second
            assertEquals("0", load["InstanceID"])
            assertEquals(url, load["CurrentURI"])
            // The metadata survives the double escaping intact.
            val didl = load["CurrentURIMetaData"]!!
            assertTrue(didl.contains("<dc:title>Rock &amp; Roll &lt;live&gt;</dc:title>"))
            assertTrue(didl.contains("""duration="0:03:12.000">$url</res>"""))

            val s = sonos.status()
            assertEquals("PLAYING", s.state)
            assertEquals(61_000, s.positionMs)
            assertEquals(200_000, s.durationMs)
            assertEquals(url, s.trackUri)
            assertEquals(55, s.volume)
            assertEquals(false, s.muted)
        }
    }

    @Test fun upnpFaultsBecomeErrorsWithTheirCode() {
        FakeSonos().use { fake ->
            fake.refuse = "Seek"
            val e = runCatching { SonosClient(OkHttpClient(), "127.0.0.1", fake.port).seek(1000) }.exceptionOrNull()
            assertTrue(e is SonosException)
            assertEquals("Seek failed: UPnP error 701", e!!.message)
        }
    }

    @Test fun timesRoundTrip() {
        assertEquals("1:02:03", SonosClient.hms(3_723_000))
        assertEquals(3_723_000, SonosClient.millis("1:02:03"))
        assertEquals(0, SonosClient.millis("NOT_IMPLEMENTED"))
        assertEquals(192_000, SonosClient.millis("0:03:12.000"))
    }
}
