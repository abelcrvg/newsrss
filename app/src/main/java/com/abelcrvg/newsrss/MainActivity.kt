package com.abelcrvg.newsrss

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.abelcrvg.newsrss.core.feed.FeedItem
import com.abelcrvg.newsrss.core.model.Article
import com.abelcrvg.newsrss.core.model.ArticleBlock
import com.abelcrvg.newsrss.core.model.FeedSource
import com.abelcrvg.newsrss.data.extraction.JsoupArticleExtractor
import com.abelcrvg.newsrss.data.feed.JsoupFeedReader
import com.abelcrvg.newsrss.ui.theme.NewsRSSTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { NewsRSSTheme { NewsRSSApp() } }
    }
}

private val defaultSource = FeedSource(
    id = "g1",
    name = "G1",
    siteUrl = "https://g1.globo.com"
)

@Composable
private fun NewsRSSApp() {
    var items by remember { mutableStateOf<List<FeedItem>>(emptyList()) }
    var article by remember { mutableStateOf<Article?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun refresh() {
        loading = true
        error = null
        scope.launch {
            JsoupFeedReader().read(defaultSource)
                .onSuccess { items = it.sortedByDescending { item -> item.publishedAt } }
                .onFailure { error = it.message ?: "Não foi possível atualizar as notícias." }
            loading = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Scaffold { padding ->
        if (article != null) {
            ReaderContent(article = article!!, onBack = { article = null })
        } else {
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("NewsRSS", style = MaterialTheme.typography.headlineLarge)
                        Text("Notícias mais recentes", style = MaterialTheme.typography.bodyMedium)
                    }
                    Button(onClick = { refresh() }, enabled = !loading) { Text("Atualizar") }
                }

                when {
                    loading && items.isEmpty() -> Column(modifier = Modifier.padding(20.dp)) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text("Buscando notícias...")
                    }
                    error != null && items.isEmpty() -> Column(modifier = Modifier.padding(20.dp)) {
                        Text(error!!, color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { refresh() }) { Text("Tentar novamente") }
                    }
                    else -> {
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 20.dp)) }
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp)
                        ) {
                            items(items, key = { it.id }) { item ->
                                NewsCard(item) {
                                    scope.launch {
                                        loading = true
                                        JsoupArticleExtractor().extract(item.url)
                                            .onSuccess { article = it }
                                            .onFailure { error = it.message ?: "Não foi possível abrir a notícia." }
                                        loading = false
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NewsCard(item: FeedItem, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(item.title, style = MaterialTheme.typography.titleLarge)
            item.summary?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 3)
            }
            Spacer(Modifier.height(10.dp))
            Text("G1  •  ${item.publishedAt ?: "agora"}", style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun ReaderContent(article: Article, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(12.dp))
        Button(onClick = onBack) { Text("← Voltar") }
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 14.dp)
        ) {
            item {
                Text(article.title, style = MaterialTheme.typography.headlineMedium)
                article.subtitle?.let { Text(it, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp)) }
                article.author?.let { Text("Por $it", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp)) }
            }
            items(article.blocks) { block ->
                when (block) {
                    is ArticleBlock.Paragraph -> Text(block.text, style = MaterialTheme.typography.bodyLarge)
                    is ArticleBlock.Heading -> Text(block.text, style = MaterialTheme.typography.titleLarge)
                    is ArticleBlock.Quote -> Text("“${block.text}”", style = MaterialTheme.typography.bodyLarge)
                    is ArticleBlock.ListBlock -> Column {
                        block.items.forEachIndexed { index, text -> Text(if (block.ordered) "${index + 1}. $text" else "• $text", style = MaterialTheme.typography.bodyLarge) }
                    }
                    is ArticleBlock.Image -> Column {
                        Text("Imagem", style = MaterialTheme.typography.labelMedium)
                        block.caption?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                    }
                }
            }
        }
    }
}
