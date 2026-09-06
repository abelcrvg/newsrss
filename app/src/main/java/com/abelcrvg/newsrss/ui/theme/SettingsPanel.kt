package com.abelcrvg.newsrss.ui.theme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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

@Composable
fun NewsRSSSettingsButton() {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) { Text("⚙", fontSize = 24.sp) }
    if (open) NewsRSSSettingsDialog(onDismiss = { open = false })
}

@Composable
private fun NewsRSSSettingsDialog(onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val store = remember { SettingsStore(context) }
    var dark by remember { mutableStateOf(store.darkMode) }
    var scale by remember { mutableStateOf(store.fontScale) }
    var family by remember { mutableStateOf(store.fontFamilyName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Configurações") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text("Modo escuro")
                        Text("Usar tema escuro em todo o aplicativo", fontSize = 13.sp)
                    }
                    Switch(checked = dark, onCheckedChange = { dark = it; store.darkMode = it })
                }
                Spacer(Modifier.height(4.dp))
                Text("Tamanho da fonte: ${scaleLabel(scale)}")
                Slider(
                    value = scale,
                    onValueChange = { scale = it; store.fontScale = it },
                    valueRange = 0.85f..1.35f,
                    steps = 9
                )
                Text("Tipo de fonte")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Sans", "Serif", "Mono", "Cursive").forEach { option ->
                        FilterChip(selected = family == option, onClick = { family = option; store.fontFamilyName = option }, label = { Text(option, fontFamily = previewFont(option)) })
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text("As alterações são salvas automaticamente e continuam após fechar o aplicativo.", fontSize = 12.sp)
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("Concluir") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Fechar") } }
    )
}

private fun scaleLabel(value: Float): String = when {
    value < 0.95f -> "Pequena"
    value < 1.08f -> "Normal"
    value < 1.22f -> "Grande"
    else -> "Muito grande"
}

private fun previewFont(name: String): FontFamily = when (name) {
    "Serif" -> FontFamily.Serif
    "Mono" -> FontFamily.Monospace
    "Cursive" -> FontFamily.Cursive
    else -> FontFamily.SansSerif
}
