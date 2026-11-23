package pl.arcom.aitrackereye.ui.tabs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import pl.arcom.aitrackereye.MainViewModel
import pl.arcom.aitrackereye.camera.Snapshot
import pl.arcom.aitrackereye.model.*
import pl.arcom.aitrackereye.ui.*
import java.time.Duration
import java.time.LocalDateTime

private val PL_DAYS = listOf("poniedziałek", "wtorek", "środa", "czwartek", "piątek", "sobota", "niedziela")
private val MONTHS = listOf("styczeń", "luty", "marzec", "kwiecień", "maj", "czerwiec", "lipiec", "sierpień",
    "wrzesień", "październik", "listopad", "grudzień")
private val WEEKS = listOf(1 to "1. tydzień", 2 to "2. tydzień", 3 to "3. tydzień", 4 to "4. tydzień", -1 to "Ostatni")
private val DST_DAYS = listOf(0 to "niedziela", 1 to "poniedziałek", 2 to "wtorek", 3 to "środa", 4 to "czwartek",
    5 to "piątek", 6 to "sobota")

/** Edytowalny punkt czasu letniego (pola dla trybu daty i trybu tygodnia jednocześnie, jak w wersji Windows). */
private class DstEdit(p: DSTPoint?) {
    var month by mutableIntStateOf(p?.month ?: 1)
    var day by mutableIntStateOf(if (p != null && p.week == 0) maxOf(1, p.day) else 1)
    var week by mutableIntStateOf(if (p != null && p.week != 0) (if (p.week in 1..4) p.week else -1) else 1)
    var weekday by mutableIntStateOf(if (p != null && p.week != 0) p.day else 0)
    var hour by mutableIntStateOf(p?.hour ?: 0)
    var minute by mutableIntStateOf(p?.minute ?: 0)

    fun point(weekMode: Boolean) =
        if (weekMode) DSTPoint(month, week, weekday, hour, minute) else DSTPoint(month, 0, day, hour, minute)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DstEditor(e: DstEdit, weekMode: Boolean, enabled: Boolean) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Dropdown(MONTHS.mapIndexed { i, m -> (i + 1) to m }, e.month, { e.month = it }, Modifier.width(170.dp), enabled)
        if (weekMode) {
            Dropdown(WEEKS, e.week, { e.week = it }, Modifier.width(150.dp), enabled)
            Dropdown(DST_DAYS, e.weekday, { e.weekday = it }, Modifier.width(170.dp), enabled)
        } else {
            Dropdown((1..31).map { it to "dzień $it" }, e.day, { e.day = it }, Modifier.width(130.dp), enabled)
        }
        Dropdown((0..23).map { it to "%02d".format(it) }, e.hour, { e.hour = it }, Modifier.width(100.dp), enabled)
        Dropdown((0..59).map { it to "%02d".format(it) }, e.minute, { e.minute = it }, Modifier.width(100.dp), enabled)
    }
}

