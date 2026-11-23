package pl.arcom.aitrackereye.camera

import okhttp3.Credentials
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.Closeable
import java.io.IOException
import java.io.OutputStream
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import pl.arcom.aitrackereye.model.CAMERA_TIME

/** Autoryzacja HTTP Digest (z zapamiętanym wyzwaniem), awaryjnie Basic. */
class DigestInterceptor(private val user: String, private val password: String) : Interceptor {
    @Volatile private var challenge: DigestChallenge? = null
    @Volatile private var basic = false
    private val nc = AtomicInteger(0)

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val first = chain.proceed(authorize(request) ?: request)
        if (first.code != 401) return first
        val headers = first.headers("WWW-Authenticate")
        val ch = parseDigestChallenge(headers)
        if (ch != null) {
            first.close()
            challenge = ch
            basic = false
            nc.set(0)
            return chain.proceed(authorize(request)!!)
        }
        if (!basic && headers.any { it.trim().startsWith("Basic", ignoreCase = true) }) {
            first.close()
            basic = true
            return chain.proceed(authorize(request)!!)
        }
        return first
    }

    private fun authorize(request: Request): Request? {
        if (basic) return request.newBuilder().header("Authorization", Credentials.basic(user, password)).build()
        val ch = challenge ?: return null
        val url = request.url
        val uri = url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: "")
        return request.newBuilder()
            .header("Authorization", digestAuthorization(ch, user, password, request.method, uri, nc.incrementAndGet()))
            .build()
    }
}

