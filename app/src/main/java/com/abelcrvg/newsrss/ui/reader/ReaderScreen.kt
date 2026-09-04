package com.abelcrvg.newsrss.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.abelcrvg.newsrss.core.model.Article
import com.abelcrvg.newsrss.core.model.ArticleBlock

@Composable
fun ReaderScreen(
    article: Article?,
    loading: Boolean,
    error: String?,
    modifier: Modifier = Modifier
) {
    when {
        loading -> Column(
            modifier = modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center
        ) {
            CircularProgressIndicator(modifier = Modifier.padding(24.dp))
        }

        error != null -> Column(modifier = modifier.padding(24.dp)) {
            Text("Não foi possível abrir a notícia", style = MaterialTheme.typography.titleLarge)
            Text(error, modifier = Modifier.padding(top = 8.dp))
        }

        article != null -> ArticleContent(article)
    }
}

@Composable
private fun ArticleContent(article: Article) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(article.title, style = MaterialTheme.typography.headlineLarge)
        }

        article.subtitle?.takeIf { it != article.title }?.let { subtitle ->
            item {
                Text(subtitle, style = MaterialTheme.typography.titleMedium)
            }
        }

        article.author?.let { author ->
            item {
                Text("Por $author", style = MaterialTheme.typography.labelLarge)
            }
        }

        items(article.blocks) { block ->
            when (block) {
                is ArticleBlock.Paragraph -> Text(
                    block.text,
                    style = MaterialTheme.typography.bodyLarge
                )
                is ArticleBlock.Heading -> Text(
                    block.text,
                    style = if (block.level <= 2) MaterialTheme.typography.titleLarge
                    else MaterialTheme.typography.titleMedium
                )
                is ArticleBlock.Quote -> Text(
                    "“${block.text}”",
                    style = MaterialTheme.typography.bodyLarge
                )
                is ArticleBlock.ListBlock -> Column {
                    block.items.forEachIndexed { index, item ->
                        Text(
                            if (block.ordered) "${index + 1}. $item" else "• $item",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                }
                is ArticleBlock.Image -> {
                    Text(
                        block.altText ?: block.caption ?: "Imagem",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }
    }
}
