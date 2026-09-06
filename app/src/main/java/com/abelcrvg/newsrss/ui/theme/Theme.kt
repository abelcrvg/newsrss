package com.abelcrvg.newsrss.ui.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.material3.FloatingActionButton

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
    val typography = MaterialTheme.typography.copy(
        displayLarge = MaterialTheme.typography.displayLarge.copy(fontFamily = storeFontFamily(fontFamilyName)),
        displayMedium = MaterialTheme.typography.displayMedium.copy(fontFamily = storeFontFamily(fontFamilyName)),
        displaySmall = MaterialTheme.typography.displaySmall.copy(fontFamily = storeFontFamily(fontFamilyName)),
        headlineLarge = MaterialTheme.typography.headlineLarge.copy(fontFamily = storeFontFamily(fontFamilyName)),
        headlineMedium = MaterialTheme.typography.headlineMedium.copy(fontFamily = storeFontFamily(fontFamilyName)),
        headlineSmall = MaterialTheme.typography.headlineSmall.copy(fontFamily = storeFontFamily(fontFamilyName)),
        titleLarge = MaterialTheme.typography.titleLarge.copy(fontFamily = storeFontFamily(fontFamilyName)),
        titleMedium = MaterialTheme.typography.titleMedium.copy(fontFamily = storeFontFamily(fontFamilyName)),
        titleSmall = MaterialTheme.typography.titleSmall.copy(fontFamily = storeFontFamily(fontFamilyName)),
        bodyLarge = MaterialTheme.typography.bodyLarge.copy(fontFamily = storeFontFamily(fontFamilyName)),
        bodyMedium = MaterialTheme.typography.bodyMedium.copy(fontFamily = storeFontFamily(fontFamilyName)),
        bodySmall = MaterialTheme.typography.bodySmall.copy(fontFamily = storeFontFamily(fontFamilyName)),
        labelLarge = MaterialTheme.typography.labelLarge.copy(fontFamily = storeFontFamily(fontFamilyName)),
        labelMedium = MaterialTheme.typography.labelMedium.copy(fontFamily = storeFontFamily(fontFamilyName)),
        labelSmall = MaterialTheme.typography.labelSmall.copy(fontFamily = storeFontFamily(fontFamilyName))
    )
    androidx.compose.runtime.CompositionLocalProvider(LocalDensity provides density) {
        MaterialTheme(colorScheme = if (darkMode) DarkColors else LightColors, typography = typography) {
            Box(Modifier.fillMaxSize()) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
                FloatingActionButton(onClick = {}, modifier = Modifier.padding(16.dp)) {
                    NewsRSSSettingsButton(darkMode, fontScale, fontFamilyName) { dark, scale, family ->
                        darkMode = dark
                        fontScale = scale
                        fontFamilyName = family
                        store.darkMode = dark
                        store.fontScale = scale
                        store.fontFamilyName = family
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
