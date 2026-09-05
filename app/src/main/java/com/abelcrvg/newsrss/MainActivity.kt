package com.abelcrvg.newsrss

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.abelcrvg.newsrss.core.feed.FeedItem
import com.abelcrvg.newsrss.core.model.Article
import com.abelcrvg.newsrss.core.model.ArticleBlock
import com.abelcrvg.newsrss.core.model.FeedSource
import com.abelcrvg.newsrss.core.model.NewsCategory
import com.abelcrvg.newsrss.core.source.SourceRegistry
import com.abelcrvg.newsrss.data.extraction.JsoupArticleExtractor
import com.abelcrvg.newsrss.data.feed.SmartFeedReader
import com.abelcrvg.newsrss.data.source.SourceStore
import com.abelcrvg.newsrss.ui.theme.NewsRSSTheme
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { NewsRSSTheme { NewsRSSApp() } }
    }
}

@Composable
private fun NewsRSSApp() {
    val context = LocalContext.current
    val sourceStore = remember { SourceStore(context.applicationContext) }
    var sources by remember { mutableStateOf(sourceStore.load(SourceRegistry.defaultSources)) }
    var items by remember { mutableStateOf<List<FeedItem>>(emptyList()) }
    var article by remember { mutableStateOf<Article?>(null) }
    var loading by remember { mutableStateOf(true) }
    var opening by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var urlInput by remember { mutableStateOf("") }
    var sourceError by remember { mutableStateOf<String?>(null) }
    var selectedCategory by remember { mutableStateOf<NewsCategory?>(null) }
    var manageSources by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    var returnIndex by remember { mutableIntStateOf(0) }
    var returnOffset by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    fun persist(newSources: List<FeedSource>) {
        sources = newSources
        sourceStore.save(newSources)
    }

    fun refresh() {
        loading = true
        error = null
        scope.launch {
            val results = coroutineScope {
                sources.filter { it.enabled }.map { source ->
                    async { source to SmartFeedReader().read(source) }
                }.awaitAll()
            }
            val successful = results.flatMap { (source, result) ->
                result.getOrElse { emptyList() }.map { it.copy(sourceId = source.id) }
            }
            val failures = results.filter { it.second.isFailure }
            items = successful.distinctBy { it.url }
                .sortedWith(compareByDescending<FeedItem> { it.publishedAt ?: Instant.EPOCH })
            error = when {
                successful.isEmpty() -> failures.firstOrNull()?.second?.exceptionOrNull()?.message
                    ?: "Nenhuma fonte conseguiu fornecer notícias."
                failures.isNotEmpty() -> "Algumas fontes não puderam ser atualizadas."
                else -> null
            }
            loading = false
        }
    }

    fun addSource() {
        sourceError = null
        val normalized = urlInput.trim().removeSuffix("/")
        val uri = runCatching { URI(normalized) }.getOrNull()
        if (uri == null || uri.scheme !in listOf("http", "https") || uri.host.isNullOrBlank()) {
            sourceError = "Digite uma URL válida, por exemplo: https://www.uol.com.br"
            return
        }
        val host = uri.host.removePrefix("www.")
        val id = "custom-${host.replace(Regex("[^a-zA-Z0-9]"), "-")}"
        val name = host.substringBefore('.').replaceFirstChar { it.uppercase() }
        if (sources.any { it.id == id }) {
            sourceError = "Essa fonte já está adicionada."
            return
        }
        persist(sources + FeedSource(id = id, name = name, siteUrl = normalized, category = NewsCategory.NEWS))
        urlInput = ""
        refresh()
    }

    val visibleItems = remember(items, sources, selectedCategory) {
        val category = selectedCategory
        if (category == null) items else {
            val sourceIds = sources.filter { it.category == category && it.enabled }.map { it.id }.toSet()
            items.filter { it.sourceId in sourceIds }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    if (article != null) {
        BackHandler { article = null }
        ReaderContent(article = article!!, onBack = { article = null })
        return
    }

    LaunchedEffect(article) {
        if (article == null && (returnIndex > 0 || returnOffset > 0)) {
            listState.scrollToItem(returnIndex, returnOffset)
        }
    }

    if (manageSources) {
        BackHandler { manageSources = false }
        SourceManager(
            sources = sources,
            urlInput = urlInput,
            onUrlChange = { urlInput = it },
            sourceError = sourceError,
            onAdd = { addSource() },
            onBack = { manageSources = false },
            onToggle = { source -> persist(sources.map { if (it.id == source.id) it.copy(enabled = !it.enabled) else it }) },
            onCategoryChange = { source, category -> persist(sources.map { if (it.id == source.id) it.copy(category = category) else it }) },
            onDelete = { source -> persist(sources.filterNot { it.id == source.id }); refresh() }
        )
        return
    }

    Scaffold { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("NewsRSS", style = MaterialTheme.typography.headlineLarge)
                        Text("${sources.count { it.enabled }} fontes ativas", style = MaterialTheme.typography.bodyMedium)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { manageSources = true }) { Text("Fontes") }
                        Button(onClick = { refresh() }, enabled = !loading && !opening) { Text("Atualizar") }
                    }
                }
                Spacer(Modifier.height(12.dp))
                CategoryFilter(selectedCategory) { selectedCategory = it }
            }
            when {
                loading && items.isEmpty() -> Column(modifier = Modifier.padding(20.dp)) {
                    CircularProgressIndicator(); Spacer(Modifier.height(12.dp)); Text("Atualizando todas as fontes...")
                }
                error != null && items.isEmpty() -> Column(modifier = Modifier.padding(20.dp)) {
                    Text(error!!, color = MaterialTheme.colorScheme.error); Spacer(Modifier.height(12.dp)); Button(onClick = { refresh() }) { Text("Tentar novamente") }
                }
                else -> {
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 20.dp)) }
                    if (opening) Row(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { CircularProgressIndicator(); Text("Abrindo notícia...") }
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(20.dp)) {
                        item {
                            val heading = selectedCategory?.label ?: "Principais e recentes"
                            Text(heading, style = MaterialTheme.typography.headlineSmall)
                        }
                        items(visibleItems, key = { it.id }) { item ->
                            NewsCard(item, sources) {
                                returnIndex = listState.firstVisibleItemIndex
                                returnOffset = listState.firstVisibleItemScrollOffset
                                opening = true
                                error = null
                                scope.launch {
                                    JsoupArticleExtractor().extract(item.url)
                                        .onSuccess { extracted -> article = extracted.copy(publishedAt = extracted.publishedAt ?: item.publishedAt) }
                                        .onFailure { error = it.message ?: "Não foi possível abrir a notícia." }
                                    opening = false
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
private fun CategoryFilter(selectedCategory: NewsCategory?, onSelected: (NewsCategory?) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CategoryButton("Todos", selectedCategory == null) { onSelected(null) }
        NewsCategory.entries.forEach { category -> CategoryButton(category.label, selectedCategory == category) { onSelected(category) } }
    }
}

@Composable
private fun CategoryButton(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) Button(onClick = onClick) { Text(label) } else OutlinedButton(onClick = onClick) { Text(label) }
}

@Composable
private fun SourceManager(
    sources: List<FeedSource>, urlInput: String, onUrlChange: (String) -> Unit, sourceError: String?, onAdd: () -> Unit,
    onBack: () -> Unit, onToggle: (FeedSource) -> Unit, onCategoryChange: (FeedSource, NewsCategory) -> Unit, onDelete: (FeedSource) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(14.dp))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Gerenciar fontes", style = MaterialTheme.typography.headlineMedium); OutlinedButton(onClick = onBack) { Text("Voltar") }
        }
        Spacer(Modifier.height(8.dp)); Text("Ative, desative ou altere a categoria de cada fonte.", style = MaterialTheme.typography.bodyMedium); Spacer(Modifier.height(14.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = urlInput, onValueChange = onUrlChange, modifier = Modifier.weight(1f), singleLine = true, label = { Text("Adicionar site") }, placeholder = { Text("https://exemplo.com") })
            Button(onClick = onAdd, modifier = Modifier.align(Alignment.CenterVertically)) { Text("Adicionar") }
        }
        sourceError?.let { Spacer(Modifier.height(8.dp)); Text(it, color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.height(14.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
            items(sources, key = { it.id }) { source ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(source.name, style = MaterialTheme.typography.titleMedium)
                            Text(source.siteUrl, style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(4.dp))
                            TextButton(onClick = {
                                val next = NewsCategory.entries[(NewsCategory.entries.indexOf(source.category) + 1) % NewsCategory.entries.size]
                                onCategoryChange(source, next)
                            }) { Text(source.category.label) }
                        }
                        Switch(checked = source.enabled, onCheckedChange = { onToggle(source) })
                        if (source.id.startsWith("custom-")) {
                            TextButton(onClick = { onDelete(source) }) { Text("Excluir") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NewsCard(item: FeedItem, sources: List<FeedSource>, onClick: () -> Unit) {
    val source = sources.firstOrNull { it.id == item.sourceId }
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column {
            item.imageUrl?.let { image -> AsyncImage(model = image, contentDescription = item.title, modifier = Modifier.fillMaxWidth().height(190.dp), contentScale = ContentScale.Crop) }
            Column(modifier = Modifier.padding(14.dp)) {
                Text(item.title, style = MaterialTheme.typography.titleLarge)
                item.summary?.takeIf { it.isNotBlank() }?.let { Spacer(Modifier.height(6.dp)); Text(it, maxLines = 3, style = MaterialTheme.typography.bodyMedium) }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(source?.name ?: "Fonte", style = MaterialTheme.typography.labelMedium)
                    item.publishedAt?.let { Text(publishedLabel(it), style = MaterialTheme.typography.labelMedium) }
                }
            }
        }
    }
}

@Composable
private fun ReaderContent(article: Article, onBack: () -> Unit) {
    Scaffold(topBar = {
        TopAppBar(title = { Text(article.title) }, navigationIcon = { TextButton(onClick = onBack) { Text("Voltar") } })
    }) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item { Text(article.title, style = MaterialTheme.typography.headlineMedium) }
            article.subtitle?.let { item { Text(it, style = MaterialTheme.typography.titleMedium) } }
            article.author?.let { item { Text("Por $it", style = MaterialTheme.typography.labelLarge) } }
            article.publishedAt?.let { item { Text(publishedLabel(it), style = MaterialTheme.typography.labelMedium) } }
            article.blocks.forEach { block ->
                item {
                    when (block) {
                        is ArticleBlock.Paragraph -> Text(block.text, style = MaterialTheme.typography.bodyLarge)
                        is ArticleBlock.Heading -> Text(block.text, style = if (block.level <= 2) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge)
                        is ArticleBlock.Image -> Column {
                            AsyncImage(model = block.url, contentDescription = block.altText ?: block.caption, modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp), contentScale = ContentScale.FillWidth)
                            block.caption?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                        is ArticleBlock.Quote -> Text("“${block.text}”${block.author?.let { " — $it" } ?: ""}", style = MaterialTheme.typography.bodyLarge)
                        is ArticleBlock.ListBlock -> Column {
                            block.items.forEachIndexed { index, text -> Text(if (block.ordered) "${index + 1}. $text" else "• $text", style = MaterialTheme.typography.bodyLarge) }
                        }
                    }
                }
            }
        }
    }
}

private fun publishedLabel(value: Instant): String {
    val local = value.atZone(ZoneId.systemDefault())
    return local.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.getDefault()))
}
