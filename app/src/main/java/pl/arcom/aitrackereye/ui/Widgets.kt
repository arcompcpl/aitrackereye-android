package pl.arcom.aitrackereye.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

const val UNAVAILABLE = "Niedostępne w tej kamerze"

/** Grupa ustawień z tytułem (odpowiednik QGroupBox). */
@Composable
fun Section(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(containerColor = Brand.Panel),
        border = BorderStroke(1.dp, Brand.Border),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (title.isNotEmpty()) {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Brand.Dark)
            }
            content()
        }
    }
}

/** Wiersz formularza: etykieta + pole; na wąskim ekranie etykieta nad polem. */
@Composable
fun FieldRow(
    label: String, enabled: Boolean = true, note: String? = null, disabledNote: String = UNAVAILABLE,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val wide = maxWidth > 520.dp
        val alpha = if (enabled) 1f else 0.45f
        if (wide) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, Modifier.width(190.dp).alpha(alpha), color = Brand.Muted, fontSize = 14.sp)
                Column(Modifier.weight(1f)) {
                    Box(Modifier.alpha(alpha)) { content() }
                    if (!enabled) Hint(disabledNote) else if (note != null) Hint(note)
                }
            }
        } else {
            Column {
                Text(label, Modifier.alpha(alpha), color = Brand.Muted, fontSize = 13.sp)
                Spacer(Modifier.height(2.dp))
                Box(Modifier.alpha(alpha)) { content() }
                if (!enabled) Hint(disabledNote) else if (note != null) Hint(note)
            }
        }
    }
}

@Composable
fun Hint(text: String, modifier: Modifier = Modifier) {
    if (text.isNotEmpty()) Text(text, modifier, color = Brand.Muted, fontSize = 12.sp)
}

/** Lista rozwijana (odpowiednik QComboBox). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> Dropdown(
    options: List<Pair<T, String>>, selected: T?, onSelect: (T) -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    val label = options.firstOrNull { it.first == selected }?.second ?: (selected?.toString() ?: "—")
    ExposedDropdownMenuBox(expanded = expanded && enabled, onExpandedChange = { if (enabled) expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = label, onValueChange = {}, readOnly = true, enabled = enabled, singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).widthIn(min = 160.dp, max = 420.dp).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            options.forEach { (value, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { onSelect(value); expanded = false })
            }
        }
    }
}

/** Pole liczby całkowitej z zakresem i przyciskami -/+ (odpowiednik QSpinBox). */
@Composable
fun NumberField(
    value: Int, onValue: (Int) -> Unit, range: IntRange, suffix: String = "", step: Int = 1,
    enabled: Boolean = true, modifier: Modifier = Modifier,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        OutlinedIconButtonSmall("−", enabled && value > range.first) { onValue((value - step).coerceIn(range)) }
        OutlinedTextField(
            value = text,
            onValueChange = { t ->
                text = t.filter { it.isDigit() }.take(7)
                text.toIntOrNull()?.let { if (it in range) onValue(it) }
            },
            enabled = enabled, singleLine = true,
            suffix = if (suffix.isNotEmpty()) ({ Text(suffix) }) else null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(140.dp).padding(horizontal = 6.dp),
        )
        OutlinedIconButtonSmall("+", enabled && value < range.last) { onValue((value + step).coerceIn(range)) }
    }
}

@Composable
private fun OutlinedIconButtonSmall(text: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, contentPadding = PaddingValues(0.dp),
        modifier = Modifier.size(44.dp)) { Text(text, fontSize = 20.sp) }
}

/** Grupa przycisków radiowych w poziomie (zawija się na wąskim ekranie). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> RadioGroup(options: List<Pair<T, String>>, selected: T?, onSelect: (T) -> Unit, enabled: Boolean = true) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, text) ->
            Row(
                Modifier
                    .selectable(selected = value == selected, enabled = enabled, role = Role.RadioButton) { onSelect(value) }
                    .padding(end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = value == selected, onClick = null, enabled = enabled)
                Spacer(Modifier.width(4.dp))
                Text(text)
            }
        }
    }
}

@Composable
fun SwitchRow(checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true, onText: String = "Włączony", offText: String = "Wyłączony") {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
        Spacer(Modifier.width(10.dp))
        Text(if (checked) onText else offText)
    }
}

@Composable
fun CheckRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(
        Modifier.clickable(enabled = enabled) { onChange(!checked) }.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Spacer(Modifier.width(6.dp))
        Text(text)
    }
}

@Composable
fun BigValue(text: String) {
    Text(text, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = Brand.Dark)
}

/** Przezroczysta warstwa blokująca dotyk (zakładka bez połączenia lub w trakcie operacji). */
@Composable
fun InputBlocker(modifier: Modifier) {
    Box(modifier.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
        }
    })
}

/** Treść zakładki: przewijana kolumna + pasek Zastosuj / Odśwież. */
@Composable
fun TabPage(
    enabled: Boolean,
    onApply: (() -> Unit)?,
    onRefresh: (() -> Unit)?,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)
                    .alpha(if (enabled) 1f else 0.55f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) { content() }
            if (!enabled) InputBlocker(Modifier.matchParentSize())
        }
        if (onApply != null || onRefresh != null) {
            HorizontalDivider(color = Brand.Border)
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                if (onRefresh != null) OutlinedButton(onClick = onRefresh, enabled = enabled) { Text("Odśwież") }
                if (onApply != null) Button(onClick = onApply, enabled = enabled) { Text("Zastosuj") }
            }
        }
    }
}
