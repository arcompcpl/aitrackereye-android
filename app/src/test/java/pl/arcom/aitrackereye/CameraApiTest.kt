package pl.arcom.aitrackereye

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import pl.arcom.aitrackereye.camera.*
import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import java.security.MessageDigest
import java.time.LocalDateTime

/** Atrapa kamery (port tests/fake_camera.py) z prawdziwą weryfikacją HTTP Digest. */
class FakeCamera : Dispatcher() {
    val store = linkedMapOf<String, String>()
    val log = mutableListOf<RecordedRequest>()
    var failSet = false
    var time = "2026-11-22 17:52:49"
    private var findPos = 0
    val files: List<Triple<String, String, Long>>
    val fileSize = 300_000
    var password = "x"
    private val nonce = "abc123nonce"
    private val realm = "Login to TEST"

    init {
        for (d in 0..6) for (i in 0..5) {
            store["Record[0].TimeSection[$d][$i]"] = if (i == 0) "1 00:00:00-24:00:00" else "0 00:00:00-24:00:00"
        }
        store.putAll(mapOf(
            "Record[0].PreRecord" to "5", "Record[0].Stream" to "0", "RecordMode[0].Mode" to "0",
            "MediaGlobal.PacketLength" to "5", "MediaGlobal.PacketType" to "0", "MediaGlobal.OverWrite" to "true",
            "RecordStoragePoint[0].TimingRecord.Local" to "true", "Locales.TimeFormat" to "yyyy-MM-dd HH:mm:ss",
            "NTP.Enable" to "false", "NTP.Address" to "pool.ntp.org", "NTP.Port" to "123", "NTP.TimeZone" to "1",
            "Encode[0].MainFormat[0].Video.Compression" to "H.265", "ChannelTitle[0].Name" to "IPC",
        ))
        var t = LocalDateTime.of(2026, 11, 22, 8, 0, 0)
        files = List(150) {
            val e = t.plusMinutes(5)
            val f = Triple(t.format(pl.arcom.aitrackereye.model.CAMERA_TIME), e.format(pl.arcom.aitrackereye.model.CAMERA_TIME), 22_000_000L)
            t = e
            f
        }
    }

    private fun md5(s: String) = MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun authorized(r: RecordedRequest): Boolean {
        val h = r.getHeader("Authorization") ?: return false
        if (!h.startsWith("Digest ")) return false
        val p = Regex("""(\w+)=(?:"([^"]*)"|([^,\s]*))""").findAll(h.substring(7))
            .associate { it.groupValues[1] to (it.groups[2]?.value ?: it.groupValues[3]) }
        if (p["nonce"] != nonce || p["uri"] != r.path) return false
        val ha1 = md5("admin:$realm:$password")
        val ha2 = md5("${r.method}:${r.path}")
        return p["response"] == md5("$ha1:$nonce:${p["nc"]}:${p["cnonce"]}:auth:$ha2")
    }

    private fun send(code: Int, body: String) = MockResponse().setResponseCode(code).setBody(body)

    override fun dispatch(request: RecordedRequest): MockResponse {
        if (!authorized(request)) {
            return MockResponse().setResponseCode(401)
                .addHeader("WWW-Authenticate", "Digest realm=\"$realm\", qop=\"auth\", nonce=\"$nonce\", opaque=\"op\"")
        }
        log.add(request)
        val url = request.requestUrl!!
        val path = url.encodedPath
        val q = (url.encodedQuery ?: "").split("&").filter { it.isNotEmpty() }.associate {
            val i = it.indexOf('=')
            URLDecoder.decode(it.substring(0, i), "UTF-8") to URLDecoder.decode(it.substring(i + 1), "UTF-8")
        }
        val action = q["action"]
        return when {
            path == "/cgi-bin/configManager.cgi" && action == "getConfig" -> {
                val name = q.getValue("name")
                val lines = store.filterKeys { it == name || it.startsWith("$name[") || it.startsWith("$name.") }
                    .map { "table.${it.key}=${it.value}" }
                if (lines.isEmpty()) send(200, "Error\nBad Request!") else send(200, lines.joinToString("\n"))
            }
            path == "/cgi-bin/configManager.cgi" && action == "setConfig" -> {
                if (failSet) send(400, "Error\nBad Request!")
                else {
                    q.filterKeys { it != "action" }.forEach { (k, v) -> store[k] = v }
                    send(200, "OK")
                }
            }
            path == "/cgi-bin/global.cgi" && action == "getCurrentTime" -> send(200, "result=$time")
            path == "/cgi-bin/global.cgi" && action == "setCurrentTime" -> { time = q.getValue("time"); send(200, "OK") }
            path == "/cgi-bin/recordManager.cgi" -> send(200, "caps.PacketLengthRange[0]=1\ncaps.PacketLengthRange[1]=120\n")
            path == "/cgi-bin/encode.cgi" -> send(200, "caps[0].MainFormat[0].Video.FPSMax=25\n")
            path == "/cgi-bin/storageDevice.cgi" && action == "getDeviceAllInfo" -> send(200,
                "list.info[0].Name=/dev/mmc0\nlist.info[0].State=Success\nlist.info[0].Detail[0].Path=/dev/mmc0\n" +
                    "list.info[0].Detail[0].Type=ReadWrite\nlist.info[0].Detail[0].TotalBytes=247393682227\n" +
                    "list.info[0].Detail[0].UsedBytes=3446711255\nlist.info[0].Detail[0].IsError=false\n")
            path == "/cgi-bin/storageDevice.cgi" && action == "setDevice" -> send(200, "OK")
            path == "/cgi-bin/mediaFileFind.cgi" -> when (action) {
                "factory.create" -> send(200, "result=12345")
                "findFile" -> { findPos = 0; send(200, "OK") }
                "findNextFile" -> {
                    val n = q["count"]?.toInt() ?: 100
                    val chunk = files.drop(findPos).take(n)
                    findPos += chunk.size
                    val lines = mutableListOf("found=${chunk.size}")
                    chunk.forEachIndexed { i, (a, b, _) ->
                        lines += listOf("items[$i].StartTime=$a", "items[$i].EndTime=$b", "items[$i].Length=$fileSize",
                            "items[$i].FilePath=/mnt/sd/${a.take(10)}/001/dav/${a.substring(11, 13)}/" +
                                "${a.substring(11, 19).replace(':', '.')}[R][0@0][0].dav")
                    }
                    send(200, lines.joinToString("\n"))
                }
                else -> send(200, "OK")
            }
            path.startsWith("/cgi-bin/RPC_Loadfile/") ->
                MockResponse().setResponseCode(200).setBody(Buffer().write(ByteArray(fileSize)))
            path == "/cgi-bin/magicBox.cgi" -> send(200, if (action == "getDeviceType") "type=IPC-TEST" else "version=1.0")
            else -> send(404, "Not found")
        }
    }
}

