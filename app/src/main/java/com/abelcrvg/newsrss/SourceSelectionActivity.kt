package com.abelcrvg.newsrss

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.abelcrvg.newsrss.core.model.FeedSource
import com.abelcrvg.newsrss.core.source.SourceRegistry
import com.abelcrvg.newsrss.data.source.SourceStore
import com.abelcrvg.newsrss.ui.theme.NewsRSSTheme

class SourceSelectionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sourceStore = SourceStore(applicationContext)
        if (sourceStore.hasSavedSelection()) {
            openNews()
            return
        }

        setContent {
            NewsRSSTheme {
                SourceSelectionScreen { selected ->
                    sourceStore.save(selected)
                    openNews()
                }
            }
        }
    }

    private fun openNews() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}

@Composable
private fun SourceSelectionScreen(onContinue: (List<FeedSource>) -> Unit) {
    val defaults = remember { SourceRegistry.defaultSources }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var error by remember { mutableStateOf<String?>(null) }

    fun toggle(id: String) {
        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
        error = null
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(
                Modifier.padding(horizontal = 20.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Escolha suas fontes", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Selecione os sites que você quer acompanhar. O NewsRSS só vai atualizar as fontes escolhidas, deixando a abertura mais rápida.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { selectedIds = defaults.map { it.id }.toSet(); error = null }) { Text("Selecionar todas") }
                    TextButton(onClick = { selectedIds = emptySet(); error = null }) { Text("Limpar") }
                }
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(defaults, key = { it.id }) { source ->
                    SourceSelectionRow(source, source.id in selectedIds) { toggle(source.id) }
                }
            }

            Column(
                Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    if (selectedIds.isEmpty()) "Nenhuma fonte selecionada" else "${selectedIds.size} fontes selecionadas",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        if (selectedIds.isEmpty()) {
                            error = "Escolha pelo menos uma fonte para continuar."
                        } else {
                            onContinue(defaults.filter { it.id in selectedIds }.map { it.copy(enabled = true) })
                        }
                    }
                ) { Text("Continuar") }
            }
        }
    }
}

@Composable
private fun SourceSelectionRow(source: FeedSource, selected: Boolean, onToggle: () -> Unit) {
    Card(onClick = onToggle, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = selected, onCheckedChange = { onToggle() })
            Column(Modifier.weight(1f)) {
                Text(source.name, style = MaterialTheme.typography.titleMedium)
                Text(source.category.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
