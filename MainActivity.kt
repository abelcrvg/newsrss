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
import com.abelcrvg.newsrss.data.remote.SupabaseNewsRealtime
import com.abelcrvg.newsrss.data.source.ReadArticleStore
import com.abelcrvg.newsrss.data.source.SavedArticleStore
import com.abelcrvg.newsrss.data.source.SourceStore
import com.abelcrvg.newsrss.data.translation.OnDeviceTranslator
import com.abelcrvg.newsrss.ui.theme.NewsRSSTheme
import kotlinx.coroutines.launch
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
    var sources by remember { mutableStateOf(sourceStore.load(SourceRegistry.defaultSources)) }
    var readUrls by remember { mutableStateOf(readStore.load()) }
    var readItems by remember { mutableStateOf(readStore.loadItems()) }
    var savedUrls by remember { mutableStateOf(savedStore.load()) }
    var savedItems by remember { mutableStateOf(savedStore.loadItems()) }
    var items by remember { mutableStateOf(cacheStore.load()) }
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
    var returnIndex by remember { mutableIntStateOf(0) }
    var returnOffset by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val showScrollToTop by remember { derivedStateOf { listState.firstVisibleItemIndex > 3 } }

    fun persistSources(value: List<FeedSource>) { sources = value; sourceStore.save(value) }
    fun markRead(item: FeedItem) { readStore.markRead(item); readUrls = readStore.load(); readItems = readStore.loadItems() }
    fun toggleSaved(item: FeedItem) { val save = item.url !in savedUrls; savedStore.setSaved(item, save); savedUrls = savedStore.load(); savedItems = savedStore.loadItems() }

    fun refresh() {
        if (loading) return
        loading = true
        error = null
        currentSource = null
        completedSources = emptySet()
        failedSources = emptySet()
        newItemsCount = 0
        scope.launch {
            val enabledSources = sources.filter { it.enabled }
            val knownUrls = items.asSequence().map { it.url }.toMutableSet()
            for (source in enabledSources) {
                currentSource = source.id
                val result = SmartFeedReader().read(source)
                if (result.isSuccess) {
                    var sourceItems = result.getOrElse { emptyList() }.map { it.copy(sourceId = source.id) }
                    if (source.language == SourceLanguage.ENGLISH && sourceItems.isNotEmpty()) {
                        sourceItems = OnDeviceTranslator(context.applicationContext).translateFeedItems(sourceItems)
                    }
                    val newCount = sourceItems.count { knownUrls.add(it.url) }
                    if (newCount > 0) newItemsCount += newCount
                    items = mergeFeedItems(items, sourceItems)
                    cacheStore.merge(sourceItems)
                    completedSources = completedSources + source.id
                } else {
                    failedSources = failedSources + source.id
                }
            }
            currentSource = null
            loading = false
        }
    }

    fun openItem(item: FeedItem) {
        returnIndex = listState.firstVisibleItemIndex
        returnOffset = listState.firstVisibleItemScrollOffset
        currentItem = item
        markRead(item)
        opening = true
        error = null
        scope.launch {
            JsoupArticleExtractor().extract(item.url).onSuccess { extracted ->
                article = if (sources.firstOrNull { it.id == item.sourceId }?.language == SourceLanguage.ENGLISH) OnDeviceTranslator(context.applicationContext).translateArticle(extracted) else extracted
                article = article?.copy(publishedAt = article?.publishedAt ?: item.publishedAt)
                opening = false
            }.onFailure { failure -> error = failure.message ?: "Não foi possível abrir a notícia."; opening = false }
        }
    }

    fun addSource() {
        sourceError = null
        val normalized = urlInput.trim().removeSuffix("/")
        val uri = runCatching { URI(normalized) }.getOrNull()
        if (uri == null || uri.scheme !in listOf("http", "https") || uri.host.isNullOrBlank()) { sourceError = "Digite uma URL válida, por exemplo: https://www.uol.com.br"; return }
        val host = uri.host.removePrefix("www.")
        val path = uri.path.orEmpty().trim('/').replace(Regex("[^a-zA-Z0-9]+"), "-").trim('-')
        val baseId = "custom-" + (host + if (path.isNotBlank()) "-$path" else "").replace(Regex("[^a-zA-Z0-9]+"), "-").trim('-').lowercase(Locale.ROOT)
        if (sources.any { it.siteUrl.equals(normalized, ignoreCase = true) }) { sourceError = "Essa fonte já está adicionada."; return }
        val id = if (sources.none { it.id == baseId }) baseId else "${baseId.take(80)}-${normalized.hashCode().toUInt().toString(16).takeLast(8)}"
        val displayName = path.substringAfterLast('-').takeIf { it.isNotBlank() }?.replaceFirstChar { it.uppercase() } ?: host.substringBefore('.').replaceFirstChar { it.uppercase() }
        persistSources(sources + FeedSource(id, displayName, normalized, category = NewsCategory.NEWS))
        urlInput = ""
        refresh()
    }

    LaunchedEffect(Unit) {
        NewsRefreshScheduler.schedule(context.applicationContext)
        refresh()
    }

    val visibleItems = remember(items, sources, selectedCategory, readUrls) {
        val unread = items.filterNot { it.url in readUrls }
        selectedCategory?.let { category -> unread.filter { item -> sources.any { it.id == item.sourceId && it.enabled && it.category == category } } } ?: unread
    }
    if (article != null && currentItem != null) {
        BackHandler { article = null }
        ReaderContent(article!!, currentItem!!.url in savedUrls, { article = null }, { toggleSaved(currentItem!!) })
        return
    }
    LaunchedEffect(article) { if (article == null && (returnIndex > 0 || returnOffset > 0)) listState.scrollToItem(returnIndex, returnOffset) }
    if (manageSources) {
        BackHandler { manageSources = false }
        SourceManager(sources, failedSources, urlInput, { urlInput = it }, sourceError, { addSource() }, { manageSources = false }, { source -> persistSources(sources.map { if (it.id == source.id) it.copy(enabled = !it.enabled) else it }) }, { source, category -> persistSources(sources.map { if (it.id == source.id) it.copy(category = category) else it }) }, { source -> persistSources(sources.filterNot { it.id == source.id }); refresh() })
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
        Column(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("NewsRSS", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                        Text(
                            when {
                                loading && currentSource != null -> "Atualizando ${sources.firstOrNull { it.id == currentSource }?.name ?: "fonte"}…"
                                newItemsCount > 0 -> "$newItemsCount ${if (newItemsCount == 1) "nova notícia" else "novas notícias"}"
                                items.isNotEmpty() -> "Conteúdo atualizado em segundo plano"
                                else -> "${sources.count { it.enabled }} fontes ativas"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (newItemsCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { manageSources = true }) { Text("Fontes") }
                        Button(onClick = { refresh() }, enabled = !loading && !opening) { Text("Atualizar") }
                    }
                }
                Spacer(Modifier.height(14.dp))
                if (tab == 0) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) { TabButton("Notícias", true) { tab = 0 } }
                    Spacer(Modifier.height(10.dp))
                    CategoryFilter(selectedCategory) { selectedCategory = it }
                }
            }
            when {
                tab == 0 && loading && items.isEmpty() -> LoadingView(sources, currentSource, completedSources, failedSources)
                displayItems.isEmpty() -> EmptyState(tab)
                else -> {
                    if (tab == 0 && loading) LoadingStatus(sources, currentSource, completedSources, failedSources)
                    if (opening) Row(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Text("Abrindo notícia…", style = MaterialTheme.typography.labelLarge) }
                    Box(Modifier.fillMaxSize()) {
                        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 92.dp)) {
                            item { if (tab != 0) Text(heading, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
                            items(displayItems, key = { it.id }) { item -> NewsCard(item, sources, item.url in savedUrls) { openItem(item) } }
                        }
                        if (tab == 0 && showScrollToTop) {
                            FloatingActionButton(onClick = { scope.launch { listState.animateScrollToItem(0) } }, modifier = Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 18.dp)) { Text("↑", fontSize = 24.sp) }
                        }
                    }
                }
            }
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

