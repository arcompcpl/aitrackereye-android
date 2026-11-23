package pl.arcom.aitrackereye.model

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Logika ustawień kamery niezależna od interfejsu - port model.py z wersji Windows.
 *
 * Każda funkcja build* zwraca mapę {klucz: wartość} zawierającą WYŁĄCZNIE pola,
 * które kamera zwróciła przy odczycie i których wartość faktycznie się zmienia.
 * Błędy danych wpisanych przez użytkownika: [IllegalArgumentException] z gotowym komunikatem.
 */

// ======================================================================
//  Harmonogram nagrywania
// ======================================================================
const val TYPE_REGULAR = 1
const val EMPTY_SLOT = "0 00:00:00-24:00:00"
const val DEFAULT_SLOTS = 6

data class WeekDay(val index: Int, val short: String, val full: String)

/** Kolejność dni jak w panelu WWW kamery; indeks 0 = niedziela. */
val WEEKDAYS = listOf(
    WeekDay(0, "Nd", "Niedziela"), WeekDay(1, "Pn", "Poniedziałek"), WeekDay(2, "Wt", "Wtorek"),
    WeekDay(3, "Śr", "Środa"), WeekDay(4, "Cz", "Czwartek"), WeekDay(5, "Pt", "Piątek"),
    WeekDay(6, "Sb", "Sobota"),
)
val DAY_NAME: Map<Int, String> = WEEKDAYS.associate { it.index to it.full }

data class Segment(val mask: Int, val start: String, val end: String) {
    val regular: Boolean get() = (mask and TYPE_REGULAR) != 0
    val active: Boolean get() = mask != 0 && timeToSeconds(start) < timeToSeconds(end)

    /** Przełącznik w harmonogramie = nagrywanie ogólne (ciągłe). */
    val enabled: Boolean get() = regular

    /** Zmienia tylko bit nagrywania; pozostałe bity maski zostają bez zmian. */
    fun withEnabled(on: Boolean): Segment =
        copy(mask = if (on) mask or TYPE_REGULAR else mask and TYPE_REGULAR.inv())

    /** Pusty slot kamery: maska 0 i cała doba albo zerowa długość. */
    val isEmptySlot: Boolean
        get() {
            if (mask != 0) return false
            val s = timeToSeconds(start)
            val e = timeToSeconds(end)
            return s >= e || (s == 0 && e >= 86399)
        }

    fun text(): String = "$mask $start-$end"
}

fun timeToSeconds(value: String): Int {
    val parts = value.split(":").map { it.trim().toIntOrNull() ?: 0 }.toMutableList()
    while (parts.size < 3) parts.add(0)
    return parts[0] * 3600 + parts[1] * 60 + parts[2]
}

private val TIME_RE = Regex("""\s*(\d{1,2}):(\d{1,2})(?::(\d{1,2}))?\s*""")

/** '8:5' / '08:05' / '24:00' -> 'HH:MM:SS'. IllegalArgumentException dla złych danych. */
fun normalizeTime(text: String?): String {
    val m = TIME_RE.matchEntire(text ?: "")
        ?: throw IllegalArgumentException("Zły format godziny: '${text ?: ""}' (użyj GG:MM).")
    val h = m.groupValues[1].toInt()
    val mi = m.groupValues[2].toInt()
    val s = m.groupValues[3].ifEmpty { "0" }.toInt()
    if (mi > 59 || s > 59 || h > 24 || (h == 24 && (mi != 0 || s != 0))) {
        throw IllegalArgumentException("Zła godzina: $text.")
    }
    return "%02d:%02d:%02d".format(h, mi, s)
}

private val SEGMENT_RE = Regex("""(\d+)\s+(\d\d:\d\d:\d\d)-(\d\d:\d\d:\d\d)""")

fun parseSegment(value: String): Segment? {
    val m = SEGMENT_RE.matchEntire(value.trim()) ?: return null
    return Segment(m.groupValues[1].toInt(), m.groupValues[2], m.groupValues[3])
}

fun dayKeys(config: Map<String, String>, channel: Int, day: Int): List<String> {
    val pattern = Regex("""^Record\[$channel]\.TimeSection\[$day]\[(\d+)]$""")
    return config.keys.mapNotNull { k -> pattern.matchEntire(k)?.let { k to it.groupValues[1].toInt() } }
        .sortedBy { it.second }.map { it.first }
}

