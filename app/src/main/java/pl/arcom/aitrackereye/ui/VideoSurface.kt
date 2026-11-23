package pl.arcom.aitrackereye.ui

import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import pl.arcom.aitrackereye.MainViewModel
import pl.arcom.aitrackereye.PlayerMode

/** Obraz z odtwarzacza (wspólny dla podglądu na żywo i nagrań); [mode] = tryb, w którym ten widok pokazuje obraz. */
@OptIn(UnstableApi::class)
@Composable
fun VideoSurface(vm: MainViewModel, mode: PlayerMode, idleText: String, modifier: Modifier = Modifier) {
    val active = vm.playerMode == mode
    Box(modifier.background(Color(0xFF1E211D)).aspectRatio(16f / 9f), contentAlignment = Alignment.Center) {
        if (active) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                        player = vm.player
                    }
                },
                onRelease = { it.player = null },
                modifier = Modifier.fillMaxSize(),
            )
        }
        val text = if (active) vm.playerInfo else idleText
        if (text.isNotEmpty()) {
            Text(text, color = Color(0xFFE8E6DE), textAlign = TextAlign.Center, modifier = Modifier.padding(16.dp))
        }
    }
}
