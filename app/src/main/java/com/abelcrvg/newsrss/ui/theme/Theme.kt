package com.abelcrvg.newsrss.ui.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme()
private val DarkColors = darkColorScheme()

@Composable
fun NewsRSSTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val store = remember { SettingsStore(context) }
    var darkMode by remember { mutableStateOf(store.darkMode) }
    var fontScale by remember { mutableStateOf(store.fontScale) }
    var fontFamilyName by remember { mutableStateOf(store.fontFamilyName) }
    val baseDensity = LocalDensity.current
    val density = Density(baseDensity.density, fontScale)
    val family = storeFontFamily(fontFamilyName)
    val baseTypography = if (darkMode) MaterialTheme.typography else MaterialTheme.typography
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
    androidx.compose.runtime.CompositionLocalProvider(LocalDensity provides density) {
        MaterialTheme(colorScheme = if (darkMode) DarkColors else LightColors, typography = typography) {
            Box(Modifier.fillMaxSize()) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
                FloatingActionButton(
                    onClick = {},
                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
                ) {
                    NewsRSSSettingsButton(darkMode, fontScale, fontFamilyName) { dark, scale, selectedFamily ->
                        darkMode = dark
                        fontScale = scale
                        fontFamilyName = selectedFamily
                        store.darkMode = dark
                        store.fontScale = scale
                        store.fontFamilyName = selectedFamily
                    }
                }
            }
        }
    }
}

private fun storeFontFamily(name: String) = when (name) {
    "Serif" -> androidx.compose.ui.text.font.FontFamily.Serif
    "Mono" -> androidx.compose.ui.text.font.FontFamily.Monospace
    "Cursive" -> androidx.compose.ui.text.font.FontFamily.Cursive
    else -> androidx.compose.ui.text.font.FontFamily.SansSerif
}
