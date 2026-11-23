package pl.arcom.aitrackereye

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import pl.arcom.aitrackereye.camera.*
import pl.arcom.aitrackereye.model.*
import pl.arcom.aitrackereye.util.AppLog
import pl.arcom.aitrackereye.util.Net
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean

enum class StripState { OK, OFF, BUSY }

sealed class UiDialog {
    data class Message(val title: String, val text: String, val isError: Boolean = false) : UiDialog()
    data class Confirm(
        val title: String, val text: String, val yes: String, val danger: Boolean = false, val onYes: () -> Unit,
    ) : UiDialog()
}

enum class PlayerMode { NONE, LIVE, PLAYBACK }

data class DownloadState(val done: Long, val total: Long, val index: Int, val count: Int)

fun describe(e: Throwable): String = when (e) {
    is CameraException, is IllegalArgumentException -> e.message ?: "Błąd"
    is IOException -> "Błąd zapisu pliku: ${e.message ?: e.javaClass.simpleName}"
    else -> "Nieoczekiwany błąd: ${e.javaClass.simpleName}"
}

@OptIn(UnstableApi::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)

    // ================================================================ formularz połączenia
    var host by mutableStateOf(prefs.getString("host", "") ?: "")
        private set
    var user by mutableStateOf(prefs.getString("user", "admin") ?: "admin")
        private set
    var password by mutableStateOf("")       // hasła celowo nie zapisujemy
        private set
    var httpPort by mutableStateOf(prefs.getInt("http_port", 80).toString())
        private set
    var rtspPort by mutableStateOf(prefs.getInt("rtsp_port", 554).toString())
        private set
    var channel by mutableStateOf(prefs.getInt("channel", 1).toString())
        private set
    var https by mutableStateOf(prefs.getBoolean("https", false))
        private set
    var verifyTls by mutableStateOf(prefs.getBoolean("verify_tls", true))
        private set

    fun updateHost(v: String) { host = v.trim(); fieldsChanged() }
    fun updateUser(v: String) { user = v; fieldsChanged() }
    fun updatePassword(v: String) { password = v; fieldsChanged() }
    fun updateHttpPort(v: String) { httpPort = v.filter { it.isDigit() }.take(5); fieldsChanged() }
    fun updateRtspPort(v: String) { rtspPort = v.filter { it.isDigit() }.take(5); fieldsChanged() }
    fun updateChannel(v: String) { channel = v.filter { it.isDigit() }.take(3); fieldsChanged() }
    fun updateVerifyTls(v: Boolean) { verifyTls = v; fieldsChanged() }
    fun updateHttps(on: Boolean) {
        https = on
        if (httpPort == "80" || httpPort == "443") httpPort = if (on) "443" else "80"
        fieldsChanged()
    }

    fun formAddress(): CameraAddress = CameraAddress(
        host = host.trim(), user = user.trim(), password = password,
        httpPort = httpPort.toIntOrNull() ?: 0, rtspPort = rtspPort.toIntOrNull() ?: 0,
        channel = channel.toIntOrNull() ?: 0, https = https, verifyTls = verifyTls,
    )

    private fun formAddressOrNull(): CameraAddress? = try { formAddress() } catch (_: IllegalArgumentException) { null }

    private fun saveSettings() {
        prefs.edit()
            .putString("host", host.trim()).putString("user", user.trim())
            .putInt("http_port", httpPort.toIntOrNull() ?: 80).putInt("rtsp_port", rtspPort.toIntOrNull() ?: 554)
            .putInt("channel", channel.toIntOrNull() ?: 1)
            .putBoolean("https", https).putBoolean("verify_tls", verifyTls)
            .apply()
    }

    private fun fieldsChanged() {
        if (addr == null || busy) return
        if (formAddressOrNull() != addr) disconnectState("Zmieniono dane połączenia – kliknij „Połącz”")
    }

    // ================================================================ stan połączenia
    var snapshot by mutableStateOf<Snapshot?>(null)
        private set
    var addr by mutableStateOf<CameraAddress?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    private var busyText by mutableStateOf("")
    var lost by mutableStateOf(false)
        private set
    private var offText by mutableStateOf("Brak połączenia z kamerą")
    private var message by mutableStateOf("")
    var pingText by mutableStateOf("")
        private set
    var dialog by mutableStateOf<UiDialog?>(null)

    val connected: Boolean get() = snapshot != null && addr != null && !lost
    val ch: Int get() = addr?.cgiChannel ?: 0

    val stripState: StripState get() = when {
        busy -> StripState.BUSY
        connected -> StripState.OK
        else -> StripState.OFF
    }
    val stripText: String get() = when {
        busy -> busyText
        connected -> "Połączono z ${addr!!.host} · kanał ${addr!!.channel}" + if (message.isNotEmpty()) "   —   $message" else ""
        else -> offText
    }

    fun setMessage(text: String) { message = text }

    private fun disconnectState(text: String = "Brak połączenia z kamerą") {
        if (!lost) {
            snapshot = null
            addr = null
        }
        offText = text
    }

    fun showError(title: String, text: String) { dialog = UiDialog.Message(title, text, isError = true) }
    fun showInfo(title: String, text: String) { dialog = UiDialog.Message(title, text) }
    fun confirm(title: String, text: String, yes: String, danger: Boolean = false, onYes: () -> Unit) {
        dialog = UiDialog.Confirm(title, text, yes, danger, onYes)
    }

    // ================================================================ zadania w tle
    private fun <T> runJob(text: String, op: () -> T, ok: (T) -> Unit, fail: (Throwable) -> Unit) {
        if (busy) return
        busy = true
        busyText = text
        viewModelScope.launch {
            val result = try {
                Result.success(withContext(Dispatchers.IO) { op() })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Result.failure(e)
            }
            busy = false
            result.fold(ok, fail)
        }
    }

    private fun <T> background(op: () -> T, ok: (T) -> Unit, fail: (Throwable) -> Unit) {
        viewModelScope.launch {
            val result = try {
                Result.success(withContext(Dispatchers.IO) { op() })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Result.failure(e)
            }
            result.fold(ok, fail)
        }
    }

    private fun logError(what: String, e: Throwable) {
        if (e is CameraException || e is IllegalArgumentException || e is IOException) AppLog.warn("$what: ${describe(e)}")
        else AppLog.error("$what: nieoczekiwany błąd", e)
    }

    init {
        viewModelScope.launch {
            while (true) {
                delay(HEARTBEAT_MS)
                heartbeat()
            }
        }
        viewModelScope.launch {
            while (true) {
                ping()
                delay(PING_MS)
            }
        }
    }

    // ================================================================ ping
    private var pinging = false

    private fun ping() {
        val a = formAddressOrNull()
        if (a == null) {
            pingText = ""
            return
        }
        if (pinging) return
        pinging = true
        background({ Net.ping(a.host, a.httpPort) }, { r ->
            pinging = false
            if (host.trim() == a.host) pingText = Net.pingText(r)
        }, { pinging = false; pingText = "" })
    }

    // ================================================================ połączenie
    fun connect() {
        val a = try {
            formAddress()
        } catch (e: IllegalArgumentException) {
            showError("Połączenie", e.message ?: "Błędne dane.")
            return
        }
        if (a.password.isEmpty()) {
            showError("Połączenie", "Wpisz hasło.")
            return
        }
        saveSettings()
        lost = false
        snapshot = null
        addr = null
        message = ""
        AppLog.info("Łączenie z ${a.host}:${a.httpPort} kanał ${a.channel}")
        runJob("Łączenie z ${a.host}…", { CameraApi(a).use { it.read() } }, { snap ->
            AppLog.info("Połączono z ${a.host}")
            loaded(a, snap)
        }, { e ->
            logError("Połączenie", e)
            disconnectState()
            showError("Połączenie", describe(e))
        })
    }

    fun disconnect() {
        stopPlayer()
        lost = false
        message = ""
        disconnectState()
        setCameraTime(null)
    }

    fun reload() {
        val a = addr ?: return
        runJob("Odczyt ustawień…", { CameraApi(a).use { it.read() } }, { loaded(a, it) }, { e ->
            logError("Odczyt", e)
            if (e is ConnectionLostException) markLost() else disconnectState()
            showError("Odczyt", describe(e))
        })
    }

    private fun loaded(a: CameraAddress, snap: Snapshot) {
        addr = a
        snapshot = snap
        lost = false
        setCameraTime(snap.currentTime)
        AppLog.saveDiagnostic(redactedDiagnostic(snap, a.host, a.channel, BuildConfig.VERSION_NAME))
        if (snap.errors.isNotEmpty()) AppLog.info("Brak w kamerze: ${snap.errors.keys.joinToString(", ")}")
        if (files.isEmpty()) cameraNow()?.let { playDay = it.toLocalDate() }
        countRecordings()
        ping()
    }

    private fun markLost() {
        AppLog.warn("Utracono połączenie z ${addr?.host ?: "kamerą"}")
        lost = true
        offText = "Utracono połączenie z ${addr?.host ?: "kamerą"} – ponawiam…"
    }

    private fun heartbeat() {
        val a = addr ?: return
        if (busy) return
        background({ CameraApi(a, 3, 5).use { it.getTime() } }, { value ->
            if (addr != a) return@background
            if (lost) {
                AppLog.info("Połączenie wróciło")
                reload()
            } else setCameraTime(value)
        }, { e ->
            if (addr == a && !busy && e is ConnectionLostException && !lost) markLost()
        })
    }

    // ================================================================ zegar kamery
    private var camTime: LocalDateTime? = null
    private var camAt = 0L
    var clockTick by mutableStateOf(0L)
        private set

    private fun setCameraTime(text: String?) {
        camTime = parseCameraTime(text)
        camAt = SystemClock.elapsedRealtime()
        clockTick++
    }

    fun cameraNow(): LocalDateTime? = camTime?.plusNanos((SystemClock.elapsedRealtime() - camAt) * 1_000_000)

    // ================================================================ nagrania na karcie
    var recStats by mutableStateOf<RecordingStats?>(null)
        private set
    var recBusy by mutableStateOf(false)
        private set
    var recError by mutableStateOf("")
        private set
    var recDays by mutableStateOf<Set<String>>(emptySet())
        private set
    private var recToken = 0

    fun countRecordings() {
        if (!connected) return
        val a = addr!!
        val token = ++recToken
        recBusy = true
        recError = ""
        background({ CameraApi(a, 4, 15).use { it.recordingStats(a.cgiChannel) } }, { stats ->
            if (token == recToken && addr == a) {
                recBusy = false
                recStats = stats
                recDays = stats.days.toSet()
            }
        }, { e ->
            AppLog.warn("Liczenie nagrań: ${describe(e)}")
            if (token == recToken && addr == a) {
                recBusy = false
                recStats = null
                recError = describe(e)
            }
        })
    }

    // ================================================================ zapis
    fun applyChanges(title: String, changes: Map<String, String>, setTime: LocalDateTime? = null, chunked: Boolean = false) {
        if (!connected) return
        if (changes.isEmpty() && setTime == null) {
            showInfo(title, "Brak zmian do zapisania.")
            return
        }
        val a = addr!!
        val snap = snapshot!!
        runJob("Zapisywanie: $title…", {
            backupSnapshot(snap, a)
            CameraApi(a).use { api ->
                if (chunked) api.setConfigChunked(changes) else api.setConfig(changes)
                if (setTime != null) api.setTime(setTime)
            }
        }, {
            AppLog.info("Zapisano: $title (${changes.size} pól: ${changes.keys.sorted().joinToString(", ").take(500)})" +
                if (setTime != null) " + czas" else "")
            message = "✓ Zapisano: $title (${LocalDateTime.now().format(HHMM)})"
            reload()
        }, { e ->
            logError("Zapis $title", e)
            if (e is ConnectionLostException) markLost()
            showError(title, "Nie zapisano.\n" + describe(e))
        })
    }

    fun syncTime() {
        if (!connected) return
        val doSync = {
            val a = addr!!
            runJob("Ustawianie czasu…", { CameraApi(a).use { it.setTime(LocalDateTime.now()) } }, { value ->
                AppLog.info("Ustawiono czas kamery: $value")
                message = "✓ Ustawiono czas ${value.drop(11)}"
                reload()
            }, { e ->
                logError("Ustawianie czasu", e)
                showError("Czas", "Nie ustawiono czasu.\n" + describe(e))
            })
        }
        if (snapshot!!.section("NTP")["NTP.Enable"]?.lowercase() == "true") {
            confirm("Czas", "Włączony jest NTP – kamera może później zmienić czas.\nUstawić czas telefonu?", "Ustaw") { doSync() }
        } else doSync()
    }

    fun formatCard(dev: StorageDevice) {
        if (!connected) return
        confirm("Formatowanie karty", "Sformatować kartę „${dev.name}”?\nWszystkie nagrania zostaną usunięte.",
            "Formatuj", danger = true) {
            val a = addr!!
            runJob("Formatowanie karty…", { CameraApi(a).use { it.formatStorage(dev.path) } }, {
                AppLog.info("Sformatowano kartę ${dev.path}")
                message = "✓ Karta sformatowana"
                reload()
            }, { e ->
                logError("Formatowanie karty", e)
                showError("Formatowanie karty", "Nie sformatowano.\n" + describe(e))
            })
        }
    }

    private fun configJson(config: Map<String, Map<String, String>>): JSONObject {
        val cfg = JSONObject()
        config.forEach { (sec, values) -> cfg.put(sec, JSONObject(values as Map<*, *>)) }
        return cfg
    }

    private fun backupSnapshot(snap: Snapshot, a: CameraAddress) {
        val dir = AppLog.backupDir ?: return
        dir.mkdirs()
        val safe = a.host.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"))
        val doc = JSONObject()
            .put("host", a.host).put("saved_at", LocalDateTime.now().withNano(0).toString())
            .put("channel", a.channel).put("config", configJson(snap.config))
        File(dir, "${safe}_$stamp.json").writeText(doc.toString(2))
        // zostawiamy 50 najnowszych kopii
        dir.listFiles()?.sortedByDescending { it.name }?.drop(50)?.forEach { it.delete() }
    }

    // ================================================================ eksport / import
    fun exportFileName(): String =
        "aitrackereye_${folderName(addr?.host ?: "kamera")}_${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmm"))}.json"

    fun exportConfig(uri: Uri) {
        val snap = snapshot ?: return
        val a = addr ?: return
        val doc = JSONObject()
            .put("app", EXPORT_APP).put("version", BuildConfig.VERSION_NAME)
            .put("exported_at", LocalDateTime.now().withNano(0).toString())
            .put("channel", a.channel).put("config", configJson(snap.config))
        try {
            getApplication<Application>().contentResolver.openOutputStream(uri, "wt")!!.use {
                it.write(doc.toString(2).toByteArray(Charsets.UTF_8))
            }
        } catch (e: Exception) {
            showError("Eksport", "Nie zapisano pliku: ${e.message ?: e.javaClass.simpleName}")
            return
        }
        AppLog.info("Eksport konfiguracji: $uri")
        message = "✓ Wyeksportowano konfigurację"
    }

    fun importConfig(uri: Uri) {
        val snap = snapshot ?: return
        val changes = try {
            val text = getApplication<Application>().contentResolver.openInputStream(uri)!!.use {
                it.readBytes().toString(Charsets.UTF_8)
            }
            val root = try { JSONObject(text) } catch (e: Exception) { throw IOException("Nie można odczytać pliku.") }
            val cfg = root.optJSONObject("config") ?: throw IllegalArgumentException("To nie jest plik konfiguracji AiTrackerEye.")
            val doc = linkedMapOf<String, Map<String, Any?>>()
            for (sec in cfg.keys()) {
                val o = cfg.optJSONObject(sec) ?: continue
                doc[sec] = o.keys().asSequence().associateWith { k -> o.opt(k) }
            }
            importChanges(snap.config, doc)
        } catch (e: IllegalArgumentException) {
            showError("Import", e.message ?: "Błędny plik.")
            return
        } catch (e: Exception) {
            showError("Import", "Nie można odczytać pliku.")
            return
        }
        if (changes.isEmpty()) {
            showInfo("Import", "Kamera ma już te ustawienia.")
            return
        }
        confirm("Import", "Zmienić ${changes.size} ustawień w kamerze?\n${changedTabs(changes).joinToString(", ")}", "Importuj") {
            AppLog.info("Import konfiguracji: $uri (${changes.size} zmian)")
            applyChanges("Import", changes, chunked = true)
        }
    }

    // ================================================================ odtwarzacz (podgląd i nagrania)
    private var playerInstance: ExoPlayer? = null
    val player: ExoPlayer
        get() = playerInstance ?: ExoPlayer.Builder(getApplication()).build().also {
            it.addListener(playerListener)
            playerInstance = it
        }
    var playerMode by mutableStateOf(PlayerMode.NONE)
        private set
    var playerInfo by mutableStateOf("")
        private set
    var videoSize by mutableStateOf("")
        private set
    var liveSubtype by mutableStateOf(1)
    private var firstFrameAt: Long? = null

    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            AppLog.warn("Odtwarzacz: ${error.errorCodeName} ${error.message}")
            playerInfo = if (playerMode == PlayerMode.PLAYBACK && firstFrameAt != null) "Koniec nagrania"
            else "Brak obrazu. Sprawdź port RTSP, login i hasło."
            firstFrameAt = null
        }

        override fun onRenderedFirstFrame() {
            if (firstFrameAt == null) firstFrameAt = SystemClock.elapsedRealtime()
            playerInfo = ""
        }

        override fun onVideoSizeChanged(size: VideoSize) {
            if (size.width > 0) videoSize = "${size.width}×${size.height}"
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) {
                playerInfo = "Koniec nagrania"
                firstFrameAt = null
            }
        }
    }

    private fun startStream(url: String, mode: PlayerMode) {
        val source = RtspMediaSource.Factory()
            .setForceUseRtpTcp(true)
            .setTimeoutMs(8000)
            .createMediaSource(MediaItem.fromUri(url))
        player.stop()          // kamera ma limit strumieni RTSP
        firstFrameAt = null
        videoSize = ""
        playerInfo = "Łączenie…"
        playerMode = mode
        player.setMediaSource(source)
        player.prepare()
        player.playWhenReady = true
    }

    fun startLive() {
        val a = addr ?: try { formAddress() } catch (e: IllegalArgumentException) {
            showError("Podgląd", e.message ?: "")
            return
        }
        startStream(a.rtspUrl(liveSubtype), PlayerMode.LIVE)
    }

    fun stopPlayer() {
        if (playerMode == PlayerMode.NONE) return
        player.stop()
        player.clearMediaItems()
        firstFrameAt = null
        playerInfo = ""
        videoSize = ""
        playerMode = PlayerMode.NONE
    }

    // ================================================================ odtwarzanie nagrań
    var playDay by mutableStateOf<LocalDate>(LocalDate.now())
    var files by mutableStateOf<List<RecordingFile>>(emptyList())
        private set
    var checked by mutableStateOf<Set<Int>>(emptySet())
    var selected by mutableStateOf(-1)
    var searching by mutableStateOf(false)
        private set
    var foundText by mutableStateOf("")
        private set
    var playFile by mutableStateOf<RecordingFile?>(null)
        private set
    var playOffset by mutableStateOf(0L)
        private set
    private var searchToken = 0

    fun search() {
        if (!connected) return
        val a = addr!!
        val day = playDay.toString()
        val token = ++searchToken
        searching = true
        foundText = "Szukam…"
        background({ CameraApi(a, 4, 15).use { it.listRecordings(a.cgiChannel, "$day 00:00:00", "$day 23:59:59") } }, { list ->
            if (token != searchToken) return@background
            searching = false
            files = list
            checked = emptySet()
            selected = if (list.isEmpty()) -1 else 0
            val total = list.sumOf { it.seconds }
            foundText = if (list.isEmpty()) "Brak nagrań z tego dnia." else "${list.size} plików · ${durationText(total)}"
        }, { e ->
            AppLog.warn("Wyszukiwanie nagrań: ${describe(e)}")
            if (token == searchToken) {
                searching = false
                foundText = describe(e)
            }
        })
    }

    fun play(index: Int = selected) {
        val f = files.getOrNull(index) ?: return
        if (!connected) return
        selected = index
        playFile = f
        playAt(0)
    }

    fun playAt(offset: Long) {
        val f = playFile ?: return
        val off = offset.coerceIn(0, maxOf(0, f.seconds - 1))
        playOffset = off
        startStream(addr!!.playbackUrl(addSeconds(f.start, off), f.end), PlayerMode.PLAYBACK)
    }

    /** Pozycja odtwarzania w sekundach od początku pliku. */
    fun playPosition(): Long {
        val t0 = firstFrameAt ?: return playOffset
        return playOffset + (SystemClock.elapsedRealtime() - t0) / 1000
    }

    // ================================================================ pobieranie
    var downloadTree by mutableStateOf(prefs.getString("download_tree", null)?.let { Uri.parse(it) })
        private set
    var download by mutableStateOf<DownloadState?>(null)
        private set
    var downloadInfo by mutableStateOf("")
        private set
    private val cancelFlag = AtomicBoolean(false)

    fun setDownloadFolder(uri: Uri) {
        downloadTree = uri
        prefs.edit().putString("download_tree", uri.toString()).apply()
    }

    fun downloadFolderLabel(): String {
        val uri = downloadTree ?: return "nie wybrano"
        val seg = Uri.decode(uri.lastPathSegment ?: uri.toString())
        return seg.replace("primary:", "Pamięć wewnętrzna/")
    }

    fun channelFolder(): String {
        val title = snapshot?.section("ChannelTitle")?.get("ChannelTitle[$ch].Name") ?: ""
        return folderName(title, addr?.host ?: "Kamera")
    }

    fun cancelDownload() = cancelFlag.set(true)

    fun downloadChecked() {
        val list = checked.sorted().mapNotNull { files.getOrNull(it) }
        val tree = downloadTree
        if (list.isEmpty() || !connected || download != null) return
        if (tree == null) {
            downloadInfo = "Wybierz folder."
            return
        }
        val a = addr!!
        val ctx = getApplication<Application>()
        val folder = channelFolder()
        val dayName = playDay.toString()
        cancelFlag.set(false)
        val total = list.sumOf { it.size }.coerceAtLeast(1)
        download = DownloadState(0, total, 1, list.size)
        viewModelScope.launch {
            var done = 0L
            var lastUi = 0L
            val result = try {
                withContext(Dispatchers.IO) {
                    val root = DocumentFile.fromTreeUri(ctx, tree) ?: throw IOException("Brak dostępu do folderu.")
                    val dayDir = subDir(subDir(root, folder), dayName)
                    CameraApi(a).use { api ->
                        list.forEachIndexed { i, f ->
                            val name = recordingFilename(f.start, f.end, "dav")
                            dayDir.findFile("$name.part")?.delete()
                            val part = dayDir.createFile("application/octet-stream", "$name.part")
                                ?: throw IOException("Nie można utworzyć pliku $name")
                            try {
                                ctx.contentResolver.openOutputStream(part.uri, "w")!!.use { out ->
                                    api.download(f, out, { n ->
                                        done += n
                                        val now = SystemClock.elapsedRealtime()
                                        if (now - lastUi > 200) {
                                            lastUi = now
                                            download = DownloadState(done, total, i + 1, list.size)
                                        }
                                    }, { cancelFlag.get() })
                                }
                            } catch (e: Throwable) {
                                part.delete()
                                throw e
                            }
                            dayDir.findFile(name)?.delete()
                            if (!part.renameTo(name)) throw IOException("Nie można zmienić nazwy pliku $name")
                        }
                    }
                    Result.success(list.size)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Result.failure(e)
            }
            download = null
            result.fold({ saved ->
                AppLog.info("Pobrano $saved plików do $folder/$dayName")
                downloadInfo = "Pobrano $saved plik(ów) do: ${downloadFolderLabel()}/$folder/$dayName · odtwarzanie np. w VLC"
            }, { e ->
                if (e is DownloadCancelledException) {
                    AppLog.info("Pobieranie anulowane")
                    downloadInfo = "Pobieranie anulowane."
                } else {
                    AppLog.warn("Pobieranie: ${describe(e)}")
                    downloadInfo = "Błąd pobierania: ${describe(e)}"
                }
            })
        }
    }

    private fun subDir(parent: DocumentFile, name: String): DocumentFile =
        parent.findFile(name)?.takeIf { it.isDirectory }
            ?: parent.createDirectory(name)
            ?: throw IOException("Nie można utworzyć folderu $name")

    override fun onCleared() {
        cancelFlag.set(true)
        playerInstance?.release()
        playerInstance = null
        super.onCleared()
    }

    companion object {
        const val HEARTBEAT_MS = 15_000L
        const val PING_MS = 4_000L
        private val HHMM = DateTimeFormatter.ofPattern("HH:mm")
    }
}