@Composable private fun LoadingView(sources: List<FeedSource>, currentSource: String?, completed: Set<String>, failed: Set<String>) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(strokeWidth = 3.dp, modifier = Modifier.size(28.dp)); Column { LoadingStatus(sources, currentSource, completed, failed) }
            }
            LinearProgressIndicator(progress = { ((completed.size + failed.size).toFloat() / sources.count { it.enabled }.coerceAtLeast(1)).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable private fun LoadingStatus(sources: List<FeedSource>, currentSource: String?, completed: Set<String>, failed: Set<String>) {
    val current = sources.firstOrNull { it.id == currentSource }; val done = completed.size + failed.size
    Text(if (current != null) "Varrendo ${current.name}…" else "Preparando varredura…", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Text("$done/${sources.count { it.enabled }} fontes concluídas", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (completed.isNotEmpty() || failed.isNotEmpty()) Text(buildString { completed.mapNotNull { id -> sources.firstOrNull { it.id == id }?.name }.takeLast(3).forEach { append("✓ $it  ") }; failed.mapNotNull { id -> sources.firstOrNull { it.id == id }?.name }.takeLast(2).forEach { append("✕ $it  ") } }.trim(), style = MaterialTheme.typography.labelSmall)
}

@Composable private fun EmptyState(tab: Int) {
    Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(if (tab == 1) "Ainda não há notícias lidas" else if (tab == 2) "Sua lista está vazia" else "Nenhuma notícia encontrada", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold); Text(if (tab == 2) "Salve uma notícia para ler depois." else "Quando houver conteúdo, ele aparecerá aqui.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
}

@Composable private fun TabButton(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(modifier = Modifier.clickable(onClick = onClick), shape = MaterialTheme.shapes.large, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant, contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant, tonalElevation = if (selected) 2.dp else 0.dp) { Text(label, modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp), style = MaterialTheme.typography.labelLarge) }
}

@Composable private fun CategoryFilter(selected: NewsCategory?, onSelected: (NewsCategory?) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { TabButton("Todos", selected == null) { onSelected(null) }; NewsCategory.entries.forEach { c -> TabButton(c.label, selected == c) { onSelected(c) } } }
}

@Composable private fun SourceManager(sources: List<FeedSource>, failedSources: Set<String>, urlInput: String, onUrlChange: (String) -> Unit, sourceError: String?, onAdd: () -> Unit, onBack: () -> Unit, onToggle: (FeedSource) -> Unit, onCategoryChange: (FeedSource, NewsCategory) -> Unit, onDelete: (FeedSource) -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(14.dp)); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text("Gerenciar fontes", style = MaterialTheme.typography.headlineMedium); OutlinedButton(onClick = onBack) { Text("Voltar") } }
        Text("Ative, desative ou altere a categoria de cada fonte.", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(14.dp)); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedTextField(urlInput, onUrlChange, Modifier.weight(1f), singleLine = true, label = { Text("Adicionar site") }); Button(onClick = onAdd) { Text("Adicionar") } }
        sourceError?.let { Text(it, color = MaterialTheme.colorScheme.error) }; Spacer(Modifier.height(14.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) { items(sources, key = { it.id }) { source -> Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) { Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(source.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold); Text(source.siteUrl, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant); if (source.id in failedSources) Text("⚠ Não foi possível atualizar esta fonte", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold); TextButton(onClick = { val i = NewsCategory.entries.indexOf(source.category); onCategoryChange(source, NewsCategory.entries[(i + 1) % NewsCategory.entries.size]) }) { Text(source.category.label) } }; Switch(source.enabled, onCheckedChange = { onToggle(source) }); if (source.id.startsWith("custom-")) TextButton(onClick = { onDelete(source) }) { Text("Excluir") } } } } }
    }
}