/** Komunikacja z kamerą przez HTTP CGI - port klasy CameraAPI z wersji Windows. */
class CameraApi(
    val addr: CameraAddress,
    connectTimeoutSec: Long = 4,
    private val readTimeoutSec: Long = 10,
) : Closeable {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(connectTimeoutSec, TimeUnit.SECONDS)
        .readTimeout(readTimeoutSec, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .addInterceptor(DigestInterceptor(addr.user, addr.password))
        .apply { if (addr.https && !addr.verifyTls) trustAll(this) }
        .build()

    override fun close() {
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    // ---------- transport ----------
    private fun get(path: String, params: List<Pair<String, String>> = emptyList(), readTimeout: Long? = null): String {
        val query = cgiQuery(params)
        val url = addr.baseUrl + path + if (query.isNotEmpty()) "?$query" else ""
        val c = if (readTimeout != null) client.newBuilder().readTimeout(readTimeout, TimeUnit.SECONDS).build() else client
        val body: String
        try {
            c.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
                checkStatus(resp.code)
                body = resp.body?.string()?.trim().orEmpty()
            }
        } catch (e: SSLException) {
            throw CameraException("Błąd certyfikatu HTTPS.", e)
        } catch (e: CameraException) {
            throw e
        } catch (e: IOException) {
            throw ConnectionLostException("Brak połączenia z kamerą. Sprawdź IP i port.", e)
        } catch (e: IllegalArgumentException) {
            throw CameraException("Niepoprawny adres kamery.", e)
        }
        if (body.isEmpty()) throw CameraException("Pusta odpowiedź kamery.")
        val low = body.lowercase()
        if (low.startsWith("error") || low.startsWith("failed") || low.startsWith("invalid")) {
            throw CameraException("Kamera odrzuciła polecenie.")
        }
        return body
    }

    private fun checkStatus(code: Int) {
        when {
            code == 401 -> throw CameraException("Zły login lub hasło.")
            code == 403 -> throw CameraException("Brak uprawnień do tej funkcji.")
            code == 404 -> throw CameraException("Funkcja niedostępna w tej kamerze.")
            code >= 400 -> throw CameraException("Kamera odrzuciła polecenie (HTTP $code).")
        }
    }

    private fun expectOk(body: String) {
        if (body.lineSequence().first().trim().uppercase() != "OK") throw CameraException("Kamera nie potwierdziła zapisu.")
    }

    // ---------- konfiguracja ----------
    fun getConfig(section: String): Map<String, String> {
        val values = parseKeyValues(get("/cgi-bin/configManager.cgi", listOf("action" to "getConfig", "name" to section)))
        if (values.keys.none { it == section || it.startsWith("$section[") || it.startsWith("$section.") }) {
            throw CameraException("Brak sekcji $section.")
        }
        return values
    }

    /** Jeden zapis = jedno polecenie (kamera przyjmuje wszystkie pola albo żadne). */
    fun setConfig(values: Map<String, String>) {
        if (values.isEmpty()) return
        expectOk(get("/cgi-bin/configManager.cgi", listOf("action" to "setConfig") + values.toList()))
    }

    /** Duży zapis (import) w porcjach - ogranicza długość adresu. Porcje grupowane wg sekcji. */
    fun setConfigChunked(values: Map<String, String>, maxKeys: Int = 40) {
        var chunk = linkedMapOf<String, String>()
        var section: String? = null
        for ((key, value) in values.toSortedMap()) {
            val sec = key.split('[', '.', limit = 2)[0]
            if (chunk.isNotEmpty() && (chunk.size >= maxKeys || sec != section)) {
                setConfig(chunk)
                chunk = linkedMapOf()
            }
            chunk[key] = value
            section = sec
        }
        if (chunk.isNotEmpty()) setConfig(chunk)
    }

    // ---------- czas ----------
    fun getTime(): String {
        val values = parseKeyValues(get("/cgi-bin/global.cgi", listOf("action" to "getCurrentTime"), readTimeout = 5))
        return values["result"] ?: throw CameraException("Nie udało się odczytać czasu kamery.")
    }

    fun setTime(value: LocalDateTime): String {
        val text = value.format(CAMERA_TIME)
        expectOk(get("/cgi-bin/global.cgi", listOf("action" to "setCurrentTime", "time" to text)))
        return text
    }

    // ---------- karta SD ----------
    fun getStorage(): List<StorageDevice> =
        parseStorage(parseKeyValues(get("/cgi-bin/storageDevice.cgi", listOf("action" to "getDeviceAllInfo"))))

    fun formatStorage(path: String) {
        // Polecenie wg dokumentacji CGI (pisownia "Patition" pochodzi z dokumentacji).
        expectOk(get("/cgi-bin/storageDevice.cgi",
            listOf("action" to "setDevice", "type" to "FormatPatition", "path" to path), readTimeout = 120))
    }

    // ---------- nagrania ----------
    /**
     * Pliki wideo z karty w zadanym przedziale (mediaFileFind). [channel]: numer CGI od 0.
     * [action] zwraca false, żeby przerwać.
     */
    fun forEachRecording(
        channel: Int, start: String = "2000-01-01 00:00:00", end: String = "2037-12-31 23:59:59",
        batch: Int = 100, action: (RecordingFile) -> Boolean,
    ) {
        val path = MEDIA_FIND
        val obj = parseKeyValues(get(path, listOf("action" to "factory.create")))["result"]
            ?: throw CameraException("Wyszukiwanie nagrań niedostępne.")
        try {
            expectOk(get(path, listOf("action" to "findFile", "object" to obj,
                "condition.Channel" to "${channel + 1}", "condition.StartTime" to start,
                "condition.EndTime" to end, "condition.Types[0]" to "dav")))
            while (true) {
                val kv = parseKeyValues(get(path, listOf("action" to "findNextFile", "object" to obj,
                    "count" to "$batch"), readTimeout = 30))
                val found = kv["found"]?.toIntOrNull() ?: 0
                for (i in 0 until found) {
                    val f = RecordingFile(kv["items[$i].StartTime"] ?: "", kv["items[$i].EndTime"] ?: "",
                        kv["items[$i].FilePath"] ?: "", kv["items[$i].Length"]?.toLongOrNull() ?: 0L)
                    if (!action(f)) return
                }
                if (found < batch) break
            }
        } finally {
            for (a in listOf("close", "destroy")) {
                try {
                    get(path, listOf("action" to a, "object" to obj), readTimeout = 5)
                } catch (_: CameraException) {
                }
            }
        }
    }

    fun listRecordings(channel: Int, start: String, end: String, limit: Int = 3000): List<RecordingFile> {
        val files = mutableListOf<RecordingFile>()
        forEachRecording(channel, start, end) { files.add(it); files.size < limit }
        return files.sortedBy { it.start }
    }

    fun recordingStats(channel: Int, limit: Int = 50000): RecordingStats {
        val stats = RecordingStats()
        val durations = mutableListOf<Long>()
        forEachRecording(channel) { f ->
            val s = f.seconds
            durations.add(s)
            stats.totalSeconds += s
            stats.totalBytes += f.size
            if (f.start.isNotEmpty() && (stats.first.isEmpty() || f.start < stats.first)) stats.first = f.start
            if (f.end > stats.last) stats.last = f.end
            for (t in listOf(f.start, f.end)) {
                if (t.length >= 10 && t.take(10) != "0000-00-00") stats.days.add(t.take(10))
            }
            stats.count++
            if (stats.count >= limit) {
                stats.truncated = true
                false
            } else true
        }
        if (durations.isNotEmpty()) stats.typicalSeconds = durations.sorted()[durations.size / 2]
        return stats
    }

    /** Pobiera plik z karty (RPC_Loadfile) do [out]. */
    fun download(file: RecordingFile, out: OutputStream, progress: (Long) -> Unit, cancelled: () -> Boolean) {
        if (file.path.isEmpty()) throw CameraException("Brak ścieżki pliku w kamerze.")
        val url = addr.baseUrl + "/cgi-bin/RPC_Loadfile" + quote(file.path, "/[]@.-_")
        val c = client.newBuilder().readTimeout(60, TimeUnit.SECONDS).build()
        try {
            c.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
                if (resp.code == 401) throw CameraException("Zły login lub hasło.")
                if (resp.code >= 400) throw CameraException("Nie można pobrać pliku (HTTP ${resp.code}).")
                val input = resp.body?.byteStream() ?: throw CameraException("Pusta odpowiedź kamery.")
                val buf = ByteArray(256 * 1024)
                while (true) {
                    if (cancelled()) throw DownloadCancelledException()
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    progress(n.toLong())
                }
            }
        } catch (e: CameraException) {
            throw e
        } catch (e: DownloadCancelledException) {
            throw e
        } catch (e: IOException) {
            throw ConnectionLostException("Przerwano pobieranie – brak połączenia.", e)
        }
    }

    // ---------- informacje ----------
    fun getSystem(): Map<String, String> {
        val info = linkedMapOf<String, String>()
        for (action in listOf("getDeviceType", "getSoftwareVersion")) {
            try {
                info.putAll(parseKeyValues(get("/cgi-bin/magicBox.cgi", listOf("action" to action))))
            } catch (_: CameraException) {
            }
        }
        return info
    }

    fun read(): Snapshot {
        val snap = Snapshot()
        for (section in SECTIONS) {
            try {
                snap.config[section] = getConfig(section)
            } catch (e: ConnectionLostException) {
                throw e
            } catch (e: CameraException) {
                snap.errors[section] = e.message ?: ""
            }
        }
        if (MAIN_SECTIONS.none { it in snap.config }) throw CameraException("Kamera nie udostępnia ustawień przez CGI.")
        try {
            snap.currentTime = getTime()
        } catch (e: CameraException) {
            snap.errors["Czas"] = e.message ?: ""
        }
        try {
            snap.recordCaps = parseKeyValues(get("/cgi-bin/recordManager.cgi", listOf("action" to "getCaps")))
        } catch (_: CameraException) {
        }
        try {
            snap.encodeCaps = parseKeyValues(get("/cgi-bin/encode.cgi", listOf("action" to "getConfigCaps")))
        } catch (_: CameraException) {
        }
        try {
            snap.storage = getStorage()
        } catch (e: CameraException) {
            snap.storageError = e.message
        }
        snap.system = getSystem()
        return snap
    }

    companion object {
        /** Sekcje konfiguracji odczytywane przy połączeniu. Brak sekcji nie jest błędem. */
        val SECTIONS = listOf("Record", "RecordMode", "MediaGlobal", "Encode", "Locales", "NTP",
            "RecordStoragePoint", "StorageGlobal", "VideoWaterMark", "ChannelTitle")
        val MAIN_SECTIONS = listOf("Record", "RecordMode", "MediaGlobal", "Encode")
        const val MEDIA_FIND = "/cgi-bin/mediaFileFind.cgi"

        /** Kamera z certyfikatem własnym - tylko gdy użytkownik wyłączył „Sprawdzaj certyfikat”. */
        private fun trustAll(builder: OkHttpClient.Builder) {
            val tm = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf<TrustManager>(tm), SecureRandom())
            builder.sslSocketFactory(ctx.socketFactory, tm)
            builder.hostnameVerifier { _, _ -> true }
        }
    }
}
