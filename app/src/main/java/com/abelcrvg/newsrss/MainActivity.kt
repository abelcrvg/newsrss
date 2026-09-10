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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
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
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
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
    val sourceStore = remember { SourceStore(context.applicationContext) }
    val cacheStore = remember { FeedCacheStore(context.applicationContext) }
    val readStore = remember { ReadArticleStore(context.applicationContext) }
    val savedStore = remember { SavedArticleStore(context.applicationContext) }

    var sources by remember { mutableStateOf<List<FeedSource>>(emptyList()) }
    var readUrls by remember { mutableStateOf<Set<String>>(emptySet()) }
    var readItems by remember { mutableStateOf<List<FeedItem>>(emptyList()) }
    var savedUrls by remember { mutableStateOf<Set<String>>(emptySet()) }
    var savedItems by remember { mutableStateOf<List<FeedItem>>(emptyList()) }
    var items by remember { mutableStateOf<List<FeedItem>>(emptyList()) }
    var initialized by remember { mutableStateOf(false) }
    var newItemsCount by remember { mutableIntStateOf(0) }
    var article by remember { mutableStateOf<Article?>(null) }
    var currentItem by remember { mutableStateOf<FeedItem?>(null) }
    var loading by remember { mutableStateOf(false) }
    var currentSource by remember { mutableStateOf<String?>(null) }
    var completedSources by remember { mutableStateOf<Set<String>>(emptySet()) }
    var failedSources by remember { mutableStateOf<Set<String>>(emptySet()) }
    var opening by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var urlInput by remember { mutableStateOf("") }
    var sourceError by remember { mutableStateOf<String?>(null) }
    var selectedCategory by remember { mutableStateOf<NewsCategory?>(null) }
    var manageSources by remember { mutableStateOf(false) }
    var tab by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val showScrollToTop by remember { derivedStateOf { listState.firstVisibleItemIndex > 5 } }

    fun persistSources(value: List<FeedSource>) {
        sources = value
        scope.launch(Dispatchers.IO) { sourceStore.save(value) }
    }

    fun markRead(item: FeedItem) {
        readUrls = readUrls + item.url
        readItems = (listOf(item) + readItems).distinctBy { it.url }.take(300)
        scope.launch(Dispatchers.IO) { readStore.markRead(item) }
    }

    fun toggleSaved(item: FeedItem) {
        val save = item.url !in savedUrls
        if (save) {
            savedUrls = savedUrls + item.url
            savedItems = (listOf(item) + savedItems).distinctBy { it.url }.take(300)
        } else {
            savedUrls = savedUrls - item.url
            savedItems = savedItems.filterNot { it.url == item.url }
        }
        scope.launch(Dispatchers.IO) { savedStore.setSaved(item, save) }
    }

    fun refresh() {
        if (!initialized || loading) return
        loading = true
        error = null
        currentSource = null
        completedSources = emptySet()
        failedSources = emptySet()
        newItemsCount = 0
        scope.launch {
            val enabledSources = sources.filter { it.enabled }
            val knownUrls = items.asSequence().map { it.url }.toMutableSet()
            val reader = SmartFeedReader()

            val results = enabledSources.map { source ->
                async {
                    currentSource = source.id
                    val result = reader.read(source)
                    if (result.isSuccess) {
                        var sourceItems = result.getOrElse { emptyList() }.map { it.copy(sourceId = source.id) }
                        if (source.language == SourceLanguage.ENGLISH && sourceItems.isNotEmpty()) {
                            sourceItems = OnDeviceTranslator(context.applicationContext).translateFeedItems(sourceItems)
                        }
                        source.id to Result.success(sourceItems)
                    } else {
                        source.id to Result.failure<List<FeedItem>>(result.exceptionOrNull() ?: Exception("Fonte indisponível"))
                    }
                }
            }.awaitAll()

            var merged = items
            var added = 0
            val failed = linkedSetOf<String>()
            val completed = linkedSetOf<String>()
            for ((sourceId, result) in results) {
                if (result.isSuccess) {
                    val sourceItems = result.getOrNull().orEmpty()
                    added += sourceItems.count { knownUrls.add(it.url) }
                    merged = mergeFeedItems(merged, sourceItems)
                    completed += sourceId
                } else {
                    failed += sourceId
                }
            }

            items = merged
            newItemsCount = added
            completedSources = completed
            failedSources = failed
            currentSource = null
            loading = false
            error = when {
                merged.isEmpty() && failed.isNotEmpty() -> "Nenhuma fonte conseguiu fornecer notícias."
                else -> null
            }

            withContext(Dispatchers.IO) {
                results.forEach { (_, result) -> result.getOrNull()?.takeIf { it.isNotEmpty() }?.let { cacheStore.merge(it) } }
            }
        }
    }

    fun openItem(item: FeedItem) {
        currentItem = item
        markRead(item)
        opening = true
        error = null
        scope.launch {
            JsoupArticleExtractor().extract(item.url).onSuccess { extracted ->
                val source = sources.firstOrNull { it.id == item.sourceId }
                article = if (source?.language == SourceLanguage.ENGLISH) {
                    OnDeviceTranslator(context.applicationContext).translateArticle(extracted)
                } else extracted
                article = article?.copy(publishedAt = article?.publishedAt ?: item.publishedAt)
                opening = false
            }.onFailure { failure ->
                error = failure.message ?: "Não foi possível abrir a notícia."
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
        val host = uri.host.removePrefix("www.")
        val path = uri.path.orEmpty().trim('/').replace(Regex("[^a-zA-Z0-9]+"), "-").trim('-')
        val baseId = "custom-" + (host + if (path.isNotBlank()) "-$path" else "").replace(Regex("[^a-zA-Z0-9]+"), "-").trim('-').lowercase(Locale.ROOT)
        if (sources.any { it.siteUrl.equals(normalized, ignoreCase = true) }) {
            sourceError = "Essa fonte já está adicionada."
            return
        }
        val id = if (sources.none { it.id == baseId }) baseId else "${baseId.take(80)}-${normalized.hashCode().toUInt().toString(16).takeLast(8)}"
        val displayName = path.substringAfterLast('-').takeIf { it.isNotBlank() }?.replaceFirstChar { it.uppercase() }
            ?: host.substringBefore('.').replaceFirstChar { it.uppercase() }
        persistSources(sources + FeedSource(id, displayName, normalized, category = NewsCategory.NEWS))
        urlInput = ""
        scope.launch {
            refresh()
        }
    }

    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) {
            InitialData(
                sources = sourceStore.load(SourceRegistry.defaultSources),
                readUrls = readStore.load(),
                readItems = readStore.loadItems(),
                savedUrls = savedStore.load(),
                savedItems = savedStore.loadItems(),
                items = cacheStore.load()
            )
        }
        sources = loaded.sources
        readUrls = loaded.readUrls
        readItems = loaded.readItems
        savedUrls = loaded.savedUrls
        savedItems = loaded.savedItems
        items = loaded.items
        initialized = true
        NewsRefreshScheduler.schedule(context.applicationContext)
        refresh()
    }

    val sourceById = remember(sources) { sources.associateBy { it.id } }
    val visibleItems = remember(items, sources, selectedCategory, readUrls) {
        val unread = items.filterNot { it.url in readUrls }
        selectedCategory?.let { category -> unread.filter { item -> sourceById[item.sourceId]?.let { it.enabled && it.category == category } == true } } ?: unread
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
            onUrlChange = { urlInput = it },
            sourceError = sourceError,
            onAdd = { addSource() },
            onBack = { manageSources = false },
            onToggle = { source -> persistSources(sources.map { if (it.id == source.id) it.copy(enabled = !it.enabled) else it }) },
            onCategoryChange = { source, category -> persistSources(sources.map { if (it.id == source.id) it.copy(category = category) else it }) },
            onDelete = { source -> persistSources(sources.filterNot { it.id == source.id }); refresh() }
        )
        return
    }

    val displayItems = when (tab) { 1 -> readItems; 2 -> savedItems; else -> visibleItems }
    val heading = when (tab) { 1 -> "Notícias lidas"; 2 -> "Ler depois"; else -> selectedCategory?.label ?: "Principais e recentes" }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = { Text("N") }, label = { Text("Notícias") })
                NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = { Text("L") }, label = { Text("Lidas") })
                NavigationBarItem(selected = tab == 2, onClick = { tab = 2 }, icon = { Text("★") }, label = { Text("Ler depois") })
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 92.dp)
            ) {
                item(key = "header") {
                    HomeHeader(
                        loading = loading,
                        initialized = initialized,
                        newItemsCount = newItemsCount,
                        activeSources = sources.count { it.enabled },
                        currentSource = currentSource?.let { sourceById[it]?.name },
                        onSources = { manageSources = true },
                        onRefresh = { refresh() }
                    )
                }
                if (tab == 0) {
                    item(key = "categories") { CategoryFilter(selectedCategory) { selectedCategory = it } }
                } else {
                    item(key = "heading") { Text(heading, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp)) }
                }
                if (!initialized) {
                    item(key = "initial-loading") { LoadingCard("Carregando notícias…") }
                } else if (tab == 0 && loading && items.isEmpty()) {
                    item(key = "refresh-loading") { LoadingCard("Buscando as notícias mais recentes…") }
                } else if (displayItems.isEmpty()) {
                    item(key = "empty") { EmptyState(tab) }
                } else {
                    if (tab == 0 && loading) item(key = "status") { CompactLoadingStatus(sources, currentSource, completedSources, failedSources) }
                    items(displayItems, key = { it.id }, contentType = { "news" }) { item ->
                        NewsCard(item, sourceById[item.sourceId], item.url in savedUrls) { openItem(item) }
                    }
                }
                if (opening) item(key = "opening") { LoadingCard("Abrindo notícia…", compact = true) }
                if (error != null && displayItems.isNotEmpty()) item(key = "error") { InlineError(error!!, onRetry = { refresh() }) }
            }

            if (showScrollToTop) {
                SmallFloatingActionButton(
                    onClick = { scope.launch { listState.animateScrollToItem(0) } },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 16.dp)
                ) { Text("↑", fontSize = 20.sp) }
            }
        }
    }
}

