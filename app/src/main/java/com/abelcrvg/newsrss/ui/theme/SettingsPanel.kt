package com.abelcrvg.newsrss.ui.theme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Drop this into any TopAppBar actions block; settings state comes from NewsRSSTheme. */
@Composable
fun NewsRSSSettingsButton() {
    val settings = LocalAppSettings.current
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) { Text("⚙", fontSize = 22.sp) }
    if (open) {
        NewsRSSSettingsDialog(settings) { mode, scale, family ->
            settings.onChanged(mode, scale, family)
            open = false
        }
    }
}

@Composable
private fun NewsRSSSettingsDialog(settings: AppSettings, onDismiss: () -> Unit) {
    var mode by remember { mutableStateOf(settings.themeMode) }
    var scale by remember { mutableStateOf(settings.readerFontScale) }
    var family by remember { mutableStateOf(settings.fontFamilyName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Configurações") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Tema")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ThemeMode.entries.forEach { option ->
                        FilterChip(
                            selected = mode == option,
                            onClick = { mode = option; settings.onChanged(mode, scale, family) },
                            label = { Text(themeLabel(option)) }
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text("Tamanho do texto do leitor: ${scaleLabel(scale)}")
                Slider(
                    value = scale,
                    onValueChange = { scale = it; settings.onChanged(mode, scale, family) },
                    valueRange = 0.85f..1.5f,
                    steps = 12
                )
                Text("Tipo de fonte")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Sans", "Serif", "Mono", "Cursive").forEach { option ->
                        FilterChip(
                            selected = family == option,
                            onClick = { family = option; settings.onChanged(mode, scale, family) },
                            label = { Text(option, fontFamily = previewFont(option)) }
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text("As alterações são salvas automaticamente.", fontSize = 12.sp)
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("Concluir") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Fechar") } }
    )
}

private fun themeLabel(mode: ThemeMode) = when (mode) {
    ThemeMode.SYSTEM -> "Sistema"
    ThemeMode.LIGHT -> "Claro"
    ThemeMode.DARK -> "Escuro"
}

private fun scaleLabel(value: Float) = when {
    value < 0.95f -> "Pequena"
    value < 1.08f -> "Normal"
    value < 1.25f -> "Grande"
    else -> "Muito grande"
}

private fun previewFont(name: String): FontFamily = when (name) {
    "Serif" -> FontFamily.Serif
    "Mono" -> FontFamily.Monospace
    "Cursive" -> FontFamily.Cursive
    else -> FontFamily.SansSerif
}
