package com.abelcrvg.newsrss

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.abelcrvg.newsrss.core.model.Article
import com.abelcrvg.newsrss.core.model.ArticleBlock
import com.abelcrvg.newsrss.data.extraction.JsoupArticleExtractor
import com.abelcrvg.newsrss.ui.theme.NewsRSSTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { NewsRSSTheme { NewsRSSApp() } }
    }
}

@Composable
private fun NewsRSSApp() {
    var url by remember { mutableStateOf("") }
    var article by remember { mutableStateOf<Article?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("NewsRSS", style = MaterialTheme.typography.headlineLarge)
            Text("Cole uma URL de notícia para testar o modo leitura.", style = MaterialTheme.typography.bodyLarge)

            if (article == null) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it; error = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("URL da notícia") },
                    singleLine = true
                )
                Button(
                    onClick = {
                        loading = true
                        error = null
                        scope.launch {
                            val result = JsoupArticleExtractor().extract(url.trim())
                            result.onSuccess { article = it }
                                .onFailure { error = it.message ?: "Não foi possível extrair a notícia." }
                            loading = false
                        }
                    },
                    enabled = url.isNotBlank() && !loading
                ) { Text("Ler notícia") }

                if (loading) CircularProgressIndicator()
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } else {
                ReaderContent(article!!)
                Button(onClick = { article = null }) { Text("← Outra notícia") }
            }
        }
    }
}

@Composable
private fun ReaderContent(article: Article) {
    Text(article.title, style = MaterialTheme.typography.headlineMedium)
    article.subtitle?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
    article.author?.let { Text("Por $it", style = MaterialTheme.typography.labelLarge) }

    article.blocks.forEach { block ->
        when (block) {
            is ArticleBlock.Paragraph -> Text(block.text, style = MaterialTheme.typography.bodyLarge)
            is ArticleBlock.Heading -> Text(block.text, style = MaterialTheme.typography.titleLarge)
            is ArticleBlock.Quote -> Text("“${block.text}”", style = MaterialTheme.typography.bodyLarge)
            is ArticleBlock.ListBlock -> block.items.forEachIndexed { index, item ->
                Text(if (block.ordered) "${index + 1}. $item" else "• $item", style = MaterialTheme.typography.bodyLarge)
            }
            is ArticleBlock.Image -> {
                Text("Imagem: ${block.url}", style = MaterialTheme.typography.labelMedium)
                block.caption?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
            }
        }
    }
}
