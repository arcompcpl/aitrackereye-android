package pl.arcom.aitrackereye.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import pl.arcom.aitrackereye.BuildConfig
import pl.arcom.aitrackereye.MainViewModel
import pl.arcom.aitrackereye.PlayerMode
import pl.arcom.aitrackereye.StripState
import pl.arcom.aitrackereye.UiDialog
import pl.arcom.aitrackereye.ui.tabs.*
import pl.arcom.aitrackereye.util.AppLog

enum class AppTab(val title: String, val icon: ImageVector) {
    RECORD("Nagrywanie", Icons.Default.FiberManualRecord),
    SCHEDULE("Harmonogram", Icons.Default.CalendarViewWeek),
    STORAGE("Pamięć", Icons.Default.SdCard),
    ENCODE("Obraz", Icons.Default.Tune),
    TIME("Data i czas", Icons.Default.Schedule),
    PLAYBACK("Odtwarzanie", Icons.Default.VideoLibrary),
    LIVE("Podgląd", Icons.Default.Videocam),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    var selectedTab by rememberSaveable { mutableStateOf(AppTab.RECORD) }
    var menuOpen by remember { mutableStateOf(false) }
    var aboutOpen by remember { mutableStateOf(false) }
    var connExpanded by rememberSaveable { mutableStateOf(true) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {
        if (it != null) vm.exportConfig(it)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        if (it != null) vm.importConfig(it)
    }

    // po połączeniu zwijamy kartę połączenia (więcej miejsca na telefonie)
    LaunchedEffect(vm.connected) { if (vm.connected) connExpanded = false }

    fun shareFile(file: java.io.File?, subject: String) {
        if (file == null || !file.exists()) {
            vm.showInfo(subject, "Plik jeszcze nie istnieje – najpierw połącz się z kamerą.")
            return
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(send, subject))
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Brand.Cream)) {
        val wide = maxWidth >= 840.dp          // tablet poziomo / duży tablet
        val tabs = if (wide) AppTab.entries.filter { it != AppTab.LIVE } else AppTab.entries.toList()
        val tab = if (selectedTab in tabs) selectedTab else AppTab.RECORD

        // zakładka Odtwarzanie i podgląd na żywo nie działają jednocześnie (limit strumieni RTSP)
        LaunchedEffect(tab) {
            if (tab == AppTab.PLAYBACK && vm.playerMode == PlayerMode.LIVE) vm.stopPlayer()
            if (tab != AppTab.PLAYBACK && tab != AppTab.LIVE && !wide && vm.playerMode != PlayerMode.NONE) vm.stopPlayer()
        }

        Scaffold(
            containerColor = Brand.Cream,
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text("AiTrackerEye", fontWeight = FontWeight.Bold, color = Color.White)
                            Spacer(Modifier.width(6.dp))
                            Text("Konfigurator", color = Brand.Orange, fontWeight = FontWeight.Bold)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Brand.Dark,
                        titleContentColor = Color.White, actionIconContentColor = Color.White),
                    actions = {
                        if (wide) Text("wersja ${BuildConfig.VERSION_NAME}", color = Brand.Sage, fontSize = 12.sp,
                            modifier = Modifier.padding(end = 8.dp))
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, "Menu") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text("Eksport konfiguracji…") }, enabled = vm.connected && !vm.busy,
                                leadingIcon = { Icon(Icons.Default.Upload, null) },
                                onClick = { menuOpen = false; exportLauncher.launch(vm.exportFileName()) })
                            DropdownMenuItem(text = { Text("Import konfiguracji…") }, enabled = vm.connected && !vm.busy,
                                leadingIcon = { Icon(Icons.Default.Download, null) },
                                onClick = { menuOpen = false; importLauncher.launch(arrayOf("application/json", "text/*", "*/*")) })
                            HorizontalDivider()
                            DropdownMenuItem(text = { Text("Wyślij diagnostykę") },
                                leadingIcon = { Icon(Icons.Default.BugReport, null) },
                                onClick = { menuOpen = false; shareFile(AppLog.diagnosticFile, "AiTrackerEye – diagnostyka") })
                            DropdownMenuItem(text = { Text("Wyślij dziennik") },
                                leadingIcon = { Icon(Icons.Default.Description, null) },
                                onClick = { menuOpen = false; shareFile(AppLog.logFile, "AiTrackerEye – dziennik") })
                            HorizontalDivider()
                            DropdownMenuItem(text = { Text("O programie") },
                                leadingIcon = { Icon(Icons.Default.Info, null) },
                                onClick = { menuOpen = false; aboutOpen = true })
                        }
                    },
                )
            },
        ) { pad ->
            Column(Modifier.padding(pad).fillMaxSize()) {
                ConnectionCard(vm, wide, connExpanded) { connExpanded = it }
                StatusStrip(vm)
                if (wide) {
                    Row(Modifier.fillMaxSize()) {
                        NavigationRail(containerColor = Brand.Panel) {
                            tabs.forEach { t ->
                                NavigationRailItem(selected = tab == t, onClick = { selectedTab = t },
                                    icon = { Icon(t.icon, null) }, label = { Text(t.title, fontSize = 11.sp) })
                            }
                        }
                        Box(Modifier.weight(1f).fillMaxHeight()) { TabContent(vm, tab) }
                        if (tab != AppTab.PLAYBACK) {
                            LivePanel(vm, Modifier.width(380.dp).fillMaxHeight().padding(10.dp))
                        }
                    }
                } else {
                    ScrollableTabRow(selectedTabIndex = tabs.indexOf(tab), containerColor = Brand.Panel,
                        edgePadding = 4.dp, contentColor = Brand.Orange) {
                        tabs.forEach { t ->
                            Tab(selected = tab == t, onClick = { selectedTab = t },
                                text = { Text(t.title) }, icon = { Icon(t.icon, null, Modifier.size(20.dp)) },
                                selectedContentColor = Brand.Orange, unselectedContentColor = Brand.Muted)
                        }
                    }
                    Box(Modifier.weight(1f).fillMaxWidth()) { TabContent(vm, tab) }
                }
            }
        }
    }

    Dialogs(vm)
    if (aboutOpen) {
        AlertDialog(
            onDismissRequest = { aboutOpen = false },
            confirmButton = { TextButton(onClick = { aboutOpen = false }) { Text("OK") } },
            title = { Text("AiTrackerEye Konfigurator") },
            text = {
                Text("wersja ${BuildConfig.VERSION_NAME} (Android)\n\n" +
                    "Konfiguracja kamery do wideorejestracji: nagrywanie, harmonogram, pamięć, obraz, data i czas, " +
                    "odtwarzanie i pobieranie nagrań.\n\nARCOM · www.arcom.pc.pl\nTelefony i tablety z Androidem 7.0 lub nowszym")
            },
        )
    }
}

