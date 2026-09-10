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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import kotlinx.coroutines.delay
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
    val app = context.applicationContext
    val sourceStore = remember { SourceStore(app) }
    val cacheStore = remember { FeedCacheStore(app) }
    val readStore = remember { ReadArticleStore(app) }
    val savedStore = remember { SavedArticleStore(app) }
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
    var error by remember { mutableStateOf<String?>(null) }
    var failedSources by remember { mutableStateOf<Set<String>>(emptySet()) }
    var article by remember { mutableStateOf<Article?>(null) }
    var currentItem by remember { mutableStateOf<FeedItem?>(null) }
    var manageSources by remember { mutableStateOf(false) }
    var tab by remember { mutableIntStateOf(0) }
    var category by remember { mutableStateOf<NewsCategory?>(null) }
    var urlInput by remember { mutableStateOf("") }
    var sourceError by remember { mutableStateOf<String?>(null) }
    var newItems by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    val sourceById = remember(sources) { sources.associateBy { it.id } }

    fun saveSources(value: List<FeedSource>) { sources = value; scope.launch(Dispatchers.IO) { sourceStore.save(value) } }
    fun markRead(item: FeedItem) {
        if (item.url !in readUrls) {
            readUrls = readUrls + item.url
            readItems = (listOf(item) + readItems).distinctBy { it.url }.take(300)
            scope.launch(Dispatchers.IO) { readStore.markRead(item) }
        }
    }
    fun toggleSaved(item: FeedItem) {
        val save = item.url !in savedUrls
        savedUrls = if (save) savedUrls + item.url else savedUrls - item.url
        savedItems = if (save) (listOf(item) + savedItems).distinctBy { it.url }.take(300) else savedItems.filterNot { it.url == item.url }
        scope.launch(Dispatchers.IO) { savedStore.setSaved(item, save) }
    }
    fun refresh() {
        if (!initialized || refreshing) return
        refreshing = true
        error = null
        scope.launch {
            val enabled = sources.filter { it.enabled }
            if (enabled.isEmpty()) { refreshing = false; error = "Nenhuma fonte ativa."; return@launch }
            val old = items.map { it.url }.toHashSet()
            val reader = SmartFeedReader()
            val results: List<Pair<String, Result<List<FeedItem>>>> = enabled.map { source ->
                async(Dispatchers.IO) { source.id to runCatching { reader.read(source) }.getOrElse { Result.failure(it) } }
            }.awaitAll()
            val incoming = buildList {
                results.forEach { (id, result) -> result.getOrNull().orEmpty().take(80).forEach { add(it.copy(sourceId = id)) } }
            }
            failedSources = results.filter { it.second.isFailure }.mapTo(linkedSetOf()) { it.first }
            items = mergeFeedItems(items, incoming).take(800)
            newItems = incoming.count { it.url !in old }
            refreshing = false
            if (incoming.isNotEmpty()) withContext(Dispatchers.IO) { cacheStore.merge(incoming) }
        }
    }
    fun openItem(item: FeedItem) {
        currentItem = item
        markRead(item)
        opening = true
        error = null
        scope.launch(Dispatchers.IO) {
            val result = JsoupArticleExtractor().extract(item.url)
            val raw = result.getOrNull()
            val translated = if (raw != null && sourceById[item.sourceId]?.language == SourceLanguage.ENGLISH) {
                runCatching { OnDeviceTranslator(app).translateArticle(raw) }.getOrNull()
            } else raw
            withContext(Dispatchers.Main.immediate) {
                if (translated != null) article = translated.copy(publishedAt = translated.publishedAt ?: item.publishedAt)
                else error = result.exceptionOrNull()?.message ?: "Não foi possível abrir a notícia."
                opening = false
            }
        }
    }
    fun addSource() {
        sourceError = null
        val normalized = urlInput.trim().removeSuffix("/")
        val uri = runCatching { URI(normalized) }.getOrNull()
        if (uri == null || uri.scheme !in listOf("http", "https") || uri.host.isNullOrBlank()) { sourceError = "Digite uma URL válida."; return }
        if (sources.any { it.siteUrl.equals(normalized, true) }) { sourceError = "Essa fonte já está adicionada."; return }
        val host = uri.host.removePrefix("www.")
        val base = "custom-" + host.replace(Regex("[^a-zA-Z0-9]+"), "-").trim('-').lowercase(Locale.ROOT)
        val id = if (sources.none { it.id == base }) base else "$base-${normalized.hashCode().toUInt().toString(16)}"
        saveSources(sources + FeedSource(id, host.substringBefore('.').replaceFirstChar { it.uppercase() }, normalized, category = NewsCategory.NEWS))
        urlInput = ""
    }

    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) { InitialData(sourceStore.load(SourceRegistry.defaultSources), readStore.load(), readStore.loadItems(), savedStore.load(), savedStore.loadItems(), cacheStore.load()) }
        sources = loaded.sources
        readUrls = loaded.readUrls
        readItems = loaded.readItems.take(300)
        savedUrls = loaded.savedUrls
        savedItems = loaded.savedItems.take(300)
        items = loaded.items.take(800)
        initialized = true
        NewsRefreshScheduler.schedule(app)
        refresh()
    }

    // Android limits WorkManager periodic jobs to a 15-minute minimum. Keep the
    // app's foreground feed fresh every 5 minutes without relying on WorkManager.
    LaunchedEffect(initialized) {
        if (!initialized) return@LaunchedEffect
        while (true) {
            delay(5 * 60 * 1000L)
            if (!refreshing) refresh()
        }
    }

    if (article != null && currentItem != null) {
        BackHandler { article = null }
        ReaderContent(article!!, currentItem!!.url in savedUrls, { article = null }, { toggleSaved(currentItem!!) })
        return
    }
    if (manageSources) {
        BackHandler { manageSources = false }
        SourceManager(sources, failedSources, urlInput, sourceError, { urlInput = it }, { addSource() }, { manageSources = false }, { s -> saveSources(sources.map { if (it.id == s.id) it.copy(enabled = !it.enabled) else it }) }, { s -> val next = NewsCategory.entries[(NewsCategory.entries.indexOf(s.category) + 1) % NewsCategory.entries.size]; saveSources(sources.map { if (it.id == s.id) it.copy(category = next) else it }) }, { s -> saveSources(sources.filterNot { it.id == s.id }) })
        return
    }

    val filtered = remember(items, category, sourceById) {
        val active = items.filter { sourceById[it.sourceId]?.enabled == true }
        if (category == null) active else active.filter { sourceById[it.sourceId]?.category == category }
    }
    val display = when (tab) { 1 -> readItems; 2 -> savedItems; else -> filtered }

    Scaffold(bottomBar = { NavigationBar {
        NavigationBarItem(tab == 0, { tab = 0 }, icon = { Text("●") }, label = { Text("Notícias") })
        NavigationBarItem(tab == 1, { tab = 1 }, icon = { Text("✓") }, label = { Text("Lidas") })
        NavigationBarItem(tab == 2, { tab = 2 }, icon = { Text("★") }, label = { Text("Ler depois") })
    } }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(16.dp, 14.dp, 16.dp, 96.dp)) {
                item { HomeHeader(refreshing, initialized, newItems, sources.count { it.enabled }, { manageSources = true }, { refresh() }) }
                if (tab == 0) item { CategoryFilter(category) { category = it } }
                else item { Text(if (tab == 1) "Notícias lidas" else "Ler depois", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
                if (opening) item { LoadingCard("Traduzindo e abrindo notícia…", true) }
                if (refreshing) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                if (!initialized) item { LoadingCard("Preparando seu feed…") }
                else if (display.isEmpty()) item { EmptyState(tab) }
                else items(display, key = { it.id }) { news ->
                    NewsCard(news, sourceById[news.sourceId], news.url in savedUrls, news.url in readUrls, display.firstOrNull()?.id == news.id && tab == 0) { openItem(news) }
                }
                error?.let { message -> if (display.isNotEmpty()) item { InlineError(message) { refresh() } } }
            }
            if (listState.firstVisibleItemIndex >= 6) SmallFloatingActionButton({ scope.launch { listState.animateScrollToItem(0) } }, Modifier.align(Alignment.BottomEnd).padding(16.dp)) { Text("↑", fontSize = 21.sp) }
        }
    }
}

