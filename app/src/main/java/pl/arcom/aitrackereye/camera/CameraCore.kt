package pl.arcom.aitrackereye.camera

import pl.arcom.aitrackereye.model.secondsBetween
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Typy i parsowanie odpowiedzi kamery (bez zależności od Androida ani OkHttp) - port camera_api.py.
 * Zasada: zapisujemy wyłącznie klucze, które kamera zwróciła przy odczycie.
 */

/** Błąd komunikacji lub odmowa kamery. Treść jest gotowa do pokazania użytkownikowi. */
open class CameraException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Brak połączenia sieciowego z kamerą. */
class ConnectionLostException(message: String, cause: Throwable? = null) : CameraException(message, cause)

class DownloadCancelledException : Exception("Pobieranie anulowane.")

data class CameraAddress(
    val host: String,
    val user: String,
    val password: String,
    val httpPort: Int = 80,
    val rtspPort: Int = 554,
    val https: Boolean = false,
    val verifyTls: Boolean = true,
    /** w RTSP kanały liczymy od 1, w CGI od 0 */
    val channel: Int = 1,
) {
    init {
        require(host.isNotEmpty() && listOf("/", "@", "?", "#", "\\", " ").none { it in host }) {
            "Wpisz sam adres IP kamery."
        }
        require(!(host.count { it == ':' } == 1 && !host.startsWith("["))) { "Port wpisz w osobnym polu." }
        require(httpPort in 1..65535 && rtspPort in 1..65535) { "Niepoprawny port." }
        require(channel in 1..256) { "Niepoprawny numer kanału." }
    }

    val cgiChannel: Int get() = channel - 1

    val hostUri: String get() = if (':' in host && !host.startsWith("[")) "[$host]" else host

    val baseUrl: String get() = "${if (https) "https" else "http"}://$hostUri:$httpPort"

    private fun rtspBase(): String {
        val auth = if (user.isNotEmpty()) "${quote(user, "")}:${quote(password, "")}@" else ""
        return "rtsp://$auth$hostUri:$rtspPort"
    }

    fun rtspUrl(subtype: Int): String = "${rtspBase()}/cam/realmonitor?channel=$channel&subtype=$subtype"

    /** Odtwarzanie z karty; czasy 'RRRR-MM-DD GG:MM:SS'. */
    fun playbackUrl(start: String, end: String): String {
        fun fmt(t: String) = t.replace('-', '_').replace(' ', '_').replace(':', '_')
        return "${rtspBase()}/cam/playback?channel=$channel&starttime=${fmt(start)}&endtime=${fmt(end)}"
    }

    override fun toString(): String = "CameraAddress($host:$httpPort, kanał $channel)"   // bez hasła
}

data class StorageDevice(
    val name: String,
    val path: String,
    val state: String,
    /** ReadWrite / ReadOnly / ... */
    val access: String,
    val total: Long,
    val used: Long,
    val error: Boolean,
    val fs: String = "",
)

data class RecordingFile(val start: String, val end: String, val path: String, val size: Long) {
    val seconds: Long get() = secondsBetween(start, end)
}

data class RecordingStats(
    var count: Int = 0,
    var totalSeconds: Long = 0,
    var totalBytes: Long = 0,
    /** mediana długości pliku */
    var typicalSeconds: Long = 0,
    var first: String = "",
    var last: String = "",
    /** przerwano po limicie plików */
    var truncated: Boolean = false,
    /** dni z nagraniami 'RRRR-MM-DD' */
    val days: MutableSet<String> = mutableSetOf(),
)

data class Snapshot(
    val config: MutableMap<String, Map<String, String>> = linkedMapOf(),
    val errors: MutableMap<String, String> = linkedMapOf(),
    var currentTime: String? = null,
    var recordCaps: Map<String, String> = emptyMap(),
    var encodeCaps: Map<String, String> = emptyMap(),
    var storage: List<StorageDevice> = emptyList(),
    var storageError: String? = null,
    var system: Map<String, String> = emptyMap(),
) {
    fun section(name: String): Map<String, String> = config[name] ?: emptyMap()

    /** Wartość klucza z dowolnej odczytanej sekcji. */
    fun value(key: String): String? = config.values.firstNotNullOfOrNull { it[key] }
}

