package pl.arcom.aitrackereye.ui.tabs

import android.net.Uri
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import pl.arcom.aitrackereye.MainViewModel
import pl.arcom.aitrackereye.PlayerMode
import pl.arcom.aitrackereye.model.*
import pl.arcom.aitrackereye.ui.*
import java.time.LocalDate
import java.time.YearMonth

/** Zakładka: Odtwarzanie i pobieranie nagrań z karty. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlaybackTab(vm: MainViewModel, enabled: Boolean) {
    val ctx = LocalContext.current
    var calendarOpen by remember { mutableStateOf(false) }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            try {
                ctx.contentResolver.takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            } catch (_: SecurityException) {
            }
            vm.setDownloadFolder(uri)
        }
    }

    TabPage(enabled, onApply = null, onRefresh = null) {
        Section("Nagrania z dnia") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { calendarOpen = true }) {
                    Icon(Icons.Default.CalendarMonth, null)
                    Spacer(Modifier.width(8.dp))
                    Text(vm.playDay.toString())
                }
                Button(onClick = vm::search, enabled = !vm.searching) { Text("Szukaj") }
            }
            Hint(vm.foundText.ifEmpty { "Kropka w kalendarzu = dzień z nagraniami" })
        }

        BoxWithConstraints {
            if (maxWidth > 760.dp) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                    Box(Modifier.weight(1f)) { FileList(vm) }
                    Box(Modifier.weight(1f)) { PlayerSection(vm) }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    PlayerSection(vm)
                    FileList(vm)
                }
            }
        }

        DownloadSection(vm) { pickFolder.launch(vm.downloadTree) }
    }

    if (calendarOpen) {
        RecordingCalendarDialog(vm.playDay, vm.recDays, onDismiss = { calendarOpen = false }) {
            vm.playDay = it
            calendarOpen = false
            vm.search()
        }
    }
}

@Composable
private fun FileList(vm: MainViewModel) {
    Section("Lista nagrań") {
        if (vm.files.isEmpty()) Hint("Wybierz dzień i kliknij „Szukaj”.")
        // lista w przewijanej zakładce: własne przewijanie przy ograniczonej wysokości
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
            vm.files.forEachIndexed { i, f ->
                val sel = i == vm.selected
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                        .background(if (sel) Brand.OrangeTint else Color.Transparent)
                        .clickable { vm.selected = i }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = i in vm.checked, onCheckedChange = { on ->
                        vm.checked = if (on) vm.checked + i else vm.checked - i
                    })
                    Column(Modifier.weight(1f)) {
                        Text("${f.start.drop(11)} – ${f.end.drop(11)}", fontWeight = FontWeight.Medium)
                        Text("${durationText(f.seconds)} · ${if (f.size > 0) humanSize(f.size) else "—"}",
                            fontSize = 12.sp, color = Brand.Muted)
                    }
                    IconButton(onClick = { vm.play(i) }) { Icon(Icons.Default.PlayArrow, contentDescription = "Odtwórz") }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.checked = vm.files.indices.toSet() }, enabled = vm.files.isNotEmpty()) {
                Text("Zaznacz wszystko")
            }
            OutlinedButton(onClick = { vm.checked = emptySet() }, enabled = vm.files.isNotEmpty()) {
                Text("Odznacz wszystko")
            }
        }
    }
}

@Composable
private fun PlayerSection(vm: MainViewModel) {
    val f = vm.playFile
    val playing = vm.playerMode == PlayerMode.PLAYBACK
    var dragging by remember { mutableStateOf(false) }
    var sliderPos by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(playing, vm.playOffset, f) {
        while (playing) {
            if (!dragging && f != null) sliderPos = vm.playPosition().coerceAtMost(f.seconds).toFloat()
            delay(500)
        }
        if (!playing) sliderPos = vm.playOffset.toFloat()
    }
    Section("Odtwarzanie") {
        VideoSurface(vm, PlayerMode.PLAYBACK,
            idleText = if (f == null) "Wybierz nagranie z listy" else "Odtwarzanie zatrzymane",
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)))
        val total = (f?.seconds ?: 1L).coerceAtLeast(1L)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(clock(sliderPos.toLong()), fontSize = 12.sp, color = Brand.Muted, modifier = Modifier.width(56.dp))
            Slider(
                value = sliderPos.coerceIn(0f, total.toFloat()),
                onValueChange = { dragging = true; sliderPos = it },
                onValueChangeFinished = { dragging = false; vm.playAt(sliderPos.toLong()) },
                valueRange = 0f..total.toFloat(),
                enabled = playing && f != null,
                modifier = Modifier.weight(1f),
            )
            Text(clock(f?.seconds ?: 0), fontSize = 12.sp, color = Brand.Muted, modifier = Modifier.width(56.dp),
                textAlign = TextAlign.End)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.play() }, enabled = vm.connected && vm.selected >= 0) { Text("Odtwórz") }
            OutlinedButton(onClick = vm::stopPlayer, enabled = playing) { Text("Zatrzymaj") }
        }
        if (f != null) Hint("${f.start}  –  ${f.end.drop(11)}")
    }
}

@Composable
private fun DownloadSection(vm: MainViewModel, chooseFolder: () -> Unit) {
    val dl = vm.download
    val checkedFiles = vm.checked.mapNotNull { vm.files.getOrNull(it) }
    Section("Pobieranie") {
        FieldRow("Folder") {
            Column {
                Text(vm.downloadFolderLabel())
                TextButton(onClick = chooseFolder, contentPadding = PaddingValues(0.dp)) { Text("Wybierz…") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                if (vm.downloadTree == null) chooseFolder() else vm.downloadChecked()
            }, enabled = vm.connected && checkedFiles.isNotEmpty() && dl == null) { Text("Pobierz zaznaczone") }
            OutlinedButton(onClick = vm::cancelDownload, enabled = dl != null) { Text("Anuluj") }
        }
        if (dl != null) {
            LinearProgressIndicator(progress = { (dl.done.toFloat() / dl.total).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(), color = Brand.Orange, trackColor = Brand.Border)
            Hint("Plik ${dl.index}/${dl.count} – pobieranie… ${humanSize(dl.done)} / ${humanSize(dl.total)}")
        } else {
            Hint(when {
                vm.downloadInfo.isNotEmpty() && checkedFiles.isEmpty() -> vm.downloadInfo
                checkedFiles.isNotEmpty() -> "Zaznaczono: ${checkedFiles.size} (${humanSize(checkedFiles.sumOf { it.size })})"
                else -> "Zaznacz nagrania do pobrania. Pliki trafiają do: folder / napis na obrazie / dzień"
            })
        }
    }
}

/** Kalendarz z pomarańczową kropką w dniach, w których są nagrania. */
@Composable
private fun RecordingCalendarDialog(initial: LocalDate, days: Set<String>, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    var month by remember { mutableStateOf(YearMonth.from(initial)) }
    val monthNames = listOf("Styczeń", "Luty", "Marzec", "Kwiecień", "Maj", "Czerwiec", "Lipiec", "Sierpień",
        "Wrzesień", "Październik", "Listopad", "Grudzień")
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Anuluj") } },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { month = month.minusMonths(1) }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Poprzedni miesiąc")
                }
                Text("${monthNames[month.monthValue - 1]} ${month.year}", Modifier.weight(1f), textAlign = TextAlign.Center)
                IconButton(onClick = { month = month.plusMonths(1) }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Następny miesiąc")
                }
            }
        },
        text = {
            Column {
                Row {
                    listOf("Pn", "Wt", "Śr", "Cz", "Pt", "Sb", "Nd").forEach {
                        Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, color = Brand.Muted, fontSize = 13.sp)
                    }
                }
                val first = month.atDay(1)
                val lead = first.dayOfWeek.value - 1
                val count = month.lengthOfMonth()
                val cells = lead + count
                for (week in 0 until (cells + 6) / 7) {
                    Row {
                        for (col in 0..6) {
                            val n = week * 7 + col - lead + 1
                            Box(Modifier.weight(1f).aspectRatio(1f), contentAlignment = Alignment.Center) {
                                if (n in 1..count) {
                                    val date = month.atDay(n)
                                    val sel = date == initial
                                    val has = date.toString() in days
                                    Box(
                                        Modifier.size(40.dp).clip(CircleShape)
                                            .background(if (sel) Brand.Orange else Color.Transparent)
                                            .clickable { onPick(date) },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text("$n", color = if (sel) Color.White else Brand.Text,
                                            fontWeight = if (has) FontWeight.Bold else FontWeight.Normal)
                                        if (has) {
                                            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp).size(5.dp)
                                                .clip(CircleShape).background(if (sel) Color.White else Brand.Orange))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
    )
}
