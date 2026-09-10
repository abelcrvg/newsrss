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
                    Row(verticalAlignment = Alignment.CenterVertically) { TextButton(onClick = { onCategory(source) }) { Text(source.category.label) }; if (source.id in failed) Text("⚠ Não atualizada", color = MaterialTheme.colorScheme.error); Spacer(Modifier.weight(1f)); if (source.id.startsWith("custom-")) TextButton(onClick = { onDelete(source) }) { Text("Excluir") } }
                } }
            }
        }
    }
}

@Composable private fun NewsCard(item: FeedItem, source: FeedSource?, saved: Boolean, read: Boolean, featured: Boolean, onClick: () -> Unit) {
    val title = cleanNewsTitle(item.title, item.sourceId)
    val titleColor = if (read) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    val cardColor = if (read) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f) else MaterialTheme.colorScheme.surface
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick), shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = cardColor)) {
        if (featured) {
            Column {
                item.imageUrl?.let { AsyncImage(it, null, Modifier.fillMaxWidth().height(190.dp), contentScale = ContentScale.Crop) }
                Column(Modifier.padding(14.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(source?.name ?: "Fonte", color = if (read) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold); item.publishedAt?.let { Text(publishedLabel(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                    Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, lineHeight = 31.sp, color = titleColor)
                    item.summary?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3) }
                    if (read) Text("✓ Lida", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                }
            }
        } else Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            item.imageUrl?.let { AsyncImage(it, null, Modifier.size(96.dp, 76.dp), contentScale = ContentScale.Crop) }
            Column(Modifier.weight(1f)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(source?.name ?: "Fonte", color = if (read) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold); item.publishedAt?.let { Text(publishedLabel(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, lineHeight = 21.sp, maxLines = 3, color = titleColor)
                if (saved) Text("★ Salvo", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                if (read) Text("✓ Lida", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable private fun ReaderContent(article: Article, saved: Boolean, onBack: () -> Unit, onToggleSaved: () -> Unit) {
    val listState = rememberLazyListState()
    val sourceColor = readerSourceColor(article.sourceId)
    val progress by remember { derivedStateOf { val total = listState.layoutInfo.totalItemsCount.coerceAtLeast(1); (listState.firstVisibleItemIndex.toFloat() / (total - 1).coerceAtLeast(1)).coerceIn(0f, 1f) } }
    val cleanTitle = cleanNewsTitle(article.title, article.sourceId)
    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = { Column { LinearProgressIndicator(progress, Modifier.fillMaxWidth().height(3.dp), color = sourceColor); TopAppBar(title = { Text("Leitura", fontWeight = FontWeight.SemiBold) }, navigationIcon = { IconButton(onClick = onBack) { Text("‹", fontSize = 32.sp) } }, actions = { TextButton(onClick = onToggleSaved) { Text(if (saved) "★" else "☆", fontSize = 24.sp) } }) } }) { padding ->
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 56.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            item { Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { Box(Modifier.size(8.dp).clip(MaterialTheme.shapes.small).background(sourceColor)); Text(sourceLabel(article.sourceId), color = sourceColor, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge) }
                Text(cleanTitle, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.ExtraBold, lineHeight = 42.sp, letterSpacing = (-0.5).sp)
                article.subtitle?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.titleLarge, lineHeight = 29.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) { article.author?.takeIf { it.isNotBlank() }?.let { Text("Por $it", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge) }; article.publishedAt?.let { Text(publishedLabel(it), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
            } }
            if (!article.heroImageUrl.isNullOrBlank()) item { AsyncImage(article.heroImageUrl, cleanTitle, Modifier.fillMaxWidth().heightIn(max = 360.dp), contentScale = ContentScale.Crop) }
            item { Spacer(Modifier.height(18.dp)) }
            article.blocks.forEachIndexed { index, block -> item(key = "reader-$index") { ReaderBlock(block, sourceColor, cleanTitle) } }
        }
    }
}

@Composable private fun ReaderBlock(block: ArticleBlock, accent: Color, title: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (block) {
            is ArticleBlock.Paragraph -> Text(block.text, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyLarge, fontSize = 19.sp, lineHeight = 32.sp, letterSpacing = 0.05.sp)
            is ArticleBlock.Heading -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Spacer(Modifier.height(14.dp)); Text(block.text, style = if (block.level <= 2) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, lineHeight = 32.sp); Box(Modifier.width(42.dp).height(3.dp).clip(MaterialTheme.shapes.small).background(accent)) }
            is ArticleBlock.Image -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) { AsyncImage(block.url, block.altText ?: title, Modifier.fillMaxWidth().heightIn(max = 420.dp).clip(MaterialTheme.shapes.large), contentScale = ContentScale.Crop); block.caption?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Start) } }
            is ArticleBlock.Quote -> Row(Modifier.fillMaxWidth()) { Box(Modifier.width(4.dp).heightIn(min = 60.dp).clip(MaterialTheme.shapes.small).background(accent)); Column(Modifier.padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) { Text("“${block.text}”", style = MaterialTheme.typography.titleLarge, fontStyle = FontStyle.Italic, lineHeight = 30.sp); block.author?.let { Text("— $it", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } } }
            is ArticleBlock.ListBlock -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { block.items.forEachIndexed { i, text -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) { Text(if (block.ordered) "${i + 1}." else "•", color = accent, fontWeight = FontWeight.Bold, fontSize = 20.sp); Text(text, style = MaterialTheme.typography.bodyLarge, fontSize = 19.sp, lineHeight = 31.sp) } } }
        }
        Spacer(Modifier.height(20.dp))
    }
}

private fun cleanNewsTitle(title: String, sourceId: String): String {
    if (!sourceId.contains("cnn", true)) return title.trim()
    return title.trim()
        .replace(Regex("(?i)^imagem\\s+representando\\s+a\\s+mat[ée]ria\\s*:\\s*"), "")
        .trim()
}

private fun mergeFeedItems(current: List<FeedItem>, incoming: List<FeedItem>): List<FeedItem> {
    val merged = LinkedHashMap<String, FeedItem>()
    current.forEach { merged[it.url] = it }
    incoming.forEach { fresh ->
        val old = merged[fresh.url]
        merged[fresh.url] = if (old == null) fresh else old.copy(id = fresh.id, sourceId = fresh.sourceId, title = fresh.title.ifBlank { old.title }, summary = fresh.summary?.takeIf { it.isNotBlank() } ?: old.summary, publishedAt = fresh.publishedAt ?: old.publishedAt, imageUrl = fresh.imageUrl?.takeIf { it.isNotBlank() } ?: old.imageUrl)
    }
    return merged.values.sortedWith(compareByDescending<FeedItem> { it.publishedAt ?: Instant.EPOCH }.thenByDescending { it.id })
}

private fun publishedLabel(instant: Instant): String = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale("pt", "BR")).withZone(ZoneId.systemDefault()).format(instant)
private fun readerSourceColor(sourceId: String): Color = when { sourceId.contains("g1", true) -> Color(0xFFE51B23); sourceId.contains("globo", true) -> Color(0xFFE51B23); sourceId.contains("verge", true) -> Color(0xFF111111); sourceId.contains("sky", true) -> Color(0xFF0072CE); sourceId.contains("espn", true) -> Color(0xFFCC0000); else -> Color(0xFF6750A4) }
private fun sourceLabel(sourceId: String): String = when { sourceId.contains("g1", true) -> "G1"; sourceId.contains("verge", true) -> "The Verge"; sourceId.contains("sky", true) -> "Sky Sports"; sourceId.contains("espn", true) -> "ESPN"; else -> sourceId.substringAfterLast('.').ifBlank { "NewsRSS" }.replaceFirstChar { it.uppercase() } }