private data class InitialData(
    val sources: List<FeedSource>,
    val readUrls: Set<String>,
    val readItems: List<FeedItem>,
    val savedUrls: Set<String>,
    val savedItems: List<FeedItem>,
    val items: List<FeedItem>
)

@Composable
private fun HomeHeader(
    loading: Boolean,
    initialized: Boolean,
    newItemsCount: Int,
    activeSources: Int,
    currentSource: String?,
    onSources: () -> Unit,
    onRefresh: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("NewsRSS", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(
                    when {
                        !initialized -> "Preparando seu feed"
                        loading && currentSource != null -> "Atualizando $currentSource…"
                        newItemsCount > 0 -> "$newItemsCount ${if (newItemsCount == 1) "nova notícia" else "novas notícias"}"
                        else -> "$activeSources fontes ativas"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onSources) { Text("Fontes") }
            FilledTonalButton(onClick = onRefresh, enabled = initialized && !loading) { Text(if (loading) "Atualizando" else "Atualizar") }
        }
        if (newItemsCount > 0 && !loading) {
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.primaryContainer) {
                Text("$newItemsCount novas notícias disponíveis", modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
}

@Composable
private fun CompactLoadingStatus(sources: List<FeedSource>, currentSource: String?, completed: Set<String>, failed: Set<String>) {
    val total = sources.count { it.enabled }.coerceAtLeast(1)
    val done = completed.size + failed.size
    LinearProgressIndicator(progress = { (done.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(3.dp))
    Text(
        if (currentSource != null) "Atualizando ${sources.firstOrNull { it.id == currentSource }?.name ?: "fonte"}…" else "Atualizando fontes…",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
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
private fun InlineError(message: String, onRetry: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.errorContainer) {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.labelMedium)
            TextButton(onClick = onRetry) { Text("Tentar") }
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
private fun CategoryFilter(selected: NewsCategory?, onSelected: (NewsCategory?) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = selected == null, onClick = { onSelected(null) }, label = { Text("Todos") })
        NewsCategory.entries.forEach { category -> FilterChip(selected = selected == category, onClick = { onSelected(category) }, label = { Text(category.label) }) }
    }
}

@Composable
private fun SourceManager(
    sources: List<FeedSource>,
    failedSources: Set<String>,
    urlInput: String,
    onUrlChange: (String) -> Unit,
    sourceError: String?,
    onAdd: () -> Unit,
    onBack: () -> Unit,
    onToggle: (FeedSource) -> Unit,
    onCategoryChange: (FeedSource, NewsCategory) -> Unit,
    onDelete: (FeedSource) -> Unit
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Fontes", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            TextButton(onClick = onBack) { Text("Voltar") }
        }
        Text("Controle suas fontes e veja quais não foram atualizadas.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(source.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text(source.siteUrl, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                            Switch(source.enabled, onCheckedChange = { onToggle(source) })
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { val i = NewsCategory.entries.indexOf(source.category); onCategoryChange(source, NewsCategory.entries[(i + 1) % NewsCategory.entries.size]) }) { Text(source.category.label) }
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
private fun NewsCard(item: FeedItem, source: FeedSource?, saved: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column {
            item.imageUrl?.let { url ->
                AsyncImage(model = url, contentDescription = null, modifier = Modifier.fillMaxWidth().height(180.dp), contentScale = ContentScale.Crop)
            }
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(source?.name ?: "Fonte", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, maxLines = 1)
                    item.publishedAt?.let { Text(publishedLabel(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                Text(item.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, lineHeight = 25.sp, maxLines = 4)
                item.summary?.takeIf { it.isNotBlank() }?.let { Text(it, maxLines = 2, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 20.sp) }
                if (saved) Text("🔖 Ler depois", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun ReaderContent(article: Article, saved: Boolean, onBack: () -> Unit, onToggleSaved: () -> Unit) {
    val highlightColor = if (article.sourceId.lowercase(Locale.ROOT).removePrefix("www.") == "ge.globo.com") Color(0xFF168A45) else MaterialTheme.colorScheme.error
    Scaffold(topBar = { TopAppBar(title = { Text("Notícia") }, navigationIcon = { TextButton(onClick = onBack) { Text("Voltar") } }, actions = { IconButton(onClick = onToggleSaved) { Text("🔖") } }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 18.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextButton(onClick = onToggleSaved) { Text(if (saved) "Remover de Ler depois" else "🔖 Ler depois") } } }
            item { Text(article.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, lineHeight = 38.sp) }
            article.subtitle?.takeIf { it.isNotBlank() }?.let { item { Text(it, style = MaterialTheme.typography.titleMedium, lineHeight = 25.sp) } }
            article.author?.takeIf { it.isNotBlank() }?.let { item { Text("Por $it", style = MaterialTheme.typography.labelLarge) } }
            article.publishedAt?.let { item { Text(publishedLabel(it), style = MaterialTheme.typography.labelMedium) } }
            if (!article.heroImageUrl.isNullOrBlank() && article.blocks.none { it is ArticleBlock.Image && it.url == article.heroImageUrl }) item { AsyncImage(article.heroImageUrl, null, Modifier.fillMaxWidth().heightIn(max = 300.dp), contentScale = ContentScale.FillWidth) }
            article.blocks.forEach { block -> item { when (block) {
                is ArticleBlock.Paragraph -> Text(if (block.inlineHtml.isNullOrBlank()) block.text else inlineAnnotated(block.inlineHtml, highlightColor), style = MaterialTheme.typography.bodyLarge, fontSize = 18.sp, lineHeight = 29.sp)
                is ArticleBlock.Heading -> Text(block.text, style = if (block.level <= 2) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                is ArticleBlock.Image -> Column { AsyncImage(block.url, block.altText ?: article.title, Modifier.fillMaxWidth().heightIn(max = 360.dp), contentScale = ContentScale.FillWidth); block.caption?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.labelMedium) } }
                is ArticleBlock.Quote -> Text("“${block.text}”${block.author?.let { " — $it" } ?: ""}", style = MaterialTheme.typography.bodyLarge, fontStyle = FontStyle.Italic, fontSize = 18.sp, lineHeight = 29.sp)
                is ArticleBlock.ListBlock -> Column { block.items.forEachIndexed { index, text -> Text(if (block.ordered) "${index + 1}. $text" else "• $text", style = MaterialTheme.typography.bodyLarge, fontSize = 18.sp, lineHeight = 29.sp) }
            } } } }
        }
    }
}

private fun mergeFeedItems(current: List<FeedItem>, incoming: List<FeedItem>): List<FeedItem> {
    val merged = LinkedHashMap<String, FeedItem>()
    current.forEach { merged[it.url] = it }
    incoming.forEach { fresh ->
        val previous = merged[fresh.url]
        merged[fresh.url] = if (previous == null) fresh else previous.copy(
            id = fresh.id,
            sourceId = fresh.sourceId,
            title = fresh.title.ifBlank { previous.title },
            url = fresh.url,
            summary = fresh.summary?.takeIf { it.isNotBlank() } ?: previous.summary,
            publishedAt = fresh.publishedAt ?: previous.publishedAt,
            imageUrl = fresh.imageUrl?.takeIf { it.isNotBlank() } ?: previous.imageUrl
        )
    }
    return merged.values.sortedWith(compareByDescending<FeedItem> { it.publishedAt ?: Instant.EPOCH }.thenByDescending { it.id })
}

private fun inlineAnnotated(html: String, highlightColor: Color): AnnotatedString = buildAnnotatedStringFromNode(org.jsoup.Jsoup.parseBodyFragment(html).body(), highlightColor)
private fun buildAnnotatedStringFromNode(root: Element, highlightColor: Color): AnnotatedString = buildAnnotatedStringFromNode(root.childNodes(), highlightColor)
private fun buildAnnotatedStringFromNode(nodes: List<Node>, highlightColor: Color): AnnotatedString = buildAnnotatedString { nodes.forEach { appendInline(it, highlightColor) } }
private fun AnnotatedString.Builder.appendInline(node: Node, highlightColor: Color) { when (node) {
    is TextNode -> append(node.text())
    is Element -> when (node.tagName().lowercase(Locale.ROOT)) {
        "br" -> append("\n")
        "b", "strong" -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { node.childNodes().forEach { appendInline(it, highlightColor) } }
        "i", "em" -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { node.childNodes().forEach { appendInline(it, highlightColor) } }
        "a", "mark" -> withStyle(SpanStyle(color = highlightColor, fontWeight = FontWeight.Bold, textDecoration = TextDecoration.Underline)) { node.childNodes().forEach { appendInline(it, highlightColor) } }
        "span" -> { val style = node.attr("style").lowercase(Locale.ROOT); val color = extractCssColor(style); val bold = style.contains("font-weight:bold") || style.contains("font-weight:700") || style.contains("font-weight: 700") || color != null; if (color != null || bold) withStyle(SpanStyle(color = color ?: Color.Unspecified, fontWeight = if (bold) FontWeight.Bold else null)) { node.childNodes().forEach { appendInline(it, highlightColor) } } else node.childNodes().forEach { appendInline(it, highlightColor) } }
        else -> node.childNodes().forEach { appendInline(it, highlightColor) }
    }
} }
private fun extractCssColor(style: String): Color? { val value = Regex("(?:^|;)\\s*color\\s*:\\s*([^;]+)").find(style)?.groupValues?.getOrNull(1)?.trim() ?: return null; return runCatching { android.graphics.Color.parseColor(value).let { Color(it) } }.getOrNull() }
private fun publishedLabel(instant: Instant): String = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale("pt", "BR")).withZone(ZoneId.systemDefault()).format(instant)