fun hasSchedule(config: Map<String, String>, channel: Int): Boolean =
    (0..6).any { dayKeys(config, channel, it).isNotEmpty() }

fun slotsPerDay(config: Map<String, String>, channel: Int): Int {
    val counts = (0..6).map { dayKeys(config, channel, it).size }.filter { it > 0 }
    return counts.minOrNull() ?: DEFAULT_SLOTS
}

fun readWeek(config: Map<String, String>, channel: Int): Map<Int, List<Segment>> =
    (0..6).associateWith { day ->
        dayKeys(config, channel, day).mapNotNull { key ->
            parseSegment(config.getValue(key))
        }.filter { !it.isEmptySlot && timeToSeconds(it.start) < timeToSeconds(it.end) }
    }

fun buildWeekUpdate(
    config: Map<String, String>, channel: Int, week: Map<Int, List<Segment>>,
): Map<String, String> {
    if (!hasSchedule(config, channel)) throw IllegalArgumentException("Kamera nie udostępnia harmonogramu.")
    val changes = linkedMapOf<String, String>()
    for (day in 0..6) {
        val keys = dayKeys(config, channel, day).ifEmpty {
            (0 until slotsPerDay(config, channel)).map { "Record[$channel].TimeSection[$day][$it]" }
        }
        val segs = week[day].orEmpty()
        if (segs.size > keys.size) {
            throw IllegalArgumentException("${DAY_NAME[day]}: maksymalnie ${keys.size} okresów.")
        }
        for (seg in segs) {
            normalizeTime(seg.start)
            normalizeTime(seg.end)
            if (timeToSeconds(seg.start) >= timeToSeconds(seg.end)) {
                throw IllegalArgumentException(
                    "${DAY_NAME[day]}: początek musi być przed końcem (${seg.start.take(5)}-${seg.end.take(5)}).")
            }
        }
        val values = segs.map { it.text() } + List(keys.size - segs.size) { EMPTY_SLOT }
        keys.zip(values).forEach { (key, value) -> if (config[key] != value) changes[key] = value }
    }
    return changes
}

// ======================================================================
//  Wyszukiwanie pól, które różne oprogramowania nazywają inaczej
// ======================================================================
fun findKey(values: Map<String, String>, candidates: List<String>): String? =
    candidates.firstOrNull { it in values }

fun merged(config: Map<String, Map<String, String>>): Map<String, String> {
    val out = linkedMapOf<String, String>()
    config.values.forEach { out.putAll(it) }
    return out
}

fun isBool(value: String?): Boolean = value != null && value.lowercase() in setOf("true", "false")

// ======================================================================
//  Kontrola nagrywania
// ======================================================================
val RECORD_MODES = listOf("0" to "Automatyczne", "1" to "Ręczne (ciągłe)", "2" to "Wyłączone")

data class ChoiceField(val key: String, val options: List<Pair<String, String>>, val current: String)

/** Pole wyboru strumienia nagrywanego na kartę - tylko gdy jednoznacznie rozpoznane. */
fun recordStreamField(all: Map<String, String>, channel: Int, subStreams: Int): ChoiceField? {
    val key = "Record[$channel].Stream"
    val v = all[key]
    if (v != null && v.isNotEmpty() && v.all { it.isDigit() }) {
        val options = mutableListOf("0" to "Strumień główny")
        for (i in 1..maxOf(1, subStreams)) options.add("$i" to "Podstrumień $i")
        if (options.none { it.first == v }) options.add(v to "Strumień $v")
        return ChoiceField(key, options, v)
    }
    val pairs = mapOf("MainStream" to "SubStream", "SubStream" to "MainStream", "Main" to "Sub", "Sub" to "Main")
    for (cand in listOf("Record[$channel].RecordStream", "Record[$channel].StreamType", "MediaGlobal.RecordStream")) {
        val current = all[cand]
        if (current != null && current in pairs) {
            val main = if (current.startsWith("Main")) current else pairs.getValue(current)
            val sub = pairs.getValue(main)
            return ChoiceField(cand, listOf(main to "Strumień główny", sub to "Podstrumień 1"), current)
        }
    }
    return null
}

fun intRange(caps: Map<String, String>, key: String, default: Pair<Int, Int>): Pair<Int, Int> {
    val nums = Regex("""\d+""").findAll(caps[key] ?: "").map { it.value.toInt() }.toList()
    return if (nums.size >= 2) nums.min() to nums.max() else default
}

