package pl.arcom.aitrackereye.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Kolory jak w wersji Windows (theme.py). */
object Brand {
    val Dark = Color(0xFF353D32)
    val Orange = Color(0xFFDF6810)
    val OrangeTint = Color(0xFFFBE6D6)
    val Cream = Color(0xFFF6F5F0)
    val Panel = Color(0xFFFFFFFF)
    val Border = Color(0xFFDCD9CD)
    val Text = Color(0xFF292B28)
    val Muted = Color(0xFF62675D)
    val Sage = Color(0xFFB0C78F)
    val OkBg = Color(0xFF2F8A3A)
    val OffBg = Color(0xFFC62828)
    val BusyBg = Color(0xFFB5651D)
    val RecRegular = Color(0xFF4CAF3A)
    val RecOff = Color(0xFFBDBDB5)
}

private val Colors = lightColorScheme(
    primary = Brand.Orange,
    onPrimary = Color.White,
    primaryContainer = Brand.OrangeTint,
    onPrimaryContainer = Brand.Text,
    secondary = Brand.Dark,
    onSecondary = Color.White,
    secondaryContainer = Brand.OrangeTint,
    onSecondaryContainer = Brand.Text,
    background = Brand.Cream,
    onBackground = Brand.Text,
    surface = Brand.Panel,
    onSurface = Brand.Text,
    surfaceVariant = Brand.Cream,
    onSurfaceVariant = Brand.Muted,
    surfaceContainer = Brand.Panel,
    surfaceContainerLow = Brand.Panel,
    surfaceContainerHigh = Brand.Panel,
    surfaceContainerHighest = Brand.Cream,
    outline = Brand.Border,
    outlineVariant = Brand.Border,
    error = Brand.OffBg,
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, content = content)
}