private data class InitialData(val sources: List<FeedSource>, val readUrls: Set<String>, val readItems: List<FeedItem>, val savedUrls: Set<String>, val savedItems: List<FeedItem>, val items: List<FeedItem>)

@Composable private fun HomeHeader(loading: Boolean, initialized: Boolean, newItems: Int, active: Int, onSources: () -> Unit, onRefresh: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("NewsRSS", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(if (!initialized) "Preparando seu feed" else if (loading) "Atualizando fontes…" else if (newItems > 0) "$newItems novas notícias" else "$active fontes ativas", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = onSources) { Text("Fontes") }
        FilledTonalButton(enabled = initialized && !loading, onClick = onRefresh) { Text("Atualizar") }
    }
}

@Composable private fun CategoryFilter(selected: NewsCategory?, onSelected: (NewsCategory?) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected == null, { onSelected(null) }, label = { Text("Todos") })
        NewsCategory.entries.forEach { FilterChip(selected == it, { onSelected(it) }, label = { Text(it.label) }) }
    }
}

@Composable private fun LoadingCard(message: String, compact: Boolean = false) {
    Card(Modifier.fillMaxWidth()) { Row(Modifier.padding(if (compact) 12.dp else 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) { CircularProgressIndicator(Modifier.size(if (compact) 20.dp else 26.dp), strokeWidth = 2.5.dp); Text(message) } }
}

@Composable private fun EmptyState(tab: Int) { Box(Modifier.fillMaxWidth().padding(vertical = 72.dp), contentAlignment = Alignment.Center) { Text(if (tab == 1) "Ainda não há notícias lidas" else if (tab == 2) "Sua lista está vazia" else "Nenhuma notícia encontrada", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) } }

@Composable private fun InlineError(message: String, retry: () -> Unit) { Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) { Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) { Text(message, Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer); TextButton(onClick = retry) { Text("Tentar") } } } }