val OVERWRITE_KEYS = listOf("MediaGlobal.OverWrite", "StorageGlobal.OverWrite", "StorageGlobal.Overwrite")

fun storagePointKey(channel: Int, event: String) = "RecordStoragePoint[$channel].$event.Local"

fun durationText(seconds: Long): String {
    val d = seconds / 86400
    var rem = seconds % 86400
    val h = rem / 3600
    rem %= 3600
    val m = rem / 60
    val s = rem % 60
    return when {
        d > 0 -> "$d d $h h $m min"
        h > 0 -> "$h h $m min"
        m > 0 -> "$m min" + if (s > 0) " $s s" else ""
        else -> "$s s"
    }
}

fun humanSize(n: Long): String {
    var value = n.toDouble()
    for (unit in listOf("B", "KB", "MB", "GB", "TB")) {
        if (value < 1024 || unit == "TB") {
            return if (unit == "B") "${value.toLong()} B" else String.format(Locale.US, "%.2f %s", value, unit)
        }
        value /= 1024
    }
    return "$n B"
}

val ACCESS_LABELS = mapOf("readwrite" to "Odczyt/zapis", "readonly" to "Tylko odczyt",
    "redundant" to "Nadmiarowy", "snapshot" to "Zdjęcia")
val STATE_LABELS = mapOf("success" to "Normalny", "normal" to "Normalny", "error" to "Błąd",
    "nodisk" to "Brak karty", "formatting" to "Formatowanie", "unformatted" to "Niesformatowany")

fun label(mapping: Map<String, String>, value: String?): String =
    mapping[(value ?: "").lowercase()] ?: value?.ifEmpty { "—" } ?: "—"

// ======================================================================
//  Data i czas
// ======================================================================
/** Indeksy strefy czasowej wg dokumentacji CGI (NTP.TimeZone). */
val TIMEZONES: List<Pair<Int, String>> = listOf(
    0 to "UTC+00:00", 1 to "UTC+01:00", 2 to "UTC+02:00", 3 to "UTC+03:00", 4 to "UTC+03:30",
    5 to "UTC+04:00", 6 to "UTC+04:30", 7 to "UTC+05:00", 8 to "UTC+05:30", 9 to "UTC+05:45",
    10 to "UTC+06:00", 11 to "UTC+06:30", 12 to "UTC+07:00", 13 to "UTC+08:00", 14 to "UTC+09:00",
    15 to "UTC+09:30", 16 to "UTC+10:00", 17 to "UTC+11:00", 18 to "UTC+12:00", 19 to "UTC+13:00",
    20 to "UTC-01:00", 21 to "UTC-02:00", 22 to "UTC-03:00", 23 to "UTC-03:30", 24 to "UTC-04:00",
    25 to "UTC-05:00", 26 to "UTC-06:00", 27 to "UTC-07:00", 28 to "UTC-08:00", 29 to "UTC-09:00",
    30 to "UTC-10:00", 31 to "UTC-11:00", 32 to "UTC-12:00",
)
val TZ_LABEL: Map<Int, String> = TIMEZONES.toMap()

/** Czy indeks strefy zgadza się z opisem z kamery (gdy opis zawiera przesunięcie). */
fun timezoneConsistent(index: String, description: String?): Boolean {
    val i = index.toIntOrNull() ?: return false
    if (index.any { !it.isDigit() } || i !in TZ_LABEL) return false
    if (description.isNullOrEmpty()) return true
    val m = Regex("""([+-]\d{1,2}):?(\d\d)""").find(description) ?: return true
    val sign = if (m.groupValues[1].startsWith("-")) "-" else "+"
    val hours = kotlin.math.abs(m.groupValues[1].toInt())
    val offset = "UTC$sign%02d:%s".format(hours, m.groupValues[2])
    return TZ_LABEL[i] == offset
}

val DATE_FORMATS = listOf("yyyy-MM-dd" to "RRRR-MM-DD", "MM-dd-yyyy" to "MM-DD-RRRR", "dd-MM-yyyy" to "DD-MM-RRRR")

data class TimeFormatParts(val date: String, val hour24: Boolean, val timePart: String)

/** 'yyyy-MM-dd HH:mm:ss' -> (yyyy-MM-dd, true, HH:mm:ss). null gdy nieznany format. */
fun splitTimeFormat(fmt: String): TimeFormatParts? {
    val parts = fmt.split(" ", limit = 2)
    if (parts.size != 2 || DATE_FORMATS.none { it.first == parts[0] }) return null
    val t = parts[1]
    return when {
        t.startsWith("HH") -> TimeFormatParts(parts[0], true, t)
        t.startsWith("hh") -> TimeFormatParts(parts[0], false, t)
        else -> null
    }
}

