package pl.arcom.aitrackereye.ui.tabs

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.arcom.aitrackereye.MainViewModel
import pl.arcom.aitrackereye.camera.Snapshot
import pl.arcom.aitrackereye.model.*
import pl.arcom.aitrackereye.ui.*

/** Okres w edytorze: tekst godzin wpisywany przez użytkownika + oryginał z kamery. */
data class EditSeg(
    val mask: Int, val origStart: String, val origEnd: String,
    val start: String, val end: String, val enabled: Boolean,
) {
    /** Zwraca okres do zapisu; IllegalArgumentException przy złej godzinie. */
    fun toSegment(): Segment {
        val s = if (start == display(origStart)) origStart else normalizeTime(start)
        val e = if (end == display(origEnd)) origEnd else normalizeTime(end)
        return Segment(mask, s, e).withEnabled(enabled)
    }

    fun toSegmentOrNull(): Segment? = try { toSegment() } catch (_: IllegalArgumentException) { null }

    companion object {
        fun display(t: String) = if (t.length >= 8 && t.endsWith(":00")) t.take(5) else t
        fun of(seg: Segment) = EditSeg(seg.mask, seg.start, seg.end, display(seg.start), display(seg.end), seg.enabled)
    }
}

/** Zakładka: Harmonogram nagrywania + informacja o nagraniach na karcie. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScheduleTab(vm: MainViewModel, snap: Snapshot?, enabled: Boolean) {
    val s = snap ?: Snapshot()
    val ch = vm.ch
    val record = s.section("Record")
    val ok = remember(snap) { hasSchedule(record, ch) }
    val maxSlots = remember(snap) { slotsPerDay(record, ch) }
    val week: SnapshotStateMap<Int, List<EditSeg>> = remember(snap) {
        mutableStateMapOf<Int, List<EditSeg>>().apply {
            val w = if (ok) readWeek(record, ch) else emptyMap()
            for (d in 0..6) put(d, w[d].orEmpty().map(EditSeg::of))
        }
    }
    var day by remember { mutableIntStateOf(1) }
    var copyOpen by remember { mutableStateOf(false) }
    val mode = s.section("RecordMode")["RecordMode[$ch].Mode"]

    fun collect(): Map<String, String> {
        val w = (0..6).associateWith { d ->
            week[d].orEmpty().map {
                try { it.toSegment() } catch (e: IllegalArgumentException) {
                    throw IllegalArgumentException("${DAY_NAME[d]}: popraw godziny (format GG:MM).")
                }
            }
        }
        return buildWeekUpdate(record, ch, w)
    }

    TabPage(
        enabled,
        onApply = {
            try {
                vm.applyChanges("Harmonogram", collect())
            } catch (e: IllegalArgumentException) {
                vm.showError("Harmonogram", e.message ?: "Błąd")
            }
        }.takeIf { ok },
        onRefresh = vm::reload,
    ) {
        RecordingsSection(vm, s)

        Section("Tydzień") {
            if (!ok && snap != null) Hint(UNAVAILABLE)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).background(Brand.RecRegular))
                Text("  Nagrywanie     ", fontSize = 13.sp)
                Box(Modifier.size(12.dp).background(Brand.RecOff))
                Text("  Okres wyłączony", fontSize = 13.sp)
            }
            WeekTimeline(week, day) { day = it }
            Hint("Dotknij dzień, aby go edytować.")
        }

        val rows = week[day].orEmpty()
        Section("Edycja: ${DAY_NAME[day]}   (${rows.size}/$maxSlots okresów)") {
            rows.forEachIndexed { i, seg ->
                SegmentRow(seg,
                    onChange = { new -> week[day] = rows.toMutableList().also { it[i] = new } },
                    onDelete = { week[day] = rows.toMutableList().also { it.removeAt(i) } })
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(enabled = rows.size < maxSlots && ok, onClick = {
                    val lastEnd = rows.lastOrNull()?.toSegmentOrNull()?.end ?: "00:00:00"
                    val start = if (lastEnd != "24:00:00") lastEnd else "00:00:00"
                    week[day] = rows + EditSeg.of(Segment(TYPE_REGULAR, start, "24:00:00"))
                }) { Text("+ Dodaj okres") }
                OutlinedButton(enabled = ok, onClick = {
                    week[day] = listOf(EditSeg.of(Segment(TYPE_REGULAR, "00:00:00", "24:00:00")))
                }) { Text("Cała doba") }
                OutlinedButton(enabled = ok, onClick = { week[day] = emptyList() }) { Text("Wyczyść dzień") }
                OutlinedButton(enabled = ok, onClick = {
                    if (rows.all { it.toSegmentOrNull() != null }) copyOpen = true
                    else vm.showError("Harmonogram", "Popraw godziny (format GG:MM).")
                }) { Text("Kopiuj do…") }
            }
        }
        if (mode != null && mode != "0") {
            Hint("Uwaga: tryb nagrywania nie jest „Automatyczne” – plan nie jest teraz używany.")
        }
    }

    if (copyOpen) {
        CopyDayDialog(day, onDismiss = { copyOpen = false }) { targets ->
            val src = week[day].orEmpty()
            targets.forEach { week[it] = src }
            copyOpen = false
        }
    }
}

@Composable
private fun RecordingsSection(vm: MainViewModel, s: Snapshot) {
    val packet = intOr(s.section("MediaGlobal")["MediaGlobal.PacketLength"], 0)
    val setting = if (packet > 0) "$packet min (ustawione)" else "—"
    val st = vm.recStats
    Section("Nagrania na karcie") {
        FieldRow("Liczba plików") {
            BigValue(when {
                vm.recBusy -> "liczę…"
                st == null -> "—"
                else -> "${st.count}${if (st.truncated) "+" else ""}"
            })
        }
        FieldRow("Długość pliku (interwał)") {
            Text(setting + if (!vm.recBusy && st != null && st.count > 0) " · zwykle ${durationText(st.typicalSeconds)}" else "")
        }
        FieldRow("Łączny czas nagrań") {
            Text(when {
                vm.recBusy -> "—"
                st == null -> vm.recError.ifEmpty { "—" }
                st.count == 0 -> "brak nagrań"
                else -> durationText(st.totalSeconds) + if (st.totalBytes > 0) "  (${humanSize(st.totalBytes)})" else ""
            })
        }
        FieldRow("Zakres nagrań") {
            Text(if (!vm.recBusy && st != null && st.count > 0) "${st.first.take(16)}  –  ${st.last.take(16)}" else "—")
        }
        OutlinedButton(onClick = vm::countRecordings, enabled = !vm.recBusy) { Text("Przelicz") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SegmentRow(seg: EditSeg, onChange: (EditSeg) -> Unit, onDelete: () -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TimeField("Od", seg.start) { onChange(seg.copy(start = it)) }
            Spacer(Modifier.width(8.dp))
            TimeField("Do", seg.end) { onChange(seg.copy(end = it)) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 56.dp)) {
            SwitchRow(seg.enabled, { onChange(seg.copy(enabled = it)) })
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "Usuń") }
        }
    }
}

@Composable
private fun TimeField(label: String, value: String, onValue: (String) -> Unit) {
    val bad = try { normalizeTime(value); false } catch (_: IllegalArgumentException) { true }
    OutlinedTextField(
        value = value, onValueChange = { t -> onValue(t.filter { it.isDigit() || it == ':' }.take(8)) },
        label = { Text(label) }, isError = bad, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.width(104.dp),
    )
}

@Composable
private fun WeekTimeline(week: Map<Int, List<EditSeg>>, selected: Int, onSelect: (Int) -> Unit) {
    val measurer = rememberTextMeasurer()
    val rowH = 34.dp
    Canvas(
        Modifier.fillMaxWidth().height(24.dp + rowH * 7)
            .pointerInput(Unit) {
                detectTapGestures { pos ->
                    val row = ((pos.y - 24.dp.toPx()) / rowH.toPx()).toInt()
                    if (pos.y >= 24.dp.toPx() && row in 0..6) onSelect(WEEKDAYS[row].index)
                }
            },
    ) {
        val labelW = 40.dp.toPx()
        val x0 = labelW
        val w = size.width - x0 - 4.dp.toPx()
        val top = 24.dp.toPx()
        val rh = rowH.toPx()
        val small = TextStyle(fontSize = 10.sp, color = Brand.Muted)
        for (h in 0..24 step 4) {
            val x = x0 + w * h / 24f
            drawLine(Brand.Border, Offset(x, top - 4), Offset(x, top + rh * 7), strokeWidth = 1f)
            drawLabel(measurer, "$h", Offset(x - 6, 2f), small)
        }
        WEEKDAYS.forEachIndexed { row, wd ->
            val y = top + rh * row
            if (wd.index == selected) {
                drawRoundRect(Brand.OrangeTint, Offset(0f, y + 2), Size(size.width, rh - 4), CornerRadius(8f))
            }
            drawLabel(measurer, wd.short, Offset(6f, y + rh / 2 - 9), TextStyle(fontSize = 13.sp,
                color = if (wd.index == selected) Brand.Orange else Brand.Text))
            drawRoundRect(Color(0xFFEDEBE3), Offset(x0, y + rh * 0.28f), Size(w, rh * 0.44f), CornerRadius(4f))
            for (seg in week[wd.index].orEmpty()) {
                val sg = seg.toSegmentOrNull() ?: continue
                val a = timeToSeconds(sg.start) / 86400f
                val b = timeToSeconds(sg.end) / 86400f
                if (b <= a) continue
                drawRoundRect(if (sg.enabled) Brand.RecRegular else Brand.RecOff,
                    Offset(x0 + w * a, y + rh * 0.28f), Size(w * (b - a), rh * 0.44f), CornerRadius(4f))
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLabel(
    m: TextMeasurer, text: String, at: Offset, style: TextStyle,
) {
    drawText(m.measure(text, style), topLeft = at)
}

@Composable
private fun CopyDayDialog(source: Int, onDismiss: () -> Unit, onCopy: (List<Int>) -> Unit) {
    val boxes = remember { mutableStateMapOf<Int, Boolean>() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Kopiuj dzień") },
        text = {
            Column {
                Text("Kopiuj plan z dnia: ${DAY_NAME[source]}")
                Spacer(Modifier.height(8.dp))
                WEEKDAYS.forEach { wd ->
                    CheckRow(wd.full, boxes[wd.index] == true, { boxes[wd.index] = it }, enabled = wd.index != source)
                }
                HorizontalDivider()
                val all = WEEKDAYS.filter { it.index != source }.all { boxes[it.index] == true }
                CheckRow("Wszystkie dni", all, { on -> WEEKDAYS.forEach { if (it.index != source) boxes[it.index] = on } })
            }
        },
        confirmButton = {
            Button(onClick = { onCopy(boxes.filter { it.value && it.key != source }.keys.toList()) }) { Text("Kopiuj") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Anuluj") } },
    )
}