@Composable
private fun TabContent(vm: MainViewModel, tab: AppTab) {
    val enabled = vm.connected && !vm.busy
    val snap = vm.snapshot
    when (tab) {
        AppTab.RECORD -> RecordTab(vm, snap, enabled)
        AppTab.SCHEDULE -> ScheduleTab(vm, snap, enabled)
        AppTab.STORAGE -> StorageTab(vm, snap, enabled)
        AppTab.ENCODE -> EncodeTab(vm, snap, enabled)
        AppTab.TIME -> TimeTab(vm, snap, enabled)
        AppTab.PLAYBACK -> PlaybackTab(vm, enabled)
        AppTab.LIVE -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
            LivePanel(vm, Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun LivePanel(vm: MainViewModel, modifier: Modifier = Modifier) {
    val live = vm.playerMode == PlayerMode.LIVE
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Podgląd na żywo", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Brand.Dark)
        VideoSurface(vm, PlayerMode.LIVE, "Podgląd wyłączony", Modifier.fillMaxWidth())
        Dropdown(listOf(0 to "Strumień główny", 1 to "Podstrumień"), vm.liveSubtype, {
            vm.liveSubtype = it
            if (live) vm.startLive()
        }, Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = vm::startLive, modifier = Modifier.weight(1f)) { Text("Uruchom") }
            OutlinedButton(onClick = vm::stopPlayer, enabled = live, modifier = Modifier.weight(1f)) { Text("Zatrzymaj") }
        }
        Hint(if (live) vm.videoSize else "Podstrumień zużywa mniej transferu – zalecany przez Wi-Fi/LTE.")
    }
}

@Composable
private fun StatusStrip(vm: MainViewModel) {
    val bg = when (vm.stripState) {
        StripState.OK -> Brand.OkBg
        StripState.OFF -> Brand.OffBg
        StripState.BUSY -> Brand.BusyBg
    }
    Row(Modifier.fillMaxWidth().background(bg).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (vm.stripState == StripState.BUSY) {
            CircularProgressIndicator(Modifier.size(14.dp), color = Color.White, strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(vm.stripText, color = Color.White, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f))
        if (vm.pingText.isNotEmpty()) Text(vm.pingText, color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
    }
}

@Composable
private fun ConnectionCard(vm: MainViewModel, wide: Boolean, expanded: Boolean, onExpand: (Boolean) -> Unit) {
    Surface(color = Brand.Panel, shadowElevation = 1.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            if (!expanded) ConnectionSummary(vm, onExpand) else ConnectionForm(vm, wide, onExpand)
        }
    }
}

@Composable
private fun ConnectionSummary(vm: MainViewModel, onExpand: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Videocam, null, tint = Brand.Orange)
        Spacer(Modifier.width(8.dp))
        Text("${vm.host.ifEmpty { "—" }} · ${vm.user} · kanał ${vm.channel}", Modifier.weight(1f),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        TextButton(onClick = { onExpand(true) }) { Text("Zmień") }
        if (vm.connected || vm.lost) OutlinedButton(onClick = vm::disconnect) { Text("Rozłącz") }
        else Button(onClick = vm::connect, enabled = !vm.busy) { Text("Połącz") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConnectionForm(vm: MainViewModel, wide: Boolean, onExpand: (Boolean) -> Unit) {
    var showPw by remember { mutableStateOf(false) }
    Column {
        val fieldW = if (wide) Modifier.width(170.dp) else Modifier.fillMaxWidth()
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(vm.host, vm::updateHost, label = { Text("Adres IP") }, singleLine = true,
                placeholder = { Text("np. 192.168.1.108") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                modifier = if (wide) Modifier.width(200.dp) else Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = if (wide) Modifier else Modifier.fillMaxWidth()) {
                OutlinedTextField(vm.httpPort, vm::updateHttpPort, label = { Text("Port HTTP") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = if (wide) Modifier.width(110.dp) else Modifier.weight(1f))
                OutlinedTextField(vm.rtspPort, vm::updateRtspPort, label = { Text("Port RTSP") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = if (wide) Modifier.width(110.dp) else Modifier.weight(1f))
                OutlinedTextField(vm.channel, vm::updateChannel, label = { Text("Kanał") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = if (wide) Modifier.width(90.dp) else Modifier.weight(0.8f))
            }
            OutlinedTextField(vm.user, vm::updateUser, label = { Text("Login") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false,
                    capitalization = KeyboardCapitalization.None),
                modifier = fieldW)
            OutlinedTextField(vm.password, vm::updatePassword, label = { Text("Hasło") }, singleLine = true,
                placeholder = { Text("nie jest zapisywane") },
                visualTransformation = if (showPw) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go,
                    autoCorrectEnabled = false, capitalization = KeyboardCapitalization.None),
                keyboardActions = KeyboardActions(onGo = { vm.connect() }),
                trailingIcon = {
                    IconButton(onClick = { showPw = !showPw }) {
                        Icon(if (showPw) Icons.Default.VisibilityOff else Icons.Default.Visibility, "Pokaż hasło")
                    }
                },
                modifier = if (wide) Modifier.width(200.dp) else Modifier.fillMaxWidth())
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
            CheckRow("HTTPS", vm.https, vm::updateHttps)
            CheckRow("Sprawdzaj certyfikat", vm.verifyTls, vm::updateVerifyTls, enabled = vm.https)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (vm.connected) TextButton(onClick = { onExpand(false) }) { Text("Zwiń") }
                OutlinedButton(onClick = vm::disconnect, enabled = vm.connected || vm.lost) { Text("Rozłącz") }
                Button(onClick = vm::connect, enabled = !vm.busy) { Text("Połącz") }
            }
        }
    }
}

@Composable
private fun Dialogs(vm: MainViewModel) {
    when (val d = vm.dialog) {
        null -> {}
        is UiDialog.Message -> AlertDialog(
            onDismissRequest = { vm.dialog = null },
            icon = if (d.isError) ({ Icon(Icons.Default.ErrorOutline, null, tint = Brand.OffBg) }) else null,
            title = { Text(d.title) },
            text = { Text(d.text) },
            confirmButton = { TextButton(onClick = { vm.dialog = null }) { Text("OK") } },
        )
        is UiDialog.Confirm -> AlertDialog(
            onDismissRequest = { vm.dialog = null },
            title = { Text(d.title) },
            text = { Text(d.text) },
            confirmButton = {
                Button(onClick = { vm.dialog = null; d.onYes() },
                    colors = if (d.danger) ButtonDefaults.buttonColors(containerColor = Brand.OffBg) else ButtonDefaults.buttonColors()) {
                    Text(d.yes)
                }
            },
            dismissButton = { TextButton(onClick = { vm.dialog = null }) { Text("Anuluj") } },
        )
    }
}