fun joinTimeFormat(dateFmt: String, hour24: Boolean, originalTimePart: String): String =
    if (hour24) "$dateFmt HH:mm:ss"
    else "$dateFmt ${if (originalTimePart.startsWith("hh")) originalTimePart else "hh:mm:ss tt"}"

val CAMERA_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

fun parseCameraTime(text: String?): LocalDateTime? {
    val t = text?.trim().orEmpty()
    if (t.isEmpty()) return null
    for (p in listOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss")) {
        try {
            return LocalDateTime.parse(t, DateTimeFormatter.ofPattern(p))
        } catch (_: Exception) {
        }
    }
    return null
}

data class DSTPoint(
    val month: Int = 1,
    /** 0 = tryb daty; 1..5 / -1 (ostatni) = tryb tygodnia */
    val week: Int = 0,
    /** dzień miesiąca (tryb daty) lub dzień tygodnia 0-6 (tryb tygodnia) */
    val day: Int = 1,
    val hour: Int = 0,
    val minute: Int = 0,
)

val DST_FIELDS = listOf("Month", "Week", "Day", "Hour", "Minute")

fun readDst(locales: Map<String, String>, which: String): DSTPoint? {
    val vals = DST_FIELDS.map { locales["Locales.$which.$it"]?.toIntOrNull() ?: return null }
    return DSTPoint(vals[0], vals[1], vals[2], vals[3], vals[4])
}

fun buildDstUpdate(locales: Map<String, String>, which: String, p: DSTPoint): Map<String, String> {
    val changes = linkedMapOf<String, String>()
    DST_FIELDS.zip(listOf(p.month, p.week, p.day, p.hour, p.minute)).forEach { (name, value) ->
        val key = "Locales.$which.$name"
        if (key in locales && locales[key] != value.toString()) changes[key] = value.toString()
    }
    return changes
}

// ======================================================================
//  Kodowanie obrazu
// ======================================================================
/** stream 0 = główny, 1 = podstrumień 1, 2 = podstrumień 2. */
fun streamPrefix(channel: Int, stream: Int): String =
    if (stream == 0) "Encode[$channel].MainFormat[0]" else "Encode[$channel].ExtraFormat[${stream - 1}]"

fun streamAvailable(config: Map<String, String>, channel: Int, stream: Int): Boolean {
    val prefix = streamPrefix(channel, stream) + ".Video."
    return config.keys.any { it.startsWith(prefix) }
}

fun capsPrefix(prefix: String): String = prefix.replaceFirst("Encode[", "caps[")

typealias Size = Pair<Int, Int>

val NAMED_RESOLUTIONS: Map<String, Size> = mapOf(
    "QCIF" to (176 to 144), "CIF" to (352 to 288), "QVGA" to (320 to 240), "VGA" to (640 to 480),
    "D1" to (704 to 576), "960H" to (960 to 576), "720P" to (1280 to 720), "1_3M" to (1280 to 960),
    "1.3M" to (1280 to 960), "1080P" to (1920 to 1080), "3M" to (2048 to 1536), "5M" to (2592 to 1944),
    "4K" to (3840 to 2160),
)
val RESOLUTION_NAMES: Map<Size, String> = mapOf(
    (704 to 576) to "D1", (352 to 288) to "CIF", (1280 to 720) to "720P", (1920 to 1080) to "1080P",
    (2688 to 1520) to "4MP", (2560 to 1440) to "4MP", (2592 to 1944) to "5MP", (3840 to 2160) to "4K",
)

fun parseResolutions(text: String?): List<Size> {
    val out = mutableListOf<Size>()
    for (raw in (text ?: "").split(',', ';', '|')) {
        val item = raw.trim()
        val m = Regex("""(\d{3,4})\s*[x*X]\s*(\d{3,4})""").matchEntire(item)
        val size = if (m != null) m.groupValues[1].toInt() to m.groupValues[2].toInt()
        else NAMED_RESOLUTIONS[item.uppercase()]
        if (size != null && size !in out) out.add(size)
    }
    return out.sortedByDescending { it.first * it.second }
}

fun resolutionLabel(size: Size): String {
    val name = RESOLUTION_NAMES[size]
    return "${size.first}×${size.second}" + if (name != null) " ($name)" else ""
}