fun parseKeyValues(text: String): Map<String, String> {
    val result = linkedMapOf<String, String>()
    for (line in text.lines()) {
        val i = line.indexOf('=')
        if (i < 0) continue
        var name = line.substring(0, i).trim()
        if (name.startsWith("table.")) name = name.substring(6)
        if (name.isNotEmpty()) result[name] = line.substring(i + 1).trim()
    }
    return result
}

private val INFO_RE = Regex("""list\.info\[(\d+)]\.(.+)$""")
private val DETAIL_RE = Regex("""Detail\[(\d+)]\..*""")

/** Parsuje odpowiedź storageDevice.cgi?action=getDeviceAllInfo. */
fun parseStorage(values: Map<String, String>): List<StorageDevice> {
    val devices = sortedMapOf<Int, MutableMap<String, String>>()
    for ((key, value) in values) {
        val m = INFO_RE.matchEntire(key) ?: continue
        devices.getOrPut(m.groupValues[1].toInt()) { linkedMapOf() }[m.groupValues[2]] = value
    }
    val result = mutableListOf<StorageDevice>()
    for ((idx, dev) in devices) {
        val details: List<Int?> = dev.keys.mapNotNull { DETAIL_RE.matchEntire(it)?.groupValues?.get(1)?.toInt() }
            .toSortedSet().toList().ifEmpty { listOf(null) }
        for (d in details) {
            fun get(name: String, default: String = ""): String =
                if (d == null) dev[name] ?: default else dev["Detail[$d].$name"] ?: default
            fun num(name: String): Long = get(name, "0").toDoubleOrNull()?.toLong() ?: 0L
            result.add(StorageDevice(
                name = dev["Name"] ?: "Dysk ${idx + 1}",
                path = get("Path").ifEmpty { dev["Name"] ?: "" },
                state = dev["State"] ?: "",
                access = get("Type"),
                total = num("TotalBytes"),
                used = num("UsedBytes"),
                error = get("IsError", "false").lowercase() == "true",
                fs = get("FileSystem").ifEmpty { get("FsType") },
            ))
        }
    }
    return result
}

/**
 * Kodowanie jak urllib.parse.quote w Pythonie: litery, cyfry i "_.-~" bez zmian,
 * dodatkowo znaki z [safe]; reszta jako %XX (UTF-8). Spacja -> %20 (nie '+').
 */
fun quote(text: String, safe: String): String {
    val sb = StringBuilder()
    for (b in text.toByteArray(Charsets.UTF_8)) {
        val c = (b.toInt() and 0xff).toChar()
        if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c in "_.-~" || (c.code < 128 && c in safe)) {
            sb.append(c)
        } else {
            sb.append('%').append("%02X".format(b.toInt() and 0xff))
        }
    }
    return sb.toString()
}

/** Parametry CGI: spacje jako %20, nawiasy [] i kropki bez kodowania - jak w dokumentacji CGI. */
fun cgiQuery(params: List<Pair<String, String>>): String =
    params.joinToString("&") { (k, v) -> quote(k, "[].") + "=" + quote(v, "[].") }

// ======================================================================
//  HTTP Digest (RFC 2617/7616, MD5, qop=auth) - kamery BCS/Dahua
// ======================================================================
data class DigestChallenge(
    val realm: String,
    val nonce: String,
    val qop: String?,
    val opaque: String?,
    val algorithm: String?,
)

private val PARAM_RE = Regex("""(\w+)\s*=\s*(?:"((?:[^"\\]|\\.)*)"|([^,\s]*))""")

