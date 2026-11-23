package pl.arcom.aitrackereye.ui.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pl.arcom.aitrackereye.MainViewModel
import pl.arcom.aitrackereye.camera.Snapshot
import pl.arcom.aitrackereye.model.*
import pl.arcom.aitrackereye.ui.*

/** Zakładka: Pamięć (zapis nagrań, pełna karta) + stan karty SD i formatowanie. */
@Composable
fun StorageTab(vm: MainViewModel, snap: Snapshot?, enabled: Boolean) {
    val s = snap ?: Snapshot()
    val ch = vm.ch
    val all = remember(snap) { merged(s.config) }
    val recKey = remember(snap) { storagePointKey(ch, "TimingRecord").takeIf { isBool(all[it]) } }
    val recInit = recKey != null && all[recKey]!!.lowercase() == "true"
    val owKey = remember(snap) { findKey(all, OVERWRITE_KEYS)?.takeIf { isBool(all[it]) } }
    val owInit = owKey != null && all[owKey]!!.lowercase() == "true"
    var record by remember(snap) { mutableStateOf(recInit) }
    var overwrite by remember(snap) { mutableStateOf(owInit) }
    var selectedDev by remember(snap) { mutableIntStateOf(0) }

    fun collect(): Map<String, String> {
        val changes = linkedMapOf<String, String>()
        if (recKey != null && record != recInit) changes[recKey] = record.toString()
        if (owKey != null && overwrite != owInit) changes[owKey] = overwrite.toString()
        return changes
    }

    TabPage(enabled, onApply = { vm.applyChanges("Pamięć", collect()) }, onRefresh = vm::reload) {
        Section("Zapis na kartę") {
            FieldRow("Zapis nagrań", enabled = recKey != null) {
                SwitchRow(record, { record = it }, enabled = recKey != null)
            }
            FieldRow("Pełna karta", enabled = owKey != null) {
                RadioGroup(listOf(true to "Zastąp najstarsze", false to "Zatrzymaj nagrywanie"), overwrite,
                    { overwrite = it }, enabled = owKey != null)
            }
            FieldRow("Metoda") { Text("Pamięć lokalna (karta SD)") }
        }
        Section("Karta pamięci") {
            if (s.storage.isEmpty()) {
                Hint(s.storageError ?: "Nie wykryto karty pamięci.")
            }
            s.storage.forEachIndexed { i, d ->
                val sel = i == selectedDev
                Column(
                    Modifier.fillMaxWidth()
                        .border(if (sel) 2.dp else 1.dp, if (sel) Brand.Orange else Brand.Border, RoundedCornerShape(8.dp))
                        .background(if (sel) Brand.OrangeTint else Brand.Panel, RoundedCornerShape(8.dp))
                        .clickable { selectedDev = i }
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(d.name, fontWeight = FontWeight.Bold)
                    Text("Stan: " + if (d.error) "Błąd" else label(STATE_LABELS, d.state))
                    Text("Właściwości: " + label(ACCESS_LABELS, d.access))
                    if (d.total > 0) {
                        Text("Zajęte: ${humanSize(d.used)} / ${humanSize(d.total)}")
                        LinearProgressIndicator(
                            progress = { (d.used.toFloat() / d.total).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(), color = Brand.Orange, trackColor = Brand.Border,
                        )
                    }
                    Text("System plików: " + d.fs.ifEmpty { "—" })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { s.storage.getOrNull(selectedDev)?.let(vm::formatCard) },
                    enabled = s.storage.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = Brand.OffBg),
                ) { Text("Formatuj kartę") }
                OutlinedButton(onClick = vm::reload) { Text("Odśwież") }
            }
        }
    }
}
