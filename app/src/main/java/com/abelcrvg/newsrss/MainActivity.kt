package com.abelcrvg.newsrss

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.abelcrvg.newsrss.core.feed.FeedItem
import com.abelcrvg.newsrss.core.model.Article
import com.abelcrvg.newsrss.core.model.ArticleBlock
import com.abelcrvg.newsrss.core.model.FeedSource
import com.abelcrvg.newsrss.core.model.NewsCategory
import com.abelcrvg.newsrss.core.model.SourceLanguage
import com.abelcrvg.newsrss.core.source.SourceRegistry
import com.abelcrvg.newsrss.data.background.NewsRefreshScheduler
import com.abelcrvg.newsrss.data.extraction.JsoupArticleExtractor
import com.abelcrvg.newsrss.data.feed.SmartFeedReader
import com.abelcrvg.newsrss.data.source.FeedCacheStore
import com.abelcrvg.newsrss.data.source.ReadArticleStore
import com.abelcrvg.newsrss.data.source.SavedArticleStore
import com.abelcrvg.newsrss.data.source.SourceStore
import com.abelcrvg.newsrss.data.translation.OnDeviceTranslator
import com.abelcrvg.newsrss.ui.theme.NewsRSSTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { NewsRSSTheme { NewsRSSApp() } }
    }
}