@Composable private fun NewsCard(item: FeedItem, sources: List<FeedSource>, saved: Boolean, onClick: () -> Unit) {
    val source = sources.firstOrNull { it.id == item.sourceId }
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick), shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Column {
            item.imageUrl?.let { AsyncImage(model = it, contentDescription = item.title, modifier = Modifier.fillMaxWidth().height(190.dp), contentScale = ContentScale.Crop) }
            Column(Modifier.padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text(source?.name ?: "Fonte", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold); item.publishedAt?.let { Text(publishedLabel(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                Spacer(Modifier.height(7.dp)); Text(item.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, lineHeight = 26.sp)
                item.summary?.takeIf { it.isNotBlank() }?.let { Spacer(Modifier.height(7.dp)); Text(it, maxLines = 3, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 21.sp) }
                if (saved) { Spacer(Modifier.height(9.dp)); Text("🔖 Salva para ler depois", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
            }
        }
    }
}

@Composable private fun ReaderContent(article: Article, saved: Boolean, onBack: () -> Unit, onToggleSaved: () -> Unit) {
    val highlightColor = if (article.sourceId.lowercase(Locale.ROOT).removePrefix("www.") == "ge.globo.com") Color(0xFF168A45) else MaterialTheme.colorScheme.error
    Scaffold(topBar = { TopAppBar(title = { Text("Notícia") }, navigationIcon = { TextButton(onClick = onBack) { Text("Voltar") } }, actions = { IconButton(onClick = onToggleSaved) { Text("🔖") } }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 18.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextButton(onClick = onToggleSaved) { Text(if (saved) "Remover de Ler depois" else "🔖 Ler depois") } } }
            item { Text(article.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, lineHeight = 38.sp) }
            article.subtitle?.takeIf { it.isNotBlank() }?.let { item { Text(it, style = MaterialTheme.typography.titleMedium, lineHeight = 25.sp) } }
            article.author?.takeIf { it.isNotBlank() }?.let { item { Text("Por $it", style = MaterialTheme.typography.labelLarge) } }
            article.publishedAt?.let { item { Text(publishedLabel(it), style = MaterialTheme.typography.labelMedium) } }
            if (!article.heroImageUrl.isNullOrBlank() && article.blocks.none { it is ArticleBlock.Image && it.url == article.heroImageUrl }) item { AsyncImage(article.heroImageUrl, article.title, Modifier.fillMaxWidth().heightIn(max = 300.dp), contentScale = ContentScale.FillWidth) }
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