/** Zakładka: Data i czas. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TimeTab(vm: MainViewModel, snap: Snapshot?, enabled: Boolean) {
    val s = snap ?: Snapshot()
    val ntp = s.section("NTP")
    val loc = s.section("Locales")

    // --- zegar kamery ---
    var now by remember { mutableStateOf<LocalDateTime?>(null) }
    LaunchedEffect(vm.clockTick) {
        while (true) {
            now = vm.cameraNow()
            delay(1000)
        }
    }

    val ntpOk = remember(snap) { isBool(ntp["NTP.Enable"]) }
    val ntpInit = ntpOk && ntp["NTP.Enable"]!!.lowercase() == "true"
    var ntpOn by remember(snap) { mutableStateOf(ntpInit) }
    val serverInit = ntp["NTP.Address"] ?: ""
    val portInit = intOr(ntp["NTP.Port"], 123)
    val periodInit = intOr(ntp["NTP.UpdatePeriod"], 10)
    var server by remember(snap) { mutableStateOf(serverInit) }
    var port by remember(snap) { mutableIntStateOf(portInit) }
    var period by remember(snap) { mutableIntStateOf(periodInit) }

    val systemInit = remember(snap) { (vm.cameraNow() ?: LocalDateTime.now()).withNano(0).format(CAMERA_TIME) }
    var system by remember(snap) { mutableStateOf(systemInit) }

    val fmt = remember(snap) { splitTimeFormat(loc["Locales.TimeFormat"] ?: "") }
    var dateFmt by remember(snap) { mutableStateOf(fmt?.date ?: DATE_FORMATS[0].first) }
    var hour24 by remember(snap) { mutableStateOf(fmt?.hour24 ?: true) }

    val tzInit = ntp["NTP.TimeZone"] ?: ""
    val tzOk = remember(snap) { timezoneConsistent(tzInit, ntp["NTP.TimeZoneDesc"]) }
    var tz by remember(snap) { mutableStateOf(tzInit) }

    val dstOk = remember(snap) { isBool(loc["Locales.DSTEnable"]) }
    val dstInit = dstOk && loc["Locales.DSTEnable"]!!.lowercase() == "true"
    var dst by remember(snap) { mutableStateOf(dstInit) }
    val startInit = remember(snap) { readDst(loc, "DSTStart") }
    val endInit = remember(snap) { readDst(loc, "DSTEnd") }
    val pointsOk = startInit != null && endInit != null
    val weekModeInit = startInit != null && startInit.week != 0
    var weekMode by remember(snap) { mutableStateOf(weekModeInit) }
    val dstStart = remember(snap) { DstEdit(startInit) }
    val dstEnd = remember(snap) { DstEdit(endInit) }
    // punkty jak przy odczycie - do porównania (zmiana trybu zmienia punkt)
    val startLoaded = remember(snap) { DstEdit(startInit).point(weekModeInit) }
    val endLoaded = remember(snap) { DstEdit(endInit).point(weekModeInit) }

    fun collect(): Map<String, String> {
        val changes = linkedMapOf<String, String>()
        fun put(section: Map<String, String>, key: String, value: String) {
            if (key in section && section[key] != value) changes[key] = value
        }
        if (ntpOn != ntpInit) put(ntp, "NTP.Enable", ntpOn.toString())
        if (ntpOn) {
            if (server.trim() != serverInit) {
                if (server.isBlank()) throw IllegalArgumentException("Wpisz adres serwera NTP.")
                put(ntp, "NTP.Address", server.trim())
            }
            if (port != portInit) put(ntp, "NTP.Port", port.toString())
            if (period != periodInit) put(ntp, "NTP.UpdatePeriod", period.toString())
        }
        if (fmt != null && (dateFmt != fmt.date || hour24 != fmt.hour24)) {
            put(loc, "Locales.TimeFormat", joinTimeFormat(dateFmt, hour24, fmt.timePart))
        }
        if (tzOk && tz != tzInit) put(ntp, "NTP.TimeZone", tz)
        if (dstOk && dst != dstInit) put(loc, "Locales.DSTEnable", dst.toString())
        if (dstOk && pointsOk && dst) {
            val sp = dstStart.point(weekMode)
            val ep = dstEnd.point(weekMode)
            if (sp != startLoaded) changes.putAll(buildDstUpdate(loc, "DSTStart", sp))
            if (ep != endLoaded) changes.putAll(buildDstUpdate(loc, "DSTEnd", ep))
        }
        return changes
    }

    fun manualTime(): LocalDateTime? {
        if (system == systemInit || ntpOn) return null
        return parseCameraTime(system) ?: throw IllegalArgumentException("Zły format czasu (RRRR-MM-DD GG:MM:SS).")
    }

    TabPage(enabled, onApply = {
        try {
            vm.applyChanges("Data i czas", collect(), setTime = manualTime())
        } catch (e: IllegalArgumentException) {
            vm.showError("Data i czas", e.message ?: "Błąd")
        }
    }, onRefresh = vm::reload) {
        Section("Czas kamery") {
            val n = now
            if (n == null) BigValue("—") else {
                BigValue("${n.toLocalDate()}  ${PL_DAYS[n.dayOfWeek.value - 1]}")
                BigValue(n.toLocalTime().withNano(0).toString().let { if (it.length == 5) "$it:00" else it })
                val diff = Duration.between(LocalDateTime.now(), n).seconds
                Hint(if (kotlin.math.abs(diff) <= 2) "Zgodny z telefonem"
                    else "Różnica względem telefonu: ${if (diff > 0) "+" else "−"}${kotlin.math.abs(diff)} s")
            }
        }

        Section("Czas i strefa czasowa") {
            FieldRow("Czas", enabled = ntpOk) {
                RadioGroup(listOf(false to "Ustawienia ręczne", true to "NTP"), ntpOn, { ntpOn = it }, enabled = ntpOk)
            }
            FieldRow("Czas systemowy", enabled = !ntpOn, disabledNote = "Czas ustawia serwer NTP") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(system, { system = it.take(19) }, singleLine = true, enabled = !ntpOn,
                        isError = parseCameraTime(system) == null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                        modifier = Modifier.width(220.dp))
                    OutlinedButton(onClick = vm::syncTime, modifier = Modifier.align(Alignment.CenterVertically)) {
                        Text("Synchronizuj z telefonem")
                    }
                }
            }
            val ntpFields = ntpOn && "NTP.Address" in ntp
            FieldRow("Serwer NTP", enabled = ntpFields, disabledNote = if ("NTP.Address" in ntp) "" else UNAVAILABLE) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(server, { server = it }, singleLine = true, enabled = ntpFields,
                        modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth())
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column { Hint("Port"); NumberField(port, { port = it }, 1..65535, enabled = ntpFields) }
                        Column { Hint("Co"); NumberField(period, { period = it }, 1..65535, "min", enabled = ntpFields) }
                    }
                }
            }
            FieldRow("Format czasu", enabled = fmt != null) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Dropdown(DATE_FORMATS, dateFmt, { dateFmt = it }, Modifier.width(190.dp), fmt != null)
                    Dropdown(listOf(true to "24-godz.", false to "12-godz."), hour24, { hour24 = it }, Modifier.width(160.dp), fmt != null)
                }
            }
            FieldRow("Strefa czasowa", enabled = tzOk, note = ntp["NTP.TimeZoneDesc"]) {
                Dropdown(TIMEZONES.map { it.first.toString() to it.second }, tz, { tz = it }, enabled = tzOk)
            }
        }

        Section("Czas letni") {
            FieldRow("Czas letni", enabled = dstOk) {
                SwitchRow(dst, { dst = it }, enabled = dstOk, onText = "Włączony", offText = "Wyłączony")
            }
            val on = dstOk && dst && pointsOk
            FieldRow("Typ", enabled = on, disabledNote = if (dstOk && pointsOk) "" else UNAVAILABLE) {
                RadioGroup(listOf(false to "Data", true to "Tydzień"), weekMode, { weekMode = it }, enabled = on)
            }
            FieldRow("Początek", enabled = on, disabledNote = if (dstOk && pointsOk) "" else UNAVAILABLE) { DstEditor(dstStart, weekMode, on) }
            FieldRow("Koniec", enabled = on, disabledNote = if (dstOk && pointsOk) "" else UNAVAILABLE) { DstEditor(dstEnd, weekMode, on) }
        }
    }
}