@Composable
private fun NewsRSSApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val appContext = context.applicationContext
    val sourceStore = remember { SourceStore(appContext) }
    val cacheStore = remember { FeedCacheStore(appContext) }
    val readStore = remember { ReadArticleStore(appContext) }
    val savedStore = remember { SavedArticleStore(appContext) }
    val scope = rememberCoroutineScope()

    var sources by remember { mutableStateOf<List<FeedSource>>(emptyList()) }
    var items by remember { mutableStateOf<List<FeedItem>>(emptyList()) }
    var readItems by remember { mutableStateOf<List<FeedItem>>(emptyList()) }
    var savedItems by remember { mutableStateOf<List<FeedItem>>(emptyList()) }
    var readUrls by remember { mutableStateOf<Set<String>>(emptySet()) }
    var savedUrls by remember { mutableStateOf<Set<String>>(emptySet()) }
    var initialized by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var opening by remember { mutableStateOf(false) }
    var refreshError by remember { mutableStateOf<String?>(null) }
    var currentSource by remember { mutableStateOf<String?>(null) }
    var completedSources by remember { mutableStateOf<Set<String>>(emptySet()) }
    var failedSources by remember { mutableStateOf<Set<String>>(emptySet()) }
    var newItemsCount by remember { mutableIntStateOf(0) }
    var selectedCategory by remember { mutableStateOf<NewsCategory?>(null) }
    var tab by remember { mutableIntStateOf(0) }
    var manageSources by remember { mutableStateOf(false) }
    var article by remember { mutableStateOf<Article?>(null) }
    var currentItem by remember { mutableStateOf<FeedItem?>(null) }
    var urlInput by remember { mutableStateOf("") }
    var sourceError by remember { mutableStateOf<String?>(null) }

    val listState = rememberLazyListState()
    val sourceById = remember(sources) { sources.associateBy { it.id } }
    val showScrollToTop by remember { derivedStateOf { listState.firstVisibleItemIndex >= 6 } }

    fun saveSources(value: List<FeedSource>) {
        sources = value
        scope.launch(Dispatchers.IO) { sourceStore.save(value) }
    }

    fun markRead(item: FeedItem) {
        if (item.url !in readUrls) {
            readUrls = readUrls + item.url
            readItems = (listOf(item) + readItems).distinctBy { it.url }.take(300)
            scope.launch(Dispatchers.IO) { readStore.markRead(item) }
        }
    }

    fun toggleSaved(item: FeedItem) {
        val shouldSave = item.url !in savedUrls
        savedUrls = if (shouldSave) savedUrls + item.url else savedUrls - item.url
        savedItems = if (shouldSave) {
            (listOf(item) + savedItems).distinctBy { it.url }.take(300)
        } else savedItems.filterNot { it.url == item.url }
        scope.launch(Dispatchers.IO) { savedStore.setSaved(item, shouldSave) }
    }

    fun refresh() {
        if (!initialized || refreshing) return
        refreshing = true
        refreshError = null
        currentSource = null
        completedSources = emptySet()
        failedSources = emptySet()
        newItemsCount = 0
        scope.launch {
            val enabled = sources.filter { it.enabled }
            if (enabled.isEmpty()) {
                refreshing = false
                refreshError = "Nenhuma fonte ativa."
                return@launch
            }
            val previousUrls = items.asSequence().map { it.url }.toHashSet()
            val reader = SmartFeedReader()
            val results = enabled.map { source ->
                async(Dispatchers.IO) {
                    val result = runCatching { reader.read(source) }.getOrElse { Result.failure(it) }
                    source.id to result.map { list ->
                        list.asSequence().map { it.copy(sourceId = source.id) }.take(80).toList()
                    }
                }
            }.awaitAll()

            val mergedIncoming = results.flatMap { it.second.getOrNull().orEmpty() }
            val failed = results.filter { it.second.isFailure }.mapTo(linkedSetOf()) { it.first }
            val completed = results.filter { it.second.isSuccess }.mapTo(linkedSetOf()) { it.first }
            val added = mergedIncoming.count { it.url !in previousUrls }
            val merged = mergeFeedItems(items, mergedIncoming).take(800)

            withContext(Dispatchers.Main.immediate) {
                items = merged
                newItemsCount = added
                completedSources = completed
                failedSources = failed
                currentSource = null
                refreshing = false
                refreshError = if (merged.isEmpty() && failed.isNotEmpty()) "Nenhuma fonte conseguiu fornecer notícias." else null
            }

            if (mergedIncoming.isNotEmpty()) {
                withContext(Dispatchers.IO) { cacheStore.merge(mergedIncoming) }
            }
        }
    }

    fun openItem(item: FeedItem) {
        currentItem = item
        markRead(item)
        opening = true
        refreshError = null
        scope.launch(Dispatchers.IO) {
            val extracted = JsoupArticleExtractor().extract(item.url)
            val translated = if (extracted.isSuccess && sourceById[item.sourceId]?.language == SourceLanguage.ENGLISH) {
                runCatching { OnDeviceTranslator(appContext).translateArticle(extracted.getOrThrow()) }.getOrNull()
            } else extracted.getOrNull()
            withContext(Dispatchers.Main.immediate) {
                if (translated != null) {
                    article = translated.copy(publishedAt = translated.publishedAt ?: item.publishedAt)
                } else {
                    refreshError = extracted.exceptionOrNull()?.message ?: "Não foi possível abrir a notícia."
                }
                opening = false
            }
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
        if (sources.any { it.siteUrl.equals(normalized, true) }) {
            sourceError = "Essa fonte já está adicionada."
            return
        }
        val host = uri.host.removePrefix("www.")
        val baseId = "custom-" + host.replace(Regex("[^a-zA-Z0-9]+"), "-").trim('-').lowercase(Locale.ROOT)
        val id = if (sources.none { it.id == baseId }) baseId else "$baseId-${normalized.hashCode().toUInt().toString(16)}"
        val name = host.substringBefore('.').replaceFirstChar { it.uppercase() }
        saveSources(sources + FeedSource(id, name, normalized, category = NewsCategory.NEWS))
        urlInput = ""
    }

    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) {
            InitialData(
                sourceStore.load(SourceRegistry.defaultSources),
                readStore.load(), readStore.loadItems(),
                savedStore.load(), savedStore.loadItems(), cacheStore.load()
            )
        }
        sources = loaded.sources
        readUrls = loaded.readUrls
        readItems = loaded.readItems.take(300)
        savedUrls = loaded.savedUrls
        savedItems = loaded.savedItems.take(300)
        items = loaded.items.take(800)
        initialized = true
        NewsRefreshScheduler.schedule(appContext)
        refresh()
    }

    if (article != null && currentItem != null) {
        BackHandler { article = null }
        ReaderContent(article!!, currentItem!!.url in savedUrls, { article = null }, { toggleSaved(currentItem!!) })
        return
    }

    if (manageSources) {
        BackHandler { manageSources = false }
        SourceManager(
            sources = sources,
            failedSources = failedSources,
            urlInput = urlInput,
            sourceError = sourceError,
            onUrlChange = { urlInput = it },
            onAdd = { addSource() },
            onBack = { manageSources = false },
            onToggle = { source -> saveSources(sources.map { if (it.id == source.id) it.copy(enabled = !it.enabled) else it }) },
            onCategoryChange = { source ->
                val next = NewsCategory.entries[(NewsCategory.entries.indexOf(source.category) + 1) % NewsCategory.entries.size]
                saveSources(sources.map { if (it.id == source.id) it.copy(category = next) else it })
            },
            onDelete = { source -> saveSources(sources.filterNot { it.id == source.id }) }
        )
        return
    }

    val unread = remember(items, readUrls) { items.filterNot { it.url in readUrls } }
    val filtered = remember(unread, selectedCategory, sourceById) {
        if (selectedCategory == null) unread else unread.filter { sourceById[it.sourceId]?.category == selectedCategory && sourceById[it.sourceId]?.enabled == true }
    }
    val displayItems = when (tab) { 1 -> readItems; 2 -> savedItems; else -> filtered }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar {
                NavigationBarItem(tab == 0, { tab = 0 }, icon = { Text("●") }, label = { Text("Notícias") })
                NavigationBarItem(tab == 1, { tab = 1 }, icon = { Text("✓") }, label = { Text("Lidas") })
                NavigationBarItem(tab == 2, { tab = 2 }, icon = { Text("★") }, label = { Text("Ler depois") })
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp, bottom = 96.dp)
            ) {
                item("top") {
                    HomeHeader(
                        loading = refreshing,
                        initialized = initialized,
                        newItemsCount = newItemsCount,
                        activeSources = sources.count { it.enabled },
                        currentSource = currentSource?.let { sourceById[it]?.name },
                        onSources = { manageSources = true },
                        onRefresh = { refresh() }
                    )
                }
                if (tab == 0) {
                    item("filters") { CategoryFilter(selectedCategory) { selectedCategory = it } }
                } else {
                    item("heading") {
                        Text(if (tab == 1) "Notícias lidas" else "Ler depois", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    }
                }
                if (tab == 0 && refreshing) item("progress") { RefreshProgress(sources, currentSource, completedSources, failedSources) }
                if (!initialized) item("loading") { LoadingCard("Preparando seu feed…") }
                else if (displayItems.isEmpty()) item("empty") { EmptyState(tab) }
                else items(displayItems, key = { it.id }, contentType = { "news" }) { news ->
                    NewsCard(news, sourceById[news.sourceId], news.url in savedUrls, featured = tab == 0 && displayItems.firstOrNull()?.id == news.id) { openItem(news) }
                }
                if (opening) item("opening") { LoadingCard("Abrindo notícia…", true) }
                if (refreshError != null && displayItems.isNotEmpty()) item("error") { InlineError(refreshError!!, { refresh() }) }
            }
            if (showScrollToTop) {
                SmallFloatingActionButton(
                    onClick = { scope.launch { listState.animateScrollToItem(0) } },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 16.dp)
                ) { Text("↑", fontSize = 21.sp, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

private data class InitialData(
    val sources: List<FeedSource>, val readUrls: Set<String>, val readItems: List<FeedItem>,
    val savedUrls: Set<String>, val savedItems: List<FeedItem>, val items: List<FeedItem>
)

@Composable
private fun HomeHeader(loading: Boolean, initialized: Boolean, newItemsCount: Int, activeSources: Int, currentSource: String?, onSources: () -> Unit, onRefresh: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("NewsRSS", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(
                    when {
                        !initialized -> "Preparando seu feed"
                        loading -> currentSource?.let { "Atualizando $it…" } ?: "Atualizando fontes…"
                        newItemsCount > 0 -> "$newItemsCount novas notícias"
                        else -> "$activeSources fontes ativas"
                    }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onSources) { Text("Fontes") }
            FilledTonalButton(enabled = initialized && !loading, onClick = onRefresh) { Text("Atualizar") }
        }
        if (newItemsCount > 0 && !loading) {
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.primaryContainer) {
                Text("$newItemsCount novas notícias disponíveis", Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
}

@Composable
private fun RefreshProgress(sources: List<FeedSource>, current: String?, completed: Set<String>, failed: Set<String>) {
    val total = sources.count { it.enabled }.coerceAtLeast(1)
    val done = completed.size + failed.size
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LinearProgressIndicator({ (done.toFloat() / total).coerceIn(0f, 1f) }, Modifier.fillMaxWidth().height(3.dp))
        Text(if (current != null) "Atualizando ${sources.firstOrNull { it.id == current }?.name ?: "fonte"}…" else "Atualizando fontes…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CategoryFilter(selected: NewsCategory?, onSelected: (NewsCategory?) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected == null, { onSelected(null) }, label = { Text("Todos") })
        NewsCategory.entries.forEach { category -> FilterChip(selected == category, { onSelected(category) }, label = { Text(category.label) }) }
    }
}

@Composable
private fun LoadingCard(message: String, compact: Boolean = false) {
    Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        Row(Modifier.padding(if (compact) 12.dp else 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(Modifier.size(if (compact) 20.dp else 26.dp), strokeWidth = 2.5.dp)
            Text(message, style = if (compact) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun EmptyState(tab: Int) {
    Box(Modifier.fillMaxWidth().padding(vertical = 72.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (tab == 1) "Ainda não há notícias lidas" else if (tab == 2) "Sua lista está vazia" else "Nenhuma notícia encontrada", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(if (tab == 2) "Salve uma notícia para ler depois." else "Quando houver conteúdo, ele aparecerá aqui.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun InlineError(message: String, onRetry: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.errorContainer) {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.labelMedium)
            TextButton(onClick = onRetry) { Text("Tentar") }
        }
    }
}

@Composable
private fun SourceManager(sources: List<FeedSource>, failedSources: Set<String>, urlInput: String, sourceError: String?, onUrlChange: (String) -> Unit, onAdd: () -> Unit, onBack: () -> Unit, onToggle: (FeedSource) -> Unit, onCategoryChange: (FeedSource) -> Unit, onDelete: (FeedSource) -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Fontes", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            TextButton(onClick = onBack) { Text("Voltar") }
        }
        Text("As falhas ficam aqui e não poluem a página principal.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(urlInput, onUrlChange, Modifier.weight(1f), singleLine = true, label = { Text("Adicionar site") })
            Button(onClick = onAdd) { Text("Adicionar") }
        }
        sourceError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp)) }
        Spacer(Modifier.height(12.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(sources, key = { it.id }) { source ->
                val failed = source.id in failedSources
                Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(source.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text(source.siteUrl, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                            Switch(source.enabled, { onToggle(source) })
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { onCategoryChange(source) }) { Text(source.category.label) }
                            if (failed) Text("⚠ Não atualizada", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.weight(1f))
                            if (source.id.startsWith("custom-")) TextButton(onClick = { onDelete(source) }) { Text("Excluir") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NewsCard(item: FeedItem, source: FeedSource?, saved: Boolean, featured: Boolean, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick), shape = MaterialTheme.shapes.large, elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        if (featured) {
            Column {
                item.imageUrl?.let { url -> AsyncImage(model = url, contentDescription = null, modifier = Modifier.fillMaxWidth().height(190.dp), contentScale = ContentScale.Crop) }
                NewsCardText(item, source, saved, large = true)
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                item.imageUrl?.let { url -> AsyncImage(model = url, contentDescription = null, modifier = Modifier.size(96.dp, 76.dp), contentScale = ContentScale.Crop) }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(source?.name ?: "Fonte", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.weight(1f))
                        item.publishedAt?.let { Text(publishedLabel(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    Text(item.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, lineHeight = 21.sp, maxLines = 3)
                    if (saved) Text("★ Salvo", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
private fun NewsCardText(item: FeedItem, source: FeedSource?, saved: Boolean, large: Boolean) {
    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(source?.name ?: "Fonte", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.weight(1f))
            item.publishedAt?.let { Text(publishedLabel(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Text(item.title, style = if (large) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, lineHeight = if (large) 31.sp else 25.sp, maxLines = if (large) 4 else 3)
        item.summary?.takeIf { it.isNotBlank() }?.let { Text(it, maxLines = 2, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 20.sp) }
        if (saved) Text("★ Salvo para ler depois", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun ReaderContent(article: Article, saved: Boolean, onBack: () -> Unit, onToggleSaved: () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text("Notícia") }, navigationIcon = { TextButton(onClick = onBack) { Text("Voltar") } }, actions = { TextButton(onClick = onToggleSaved) { Text(if (saved) "★" else "☆") } }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(horizontal = 22.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            item { Text(if (saved) "Salva para ler depois" else "Notícia", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
            item { Text(article.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, lineHeight = 38.sp) }
            article.subtitle?.takeIf { it.isNotBlank() }?.let { item { Text(it, style = MaterialTheme.typography.titleMedium, lineHeight = 25.sp) } }
            article.author?.takeIf { it.isNotBlank() }?.let { item { Text("Por $it", style = MaterialTheme.typography.labelLarge) } }
            article.publishedAt?.let { item { Text(publishedLabel(it), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
            if (!article.heroImageUrl.isNullOrBlank()) item { AsyncImage(article.heroImageUrl, null, Modifier.fillMaxWidth().heightIn(max = 300.dp), contentScale = ContentScale.FillWidth) }
            article.blocks.forEach { block ->
                item {
                    when (block) {
                        is ArticleBlock.Paragraph -> Text(block.text, style = MaterialTheme.typography.bodyLarge, fontSize = 18.sp, lineHeight = 29.sp)
                        is ArticleBlock.Heading -> Text(block.text, style = if (block.level <= 2) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        is ArticleBlock.Image -> Column(verticalArrangement = Arrangement.spacedBy(5.dp)) { AsyncImage(block.url, block.altText ?: article.title, Modifier.fillMaxWidth().heightIn(max = 360.dp), contentScale = ContentScale.FillWidth); block.caption?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                        is ArticleBlock.Quote -> Text("“${block.text}”${block.author?.let { " — $it" } ?: ""}", style = MaterialTheme.typography.bodyLarge, fontStyle = FontStyle.Italic, fontSize = 18.sp, lineHeight = 29.sp)
                        is ArticleBlock.ListBlock -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { block.items.forEachIndexed { index, text -> Text(if (block.ordered) "${index + 1}. $text" else "• $text", style = MaterialTheme.typography.bodyLarge, fontSize = 18.sp, lineHeight = 29.sp) } }
                    }
                }
            }
        }
    }
}

private fun mergeFeedItems(current: List<FeedItem>, incoming: List<FeedItem>): List<FeedItem> {
    val merged = LinkedHashMap<String, FeedItem>(current.size + incoming.size)
    current.forEach { merged[it.url] = it }
    incoming.forEach { fresh ->
        val previous = merged[fresh.url]
        merged[fresh.url] = if (previous == null) fresh else previous.copy(
            id = fresh.id, sourceId = fresh.sourceId, title = fresh.title.ifBlank { previous.title },
            summary = fresh.summary?.takeIf { it.isNotBlank() } ?: previous.summary,
            publishedAt = fresh.publishedAt ?: previous.publishedAt,
            imageUrl = fresh.imageUrl?.takeIf { it.isNotBlank() } ?: previous.imageUrl
        )
    }
    return merged.values.sortedWith(compareByDescending<FeedItem> { it.publishedAt ?: Instant.EPOCH }.thenByDescending { it.id })
}

private fun publishedLabel(instant: Instant): String = DateTimeFormatter.ofPattern("dd/MM HH:mm", Locale("pt", "BR")).withZone(ZoneId.systemDefault()).format(instant)