val CONTROL_LABELS = mapOf("CBR" to "Stały (CBR)", "VBR" to "Zmienny (VBR)", "ABR" to "Średni (ABR)")

fun bitrateControls(current: String?): List<Pair<String, String>> {
    val values = mutableListOf("CBR", "VBR")
    if (current != null && current !in values) values.add(current)
    return values.map { it to (CONTROL_LABELS[it] ?: it) }
}

/** H.265 obsługuje profil Main; zwraca nowy profil albo null gdy bez zmian. */
fun profileFix(compression: String, profile: String?): String? {
    if (profile == null) return null
    return if (compression.uppercase().startsWith("H.265") && profile != "Main") "Main" else null
}

fun intOr(value: String?, default: Int): Int = value?.trim()?.toDoubleOrNull()?.toInt() ?: default
fun floatOr(value: String?, default: Double): Double = value?.trim()?.toDoubleOrNull() ?: default

// ======================================================================
//  Nazwa na obrazie, pobieranie, eksport/import
// ======================================================================
const val TITLE_MAX = 32
private val BAD_PATH = Regex("""[<>:"/\\|?*\x00-\x1f]""")
private val RESERVED = setOf("CON", "PRN", "AUX", "NUL") +
    (1..9).map { "COM$it" } + (1..9).map { "LPT$it" }

/** Nazwa na obrazie -> bezpieczna nazwa katalogu (zgodna z Windows). */
fun folderName(title: String?, fallback: String = "Kamera"): String {
    var name = BAD_PATH.replace((title ?: "").trim(), "_").trim(' ', '.')
    if (name.isEmpty() || name.uppercase() in RESERVED) name = fallback
    return name.take(60)
}

/** '2026-11-22 08:00:00', '2026-11-22 08:05:00' -> '2026-11-22_08-00-00_08-05-00.ext'. */
fun recordingFilename(start: String, end: String, ext: String): String {
    val a = start.replace(":", "-").replace(" ", "_")
    val b = if (end.length >= 19) end.substring(11).replace(":", "-") else end.replace(":", "-")
    return "${a}_$b.$ext"
}

const val EXPORT_APP = "AiTrackerEye Konfigurator"

val SECTION_TABS = mapOf(
    "Record" to "Nagrywanie / Harmonogram", "RecordMode" to "Nagrywanie", "MediaGlobal" to "Nagrywanie",
    "Encode" to "Obraz", "ChannelTitle" to "Obraz", "VideoWaterMark" to "Obraz",
    "Locales" to "Data i czas", "NTP" to "Data i czas",
    "RecordStoragePoint" to "Pamięć", "StorageGlobal" to "Pamięć",
)

fun sectionOf(key: String): String = key.split('[', '.', limit = 2)[0]

/**
 * Zmiany do zapisania z pliku eksportu: tylko klucze, które ta kamera ma, i tylko różniące się.
 * [document] = sekcja "config" z pliku (już sparsowana); wartości inne niż tekst są pomijane.
 */
fun importChanges(current: Map<String, Map<String, String>>, document: Map<String, Map<String, Any?>>): Map<String, String> {
    val changes = linkedMapOf<String, String>()
    for ((section, values) in document) {
        if (section !in SECTION_TABS || section !in current) continue
        val cur = current.getValue(section)
        for ((key, value) in values) {
            if (key in cur && value is String && cur[key] != value) changes[key] = value
        }
    }
    return changes
}

fun changedTabs(changes: Map<String, String>): List<String> {
    val tabs = mutableListOf<String>()
    for (key in changes.keys) {
        val tab = SECTION_TABS[sectionOf(key)]
        if (tab != null && tab !in tabs) tabs.add(tab)
    }
    return tabs
}

/** Sekundy między dwoma czasami 'RRRR-MM-DD GG:MM:SS' (0 gdy nie da się odczytać). */
fun secondsBetween(start: String, end: String): Long {
    val a = parseCameraTime(start) ?: return 0
    val b = parseCameraTime(end) ?: return 0
    return maxOf(0L, java.time.Duration.between(a, b).seconds)
}

fun addSeconds(stamp: String, seconds: Long): String {
    val t = parseCameraTime(stamp) ?: return stamp
    return t.plusSeconds(seconds).format(CAMERA_TIME)
}

fun clock(seconds: Long): String {
    val s = maxOf(0L, seconds)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
}