@Composable private fun SourceManager(sources: List<FeedSource>, failed: Set<String>, input: String, sourceError: String?, onInput: (String) -> Unit, onAdd: () -> Unit, onBack: () -> Unit, onToggle: (FeedSource) -> Unit, onCategory: (FeedSource) -> Unit, onDelete: (FeedSource) -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text("Fontes", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); TextButton(onClick = onBack) { Text("Voltar") } }
        Text("Falhas ficam aqui, sem poluir o feed.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedTextField(input, onInput, Modifier.weight(1f), singleLine = true, label = { Text("Adicionar site") }); Button(onClick = onAdd) { Text("Adicionar") } }
        sourceError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.height(12.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(sources, key = { it.id }) { source ->
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(source.name, fontWeight = FontWeight.SemiBold); Text(source.siteUrl, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }; Switch(source.enabled, { onToggle(source) }) }
                    Row(verticalAlignment = Alignment.CenterVertically) { TextButton(onClick = { onCategory(source) }) { Text(source.category.label) }; if (source.id in failed) Text("⚠ Falha", color = MaterialTheme.colorScheme.error); Spacer(Modifier.weight(1f)); TextButton(onClick = { onDelete(source) }) { Text("Excluir") } }
                } }
            }
        }
    }
}

@Composable private fun NewsCard(item: FeedItem, source: FeedSource?, saved: Boolean, read: Boolean, featured: Boolean, onClick: () -> Unit) {
    val titleColor = if (read) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    val cardColor = if (read) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f) else MaterialTheme.colorScheme.surface
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick), colors = CardDefaults.cardColors(containerColor = cardColor)) {
        Column {
            item.imageUrl?.let { AsyncImage(it, null, Modifier.fillMaxWidth().height(if (featured) 220.dp else 150.dp), contentScale = ContentScale.Crop) }
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(source?.name ?: item.sourceId, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                    if (read) Text("✓ Lida", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(6.dp))
                Text(item.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = titleColor)
                item.summary?.takeIf { it.isNotBlank() }?.let { Spacer(Modifier.height(6.dp)); Text(it, maxLines = 3, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) { Text(formatDate(item.publishedAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.weight(1f)); Text(if (saved) "★" else "☆", fontSize = 22.sp) }
            }
        }
    }
}

@Composable private fun ReaderContent(article: Article, saved: Boolean, onBack: () -> Unit, onSave: () -> Unit) {
    val scroll = rememberScrollState()
    val sourceColor = sourceAccent(article.sourceId)
    val progress = if (scroll.maxValue == 0) 0f else (scroll.value.toFloat() / scroll.maxValue).coerceIn(0f, 1f)
    Column(Modifier.fillMaxSize()) {
        LinearProgressIndicator(progress, Modifier.fillMaxWidth().height(3.dp), color = sourceColor)
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { TextButton(onClick = onBack) { Text("← Voltar") }; Spacer(Modifier.weight(1f)); TextButton(onClick = onSave) { Text(if (saved) "★ Salvo" else "☆ Salvar") } }
        androidx.compose.foundation.verticalScroll(Modifier.weight(1f)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp)) {
                Text(article.sourceId, style = MaterialTheme.typography.labelMedium, color = sourceColor, fontWeight = FontWeight.Bold)
                Text(article.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                article.subtitle?.takeIf { it.isNotBlank() }?.let { Spacer(Modifier.height(8.dp)); Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                article.heroImageUrl?.let { Spacer(Modifier.height(14.dp)); AsyncImage(it, null, Modifier.fillMaxWidth().heightIn(max = 280.dp).clip(MaterialTheme.shapes.medium), contentScale = ContentScale.Crop) }
                article.author?.let { Spacer(Modifier.height(10.dp)); Text("Por $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                article.publishedAt?.let { Text(formatDate(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Spacer(Modifier.height(18.dp))
                article.blocks.forEach { block -> when (block) {
                    is ArticleBlock.Paragraph -> Text(block.text, style = MaterialTheme.typography.bodyLarge, lineHeight = 28.sp, modifier = Modifier.padding(bottom = 16.dp))
                    is ArticleBlock.Heading -> Text(block.text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 10.dp))
                    is ArticleBlock.Image -> AsyncImage(block.url, null, Modifier.fillMaxWidth().heightIn(max = 360.dp).clip(MaterialTheme.shapes.medium), contentScale = ContentScale.Fit)
                    is ArticleBlock.Video -> Text("▶ Vídeo", modifier = Modifier.padding(vertical = 12.dp), fontWeight = FontWeight.Bold)
                    is ArticleBlock.Quote -> Text("“${block.text}”", style = MaterialTheme.typography.bodyLarge, fontStyle = FontStyle.Italic, modifier = Modifier.padding(vertical = 12.dp))
                } }
            }
        }
    }
}

private fun mergeFeedItems(existing: List<FeedItem>, incoming: List<FeedItem>): List<FeedItem> {
    val map = LinkedHashMap<String, FeedItem>()
    (existing + incoming).forEach { map[it.url] = it }
    return map.values.sortedWith(compareByDescending<FeedItem> { it.publishedAt ?: Instant.EPOCH }.thenByDescending { it.title.lowercase(Locale.ROOT) })
}

private fun formatDate(value: Instant?): String = value?.atZone(ZoneId.systemDefault())?.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale("pt", "BR"))) ?: "Data não informada"

private fun sourceAccent(sourceId: String): Color = when {
    sourceId.contains("globo", true) -> Color(0xFF1A73E8)
    sourceId.contains("lance", true) -> Color(0xFFE53935)
    sourceId.contains("tnt", true) -> Color(0xFF6A1B9A)
    sourceId.contains("cnn", true) -> Color(0xFFD32F2F)
    sourceId.contains("bbc", true) -> Color(0xFF222222)
    sourceId.contains("guardian", true) -> Color(0xFF005689)
    sourceId.contains("sky", true) -> Color(0xFF1565C0)
    else -> Color(0xFF455A64)
}
