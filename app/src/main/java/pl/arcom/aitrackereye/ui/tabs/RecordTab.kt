package pl.arcom.aitrackereye.ui.tabs

import androidx.compose.runtime.*
import pl.arcom.aitrackereye.MainViewModel
import pl.arcom.aitrackereye.camera.Snapshot
import pl.arcom.aitrackereye.model.*
import pl.arcom.aitrackereye.ui.*

/** Zakładka: Kontrola nagrywania. */
@Composable
fun RecordTab(vm: MainViewModel, snap: Snapshot?, enabled: Boolean) {
    val s = snap ?: Snapshot()
    val ch = vm.ch
    val init = remember(snap) {
        val media = s.section("MediaGlobal")
        val record = s.section("Record")
        val caps = s.recordCaps
        val lenKey = if ("MediaGlobal.PacketLength" in media) "MediaGlobal.PacketLength" else null
        val (lLo, lHi) = intRange(caps, "caps.PacketLengthRange", 1 to 120)
        val len = intOr(media["MediaGlobal.PacketLength"], lLo)
        val preKey = "Record[$ch].PreRecord".takeIf { it in record }
        val (pLo, pHi) = intRange(caps, "caps.PreRecordRange", 0 to 5)
        val pre = intOr(record["Record[$ch].PreRecord"], 0)
        val mode = s.section("RecordMode")["RecordMode[$ch].Mode"]?.takeIf { m -> RECORD_MODES.any { it.first == m } }
        val subCount = (1..2).count { streamAvailable(s.section("Encode"), ch, it) }
        val stream = recordStreamField(merged(s.config), ch, subCount)
        RecordInit(lenKey, minOf(lLo, len)..maxOf(lHi, len), len, "Zakres $lLo-$lHi min",
            preKey, minOf(pLo, pre)..maxOf(pHi, pre), pre, "Zakres $pLo-$pHi s", mode, stream)
    }
    var length by remember(snap) { mutableIntStateOf(init.len) }
    var pre by remember(snap) { mutableIntStateOf(init.pre) }
    var mode by remember(snap) { mutableStateOf(init.mode) }
    var stream by remember(snap) { mutableStateOf(init.stream?.current) }

    fun collect(): Map<String, String> {
        val changes = linkedMapOf<String, String>()
        if (init.lenKey != null && length != init.len) {
            changes[init.lenKey] = length.toString()
            if ((s.section("MediaGlobal")["MediaGlobal.PacketType"] ?: "0") != "0") {
                changes["MediaGlobal.PacketType"] = "0"   // podział plików według czasu
            }
        }
        if (init.preKey != null && pre != init.pre) changes[init.preKey] = pre.toString()
        if (mode != null && mode != init.mode) changes["RecordMode[$ch].Mode"] = mode!!
        if (init.stream != null && stream != null && stream != init.stream.current) changes[init.stream.key] = stream!!
        return changes
    }

    TabPage(enabled, onApply = { vm.applyChanges("Nagrywanie", collect()) }, onRefresh = vm::reload) {
        Section("Kontrola nagrywania") {
            FieldRow("Maks. długość pliku", enabled = init.lenKey != null, note = init.lenNote) {
                NumberField(length, { length = it }, init.lenRange, "min", enabled = init.lenKey != null)
            }
            FieldRow("Nagraj wstępnie", enabled = init.preKey != null, note = init.preNote) {
                NumberField(pre, { pre = it }, init.preRange, "s", enabled = init.preKey != null)
            }
            FieldRow("Tryb nagrywania", enabled = init.mode != null) {
                RadioGroup(RECORD_MODES, mode, { mode = it }, enabled = init.mode != null)
            }
            FieldRow("Strumień nagrań", enabled = init.stream != null) {
                Dropdown(init.stream?.options ?: listOf("" to UNAVAILABLE), stream ?: "", { stream = it },
                    enabled = init.stream != null)
            }
        }
        Hint("Tryb „Automatyczne” nagrywa według zakładki Harmonogram.")
    }
}

private data class RecordInit(
    val lenKey: String?, val lenRange: IntRange, val len: Int, val lenNote: String,
    val preKey: String?, val preRange: IntRange, val pre: Int, val preNote: String,
    val mode: String?, val stream: ChoiceField?,
)