class CameraApiTest {
    private lateinit var server: MockWebServer
    private lateinit var cam: FakeCamera
    private lateinit var api: CameraApi

    @Before fun setUp() {
        cam = FakeCamera()
        server = MockWebServer()
        server.dispatcher = cam
        server.start()
        api = CameraApi(CameraAddress("127.0.0.1", "admin", "x", httpPort = server.port))
    }

    @After fun tearDown() {
        api.close()
        server.shutdown()
    }

    @Test fun readSnapshot() {
        val s = api.read()
        assertTrue(s.config.keys.containsAll(listOf("Record", "RecordMode", "MediaGlobal", "Encode", "NTP")))
        assertTrue("StorageGlobal" in s.errors)
        assertEquals("2026-11-22 17:52:49", s.currentTime)
        assertEquals("/dev/mmc0", s.storage[0].name)
        assertEquals("IPC-TEST", s.system["type"])
    }

    @Test fun setConfigSingleRequestAndEncoding() {
        api.setConfig(mapOf("Record[0].TimeSection[1][0]" to "1 08:00:00-16:00:00", "MediaGlobal.PacketLength" to "10"))
        val sets = cam.log.filter { it.path!!.contains("action=setConfig") }
        assertEquals(1, sets.size)
        assertTrue(sets[0].path, sets[0].path!!.contains("=1%2008") && !sets[0].path!!.contains("+"))
        assertTrue(sets[0].path, sets[0].path!!.contains("Record[0].TimeSection[1][0]="))
        assertEquals("10", cam.store["MediaGlobal.PacketLength"])
        assertEquals("1 08:00:00-16:00:00", cam.store["Record[0].TimeSection[1][0]"])
    }

    @Test fun setTime() {
        api.setTime(LocalDateTime.of(2026, 1, 2, 3, 4, 5))
        assertEquals("2026-01-02 03:04:05", cam.time)
    }

    @Test fun rejectedSet() {
        cam.failSet = true
        try {
            api.setConfig(mapOf("MediaGlobal.PacketLength" to "10"))
            fail()
        } catch (e: CameraException) {
            assertTrue(e.message!!.contains("odrzuciła"))
        }
    }

    @Test fun wrongPassword() {
        cam.password = "inne"
        try {
            api.getTime()
            fail()
        } catch (e: CameraException) {
            assertEquals("Zły login lub hasło.", e.message)
        }
    }

    @Test fun noConnection() {
        val dead = CameraApi(CameraAddress("127.0.0.1", "admin", "x", httpPort = 1), 1, 1)
        try {
            dead.getTime()
            fail()
        } catch (e: ConnectionLostException) {
        } finally {
            dead.close()
        }
    }

    @Test fun recordingsStatsAndList() {
        val st = api.recordingStats(0)
        assertEquals(150, st.count)
        assertEquals(300L, st.typicalSeconds)
        assertEquals("2026-11-22 08:00:00", st.first)
        assertTrue("2026-11-22" in st.days)
        val list = api.listRecordings(0, "2026-11-22 00:00:00", "2026-11-22 23:59:59", limit = 120)
        assertEquals(120, list.size)
        assertTrue(cam.log.any { it.path!!.contains("action=destroy") })
    }

    @Test fun download() {
        val f = api.listRecordings(0, "2026-11-22 00:00:00", "2026-11-22 23:59:59").first()
        val out = ByteArrayOutputStream()
        var done = 0L
        api.download(f, out, { done += it }, { false })
        assertEquals(cam.fileSize, out.size())
        assertEquals(cam.fileSize.toLong(), done)
        assertTrue(cam.log.last().path!!.contains("[R][0@0][0].dav"))
    }

    @Test fun chunkedImport() {
        api.setConfigChunked(mapOf("NTP.Port" to "124", "MediaGlobal.PacketLength" to "7"), maxKeys = 40)
        assertEquals(2, cam.log.count { it.path!!.contains("action=setConfig") })
        assertFalse(cam.store["NTP.Port"] == "123")
    }
}
