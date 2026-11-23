package pl.arcom.aitrackereye.ui.tabs

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import pl.arcom.aitrackereye.MainViewModel
import pl.arcom.aitrackereye.camera.Snapshot
import pl.arcom.aitrackereye.model.*
import pl.arcom.aitrackereye.ui.*

/** Stan edytora jednego strumienia. Zapisuje tylko pola zmienione przez użytkownika. */
class StreamState(val cfg: Map<String, String>, caps: Map<String, String>, ch: Int, val stream: Int) {
    val prefix = streamPrefix(ch, stream)
    val available = streamAvailable(cfg, ch, stream)
    private val cp = capsPrefix(prefix)
    fun key(f: String) = "$prefix.$f"
    fun has(f: String) = key(f) in cfg
    private fun v(f: String) = cfg[key(f)]

    val enableInit: Boolean? = v("VideoEnable")?.takeIf { isBool(it) }?.let { it.lowercase() == "true" }

    val compressionInit = v("Video.Compression")
    val compressions: List<String> = caps["$cp.Video.CompressionTypes"].orEmpty().split(",").map { it.trim() }
        .filter { it.isNotEmpty() }.toMutableList().also { if (compressionInit != null && compressionInit !in it) it.add(0, compressionInit) }

    val resInit: Size = intOr(v("Video.Width"), 0) to intOr(v("Video.Height"), 0)
    val resolutions: List<Size> = parseResolutions(caps["$cp.Video.ResolutionTypes"]).toMutableList()
        .also { if (resInit.first > 0 && resInit.second > 0 && resInit !in it) it.add(0, resInit) }
    val resEditable = resInit.first > 0 && resInit.second > 0 && has("Video.Width") && has("Video.Height") && resolutions.size > 1

    val fpsInit: Double = floatOr(v("Video.FPS"), 25.0)
    val fpsOptions: List<Double> = run {
        val max = maxOf(floatOr(caps["$cp.Video.FPSMax"], 25.0).toInt(), fpsInit.toInt(), 1)
        val list = (1..max).map { it.toDouble() }.toMutableList()
        if (fpsInit !in list) list.add(fpsInit)
        list
    }

    val controlInit = v("Video.BitRateControl")
    val controls = bitrateControls(controlInit)

    val bitrateInit = intOr(v("Video.BitRate"), 0)
    private val brRange = intRange(caps, "$cp.Video.BitRateOptions", 32 to 20480)
    val bitrateNote = if ("$cp.Video.BitRateOptions" in caps) "Zalecany zakres ${brRange.first}-${brRange.second} kb/s" else null
    val bitrateRange = minOf(brRange.first, if (bitrateInit > 0) bitrateInit else brRange.first)..maxOf(brRange.second, bitrateInit)

    val qualityInit = v("Video.Quality") ?: "4"
    val gopInit = intOr(v("Video.GOP"), 50)

    var enable by mutableStateOf(enableInit ?: false)
    var compression by mutableStateOf(compressionInit)
    var resolution by mutableStateOf(resInit)
    var fps by mutableStateOf(fpsInit)
    var control by mutableStateOf(controlInit)
    var bitrate by mutableIntStateOf(bitrateInit)
    var quality by mutableStateOf(qualityInit)
    var gop by mutableIntStateOf(gopInit)

    val qualityEnabled get() = has("Video.Quality") && control == "VBR"

    fun collect(isSub: Boolean): Map<String, String> {
        if (!available && !(isSub && enableInit != null)) return emptyMap()
        val changes = linkedMapOf<String, String>()
        fun put(f: String, value: String) { if (has(f)) changes[key(f)] = value }
        if (isSub && enableInit != null && enable != enableInit) put("VideoEnable", enable.toString())
        if (!available) return changes
        if (compression != compressionInit && compression != null) {
            put("Video.Compression", compression!!)
            profileFix(compression!!, cfg[key("Video.Profile")])?.let { put("Video.Profile", it) }
        }
        if (resolution != resInit && resolution.first > 0) {
            put("Video.Width", resolution.first.toString())
            put("Video.Height", resolution.second.toString())
        }
        if (fps != fpsInit) put("Video.FPS", if (fps % 1.0 == 0.0) fps.toInt().toString() else fps.toString())
        if (control != controlInit && control != null) put("Video.BitRateControl", control!!)
        if (bitrate != bitrateInit) put("Video.BitRate", bitrate.toString())
        if (quality != qualityInit && qualityEnabled) put("Video.Quality", quality)
        if (gop != gopInit) put("Video.GOP", gop.toString())
        return changes
    }
}

@Composable
private fun StreamEditor(title: String, st: StreamState, isSub: Boolean, subSelector: (@Composable () -> Unit)? = null) {
    Section(title) {
        if (isSub) {
            subSelector?.invoke()
            FieldRow("Włączony", enabled = st.enableInit != null) {
                SwitchRow(st.enable, { st.enable = it }, enabled = st.enableInit != null)
            }
        }
        if (!st.available) Hint(UNAVAILABLE) else StreamFields(st)
    }
}