/** Zwraca wyzwanie Digest z nagłówków WWW-Authenticate (null gdy brak). */
fun parseDigestChallenge(headers: List<String>): DigestChallenge? {
    for (h in headers) {
        val t = h.trim()
        if (!t.startsWith("Digest", ignoreCase = true)) continue
        val params = PARAM_RE.findAll(t.substring(6)).associate { m ->
            m.groupValues[1].lowercase() to (m.groups[2]?.value ?: m.groupValues[3])
        }
        val nonce = params["nonce"] ?: continue
        return DigestChallenge(params["realm"] ?: "", nonce, params["qop"], params["opaque"], params["algorithm"])
    }
    return null
}

private fun md5(s: String): String =
    MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

private val random = SecureRandom()

fun newCnonce(): String = ByteArray(8).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }

/** Nagłówek Authorization dla żądania [method] na [uri] (ścieżka z zapytaniem, dokładnie jak w żądaniu). */
fun digestAuthorization(
    ch: DigestChallenge, user: String, password: String, method: String, uri: String,
    nc: Int, cnonce: String = newCnonce(),
): String {
    val sess = ch.algorithm?.equals("MD5-sess", ignoreCase = true) == true
    var ha1 = md5("$user:${ch.realm}:$password")
    if (sess) ha1 = md5("$ha1:${ch.nonce}:$cnonce")
    val ha2 = md5("$method:$uri")
    val qopAuth = ch.qop?.split(',')?.map { it.trim().lowercase() }?.contains("auth") == true
    val ncText = "%08x".format(nc)
    val response = if (qopAuth) md5("$ha1:${ch.nonce}:$ncText:$cnonce:auth:$ha2") else md5("$ha1:${ch.nonce}:$ha2")
    // Format jak w requests.HTTPDigestAuth (wersja Windows, sprawdzony z kamerami BCS/Dahua).
    val sb = StringBuilder("Digest ")
    sb.append("username=\"").append(user.replace("\"", "\\\"")).append("\", ")
    sb.append("realm=\"").append(ch.realm).append("\", ")
    sb.append("nonce=\"").append(ch.nonce).append("\", ")
    sb.append("uri=\"").append(uri).append("\", ")
    sb.append("response=\"").append(response).append("\"")
    if (ch.opaque != null) sb.append(", opaque=\"").append(ch.opaque).append("\"")
    if (ch.algorithm != null) sb.append(", algorithm=\"").append(ch.algorithm).append("\"")
    if (qopAuth) sb.append(", qop=\"auth\", nc=").append(ncText).append(", cnonce=\"").append(cnonce).append("\"")
    return sb.toString()
}

// ======================================================================
//  Diagnostyka (bez haseł)
// ======================================================================
private val SECRET_WORDS = listOf("password", "passwd", "secret", "token", "privatekey", "serial")

fun redactedDiagnostic(snapshot: Snapshot, host: String, channel: Int, version: String = ""): String {
    val lines = mutableListOf(
        "AiTrackerEye Konfigurator $version (Android) - diagnostyka (bez haseł)",
        "Host: $host", "Kanał: $channel", "Czas kamery: ${snapshot.currentTime ?: "brak"}",
    )
    snapshot.system.toSortedMap().forEach { (k, v) -> lines.add("$k=$v") }
    lines.add("")
    val blocks = snapshot.config.entries.map { it.key to it.value } +
        listOf("caps:record" to snapshot.recordCaps, "caps:encode" to snapshot.encodeCaps)
    for ((section, cfg) in blocks) {
        if (cfg.isEmpty()) continue
        lines.add("[$section]")
        for ((key, value) in cfg.toSortedMap()) {
            val v = if (SECRET_WORDS.any { it in key.lowercase() }) "[UKRYTO]" else value
            lines.add("$key=$v")
        }
        lines.add("")
    }
    lines.add("[Karta SD]")
    snapshot.storageError?.let { lines.add("Błąd: $it") }
    for (d in snapshot.storage) {
        lines.add("${d.name} path=${d.path} state=${d.state} type=${d.access} " +
            "used=${d.used} total=${d.total} error=${d.error} fs=${d.fs}")
    }
    lines.add("")
    snapshot.errors.forEach { (name, v) -> lines.add("Brak: $name ($v)") }
    return lines.joinToString("\n")
}
