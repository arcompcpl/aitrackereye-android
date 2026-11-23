package pl.arcom.aitrackereye

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import pl.arcom.aitrackereye.camera.*

class CameraCoreTest {
    @Test fun parseKeyValuesStripsTablePrefix() {
        assertEquals(mapOf("A[0].B" to "x=y", "result" to "OK"), parseKeyValues("table.A[0].B=x=y\nresult=OK\nśmieć"))
    }

    @Test fun badHosts() {
        for (host in listOf("", "http://1.2.3.4", "1.2.3.4:80", "a/b", "a@b", "1.2.3. 4")) {
            try {
                CameraAddress(host, "u", "p")
                fail("host '$host' powinien być odrzucony")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test fun urls() {
        val a = CameraAddress("192.168.1.10", "ad min", "p@ss:/", channel = 2, https = true, httpPort = 443)
        assertEquals(1, a.cgiChannel)
        assertEquals("https://192.168.1.10:443", a.baseUrl)
        assertTrue(a.rtspUrl(1), "ad%20min:p%40ss%3A%2F@192.168.1.10:554" in a.rtspUrl(1))
        assertTrue(CameraAddress("fe80::1", "u", "p").baseUrl.startsWith("http://[fe80::1]:"))
        assertEquals("rtsp://u:p@1.2.3.4:554/cam/playback?channel=1&starttime=2026_11_22_08_00_00&endtime=2026_11_22_08_05_00",
            CameraAddress("1.2.3.4", "u", "p").playbackUrl("2026-11-22 08:00:00", "2026-11-22 08:05:00"))
        assertFalse("p@ss" in a.toString())
    }

    @Test fun cgiEncoding() {
        assertEquals("action=setConfig&Record[0].TimeSection[1][0]=1%2008%3A00%3A00-16%3A00%3A00",
            cgiQuery(listOf("action" to "setConfig", "Record[0].TimeSection[1][0]" to "1 08:00:00-16:00:00")))
        assertEquals("/a/b[0]@x.dav", quote("/a/b[0]@x.dav", "/[]@.-_"))
        assertEquals("%C5%9B", quote("ś", ""))
    }

    @Test fun parseStorageTest() {
        val text = """
            list.info[0].Name=/dev/mmc0
            list.info[0].State=Success
            list.info[0].Detail[0].Path=/dev/mmc0
            list.info[0].Detail[0].Type=ReadWrite
            list.info[0].Detail[0].TotalBytes=31914983424.000000
            list.info[0].Detail[0].UsedBytes=3446711255.000000
            list.info[0].Detail[0].IsError=false
        """.trimIndent()
        val devs = parseStorage(parseKeyValues(text))
        assertEquals(1, devs.size)
        val d = devs[0]
        assertEquals("/dev/mmc0", d.path)
        assertEquals("ReadWrite", d.access)
        assertTrue(d.total > d.used && d.used > 0 && !d.error)
    }

    /** Przykład z RFC 2617, rozdz. 3.5. */
    @Test fun digestRfcExample() {
        val ch = parseDigestChallenge(listOf(
            "Basic realm=\"x\"",
            "Digest realm=\"testrealm@host.com\", qop=\"auth,auth-int\", " +
                "nonce=\"dcd98b7102dd2f0e8b11d0f600bfb0c093\", opaque=\"5ccc069c403ebaf9f0171e9517f40e41\""))!!
        assertEquals("testrealm@host.com", ch.realm)
        val h = digestAuthorization(ch, "Mufasa", "Circle Of Life", "GET", "/dir/index.html", 1, "0a4f113b")
        assertTrue(h, "response=\"6629fae49393a05397450978507c4ef1\"" in h)
        assertTrue(h, "nc=00000001" in h && "qop=\"auth\"" in h && "opaque=\"5ccc069c403ebaf9f0171e9517f40e41\"" in h)
    }

    @Test fun diagnosticHidesSecrets() {
        val s = Snapshot()
        s.config["X"] = mapOf("X.Password" to "tajne", "X.A" to "1")
        val text = redactedDiagnostic(s, "1.2.3.4", 1, "t")
        assertFalse("tajne" in text)
        assertTrue("X.A=1" in text)
    }
}