@Composable
private fun StreamFields(st: StreamState) {
    FieldRow("Kompresja", enabled = st.compressionInit != null) {
        Dropdown(st.compressions.map { it to it }, st.compression, { st.compression = it }, enabled = st.compressionInit != null)
    }
    FieldRow("Rozdzielczość", enabled = st.resEditable) {
        Dropdown(st.resolutions.map { it to resolutionLabel(it) }, st.resolution, { st.resolution = it }, enabled = st.resEditable)
    }
    FieldRow("Klatki (FPS)", enabled = st.has("Video.FPS")) {
        Dropdown(st.fpsOptions.map { it to (if (it % 1.0 == 0.0) it.toInt().toString() else it.toString()) },
            st.fps, { st.fps = it }, enabled = st.has("Video.FPS"))
    }
    FieldRow("Typ bitrate", enabled = st.controlInit != null) {
        Dropdown(st.controls, st.control, { st.control = it }, enabled = st.controlInit != null)
    }
    FieldRow("Bitrate", enabled = st.has("Video.BitRate"), note = st.bitrateNote) {
        NumberField(st.bitrate, { st.bitrate = it }, st.bitrateRange, "kb/s", step = 128, enabled = st.has("Video.BitRate"))
    }
    FieldRow("Jakość (VBR)", enabled = st.qualityEnabled, disabledNote = if (st.has("Video.Quality")) "Tylko przy typie VBR" else UNAVAILABLE) {
        Dropdown((1..6).map { "$it" to "$it" }, st.quality, { st.quality = it }, enabled = st.qualityEnabled)
    }
    val gopNote = if (st.fps > 0) "= %.1f s".format(st.gop / st.fps) else null
    FieldRow("Interwał I-ramek", enabled = st.has("Video.GOP"), note = gopNote) {
        NumberField(st.gop, { st.gop = it }, 1..maxOf(1000, st.gopInit), enabled = st.has("Video.GOP"))
    }
}

/** Zakładka: Obraz (napis, kodowanie strumienia głównego i podstrumienia, znak wodny). */
@Composable
fun EncodeTab(vm: MainViewModel, snap: Snapshot?, enabled: Boolean) {
    val s = snap ?: Snapshot()
    val ch = vm.ch
    val cfg = s.section("Encode")
    val main = remember(snap) { StreamState(cfg, s.encodeCaps, ch, 0) }
    val subs = remember(snap) { (1..2).filter { streamAvailable(cfg, ch, it) }.ifEmpty { listOf(1) } }
    var subIndex by remember(snap) { mutableIntStateOf(subs.first()) }
    val sub = remember(snap, subIndex) { StreamState(cfg, s.encodeCaps, ch, subIndex) }

    val titles = s.section("ChannelTitle")
    val titleKey = remember(snap) { findKey(titles, listOf("ChannelTitle[$ch].Name")) }
    val titleInit = titleKey?.let { titles[it] } ?: ""
    var title by remember(snap) { mutableStateOf(titleInit) }

    val wm = s.section("VideoWaterMark")
    val wmEnKey = remember(snap) { findKey(wm, listOf("VideoWaterMark[$ch].Enable")) }
    val wmTxtKey = remember(snap) { findKey(wm, listOf("VideoWaterMark[$ch].String", "VideoWaterMark[$ch].Text")) }
    val wmEnInit = wmEnKey != null && wm[wmEnKey]!!.lowercase() == "true"
    val wmTxtInit = wmTxtKey?.let { wm[it] } ?: ""
    var wmEnable by remember(snap) { mutableStateOf(wmEnInit) }
    var wmText by remember(snap) { mutableStateOf(wmTxtInit) }

    fun collect(): Map<String, String> {
        val changes = linkedMapOf<String, String>()
        changes.putAll(main.collect(false))
        changes.putAll(sub.collect(true))
        val t = title.trim()
        if (titleKey != null && t != titleInit) {
            if (t.isEmpty()) throw IllegalArgumentException("Wpisz napis na obrazie.")
            changes[titleKey] = t
        }
        if (wmEnKey != null && wmEnable != wmEnInit) changes[wmEnKey] = wmEnable.toString()
        if (wmTxtKey != null && wmText != wmTxtInit) changes[wmTxtKey] = wmText
        return changes
    }

    TabPage(enabled, onApply = {
        try {
            vm.applyChanges("Obraz", collect())
        } catch (e: IllegalArgumentException) {
            vm.showError("Obraz", e.message ?: "Błąd")
        }
    }, onRefresh = vm::reload) {
        Section("Nazwa na obrazie") {
            FieldRow("Napis", enabled = titleKey != null,
                note = "Widoczny w lewym dolnym rogu obrazu. Używany też jako nazwa katalogu przy pobieraniu nagrań.") {
                OutlinedTextField(title, { title = it.take(TITLE_MAX) }, singleLine = true, enabled = titleKey != null,
                    placeholder = { Text("np. Brama wjazdowa") }, modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth())
            }
        }
        BoxWithConstraints {
            val wide = maxWidth > 900.dp
            val selector: @Composable () -> Unit = {
                FieldRow("Podstrumień") {
                    Dropdown(subs.map { it to "Podstrumień $it" }, subIndex, { subIndex = it })
                }
            }
            if (wide) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                    Box(Modifier.weight(1f)) { StreamEditor("Strumień główny", main, false) }
                    Box(Modifier.weight(1f)) { StreamEditor("Podstrumień", sub, true, selector) }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    StreamEditor("Strumień główny", main, false)
                    StreamEditor("Podstrumień", sub, true, selector)
                }
            }
        }
        Section("Znak wodny") {
            FieldRow("Znak wodny", enabled = wmEnKey != null) {
                SwitchRow(wmEnable, { wmEnable = it }, enabled = wmEnKey != null)
            }
            FieldRow("Tekst", enabled = wmTxtKey != null) {
                OutlinedTextField(wmText, { wmText = it }, singleLine = true, enabled = wmTxtKey != null,
                    modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth())
            }
        }
        Hint("Zmiana kodowania może na chwilę przerwać podgląd.")
    }
}
