package com.abelcrvg.newsrss.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily

/** Settings exposed to any screen through the theme, so the same dialog works everywhere. */
data class AppSettings(
    val themeMode: ThemeMode,
    val readerFontScale: Float,
    val fontFamilyName: String,
    val onChanged: (ThemeMode, Float, String) -> Unit
)

val LocalAppSettings = compositionLocalOf<AppSettings> {
    error("NewsRSSTheme must provide LocalAppSettings")
}

private val LightColors = lightColorScheme(
    primary = Color(0xFF155EEF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE7FF),
    onPrimaryContainer = Color(0xFF001A41),
    secondary = Color(0xFF475467),
    background = Color(0xFFF7F9FC),
    surface = Color.White,
    surfaceVariant = Color(0xFFEFF2F7),
    outline = Color(0xFFD0D5DD)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFAFC6FF),
    onPrimary = Color(0xFF002E6B),
    primaryContainer = Color(0xFF174A9A),
    onPrimaryContainer = Color(0xFFDDE7FF),
    secondary = Color(0xFFB8C1D1),
    background = Color(0xFF0B1018),
    surface = Color(0xFF111827),
    surfaceVariant = Color(0xFF1B2433),
    outline = Color(0xFF344054)
)

@Composable
fun NewsRSSTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val store = remember { SettingsStore(context) }
    var themeMode by remember { mutableStateOf(store.themeMode) }
    var readerFontScale by remember { mutableStateOf(store.readerFontScale) }
    var fontFamilyName by remember { mutableStateOf(store.fontFamilyName) }

    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val family = storeFontFamily(fontFamilyName)
    val baseTypography = MaterialTheme.typography
    val typography = baseTypography.copy(
        displayLarge = baseTypography.displayLarge.copy(fontFamily = family),
        displayMedium = baseTypography.displayMedium.copy(fontFamily = family),
        displaySmall = baseTypography.displaySmall.copy(fontFamily = family),
        headlineLarge = baseTypography.headlineLarge.copy(fontFamily = family),
        headlineMedium = baseTypography.headlineMedium.copy(fontFamily = family),
        headlineSmall = baseTypography.headlineSmall.copy(fontFamily = family),
        titleLarge = baseTypography.titleLarge.copy(fontFamily = family),
        titleMedium = baseTypography.titleMedium.copy(fontFamily = family),
        titleSmall = baseTypography.titleSmall.copy(fontFamily = family),
        bodyLarge = baseTypography.bodyLarge.copy(fontFamily = family),
        bodyMedium = baseTypography.bodyMedium.copy(fontFamily = family),
        bodySmall = baseTypography.bodySmall.copy(fontFamily = family),
        labelLarge = baseTypography.labelLarge.copy(fontFamily = family),
        labelMedium = baseTypography.labelMedium.copy(fontFamily = family),
        labelSmall = baseTypography.labelSmall.copy(fontFamily = family)
    )

    MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors, typography = typography) {
        CompositionLocalProvider(
            LocalAppSettings provides AppSettings(themeMode, readerFontScale, fontFamilyName) { mode, scale, selectedFamily ->
                themeMode = mode
                readerFontScale = scale
                fontFamilyName = selectedFamily
                store.themeMode = mode
                store.readerFontScale = scale
                store.fontFamilyName = selectedFamily
            }
        ) {
            content()
        }
    }
}

private fun storeFontFamily(name: String) = when (name) {
    "Serif" -> FontFamily.Serif
    "Mono" -> FontFamily.Monospace
    "Cursive" -> FontFamily.Cursive
    else -> FontFamily.SansSerif
}
