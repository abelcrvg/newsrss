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
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
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
import com.abelcrvg.newsrss.core.source.SourceRegistry
import com.abelcrvg.newsrss.data.extraction.JsoupArticleExtractor
import com.abelcrvg.newsrss.data.feed.SmartFeedReader
import com.abelcrvg.newsrss.data.source.ReadArticleStore
import com.abelcrvg.newsrss.data.source.SavedArticleStore
import com.abelcrvg.newsrss.data.source.SourceStore
import com.abelcrvg.newsrss.data.translation.OnDeviceTranslator
import com.abelcrvg.newsrss.ui.theme.NewsRSSTheme
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
    val readStore = remember { ReadArticleStore(context.applicationContext) }
    val savedStore = remember { SavedArticleStore(context.applicationContext) }
    var sources by remember { mutableStateOf(sourceStore.load(SourceRegistry.defaultSources)) }
    var readUrls by remember { mutableStateOf(readStore.load()) }
    var readItems by remember { mutableStateOf(readStore.loadItems()) }
    var savedUrls by remember { mutableStateOf(savedStore.load()) }
    var savedItems by remember { mutableStateOf(savedStore.loadItems()) }
    var items by remember { mutableStateOf<List<FeedItem>>(emptyList()) }
    var article by remember { mutableStateOf<Article?>(null) }
    var currentItem by remember { mutableStateOf<FeedItem?>(null) }
    var loading by remember { mutableStateOf(false) }
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

    fun persistSources(value: List<FeedSource>) { sources = value; sourceStore.save(value) }
    fun markRead(item: FeedItem) { readStore.markRead(item); readUrls = readStore.load(); readItems = readStore.loadItems() }
    fun toggleSaved(item: FeedItem) { val save = item.url !in savedUrls; savedStore.setSaved(item, save); savedUrls = savedStore.load(); savedItems = savedStore.loadItems() }

    fun refresh() {
        if (loading) return
        loading = true; error = null
        scope.launch {
            val results = coroutineScope { sources.filter { it.enabled }.map { source -> async { source to SmartFeedReader().read(source) } }.awaitAll() }
            val successful = results.flatMap { (source, result) -> result.getOrElse { emptyList() }.map { it.copy(sourceId = source.id) } }
            val failures = results.filter { it.second.isFailure }
            items = successful.distinctBy { it.url }.sortedByDescending { it.publishedAt ?: Instant.EPOCH }
            error = when {
                successful.isEmpty() -> failures.firstOrNull()?.second?.exceptionOrNull()?.message ?: "Nenhuma fonte conseguiu fornecer notícias."
                failures.isNotEmpty() -> "Algumas fontes não puderam ser atualizadas."
                else -> null
            }
            loading = false
        }
    }

    fun openItem(item: FeedItem) {
        returnIndex = listState.firstVisibleItemIndex; returnOffset = listState.firstVisibleItemScrollOffset
        currentItem = item; markRead(item); opening = true; error = null
        scope.launch {
            JsoupArticleExtractor().extract(item.url).onSuccess { extracted ->
                article = if (sources.firstOrNull { it.id == item.sourceId }?.category == NewsCategory.ENGLISH) OnDeviceTranslator(context.applicationContext).translateArticle(extracted) else extracted
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
        val id = "custom-${host.replace(Regex("[^a-zA-Z0-9]"), "-")}"
        if (sources.any { it.id == id }) { sourceError = "Essa fonte já está adicionada."; return }
        persistSources(sources + FeedSource(id, host.substringBefore('.').replaceFirstChar { it.uppercase() }, normalized, category = NewsCategory.NEWS)); urlInput = ""; refresh()
    }

    LaunchedEffect(Unit) { refresh() }
    val visibleItems = remember(items, sources, selectedCategory, readUrls) {
        val unread = items.filterNot { it.url in readUrls }
        selectedCategory?.let { category -> unread.filter { item -> sources.filter { it.enabled && it.category == category }.any { it.id == item.sourceId } } } ?: unread
    }
    if (article != null && currentItem != null) {
        BackHandler { article = null }
        ReaderContent(article!!, currentItem!!.url in savedUrls, { article = null }, { toggleSaved(currentItem!!) })
        return
    }
    LaunchedEffect(article) { if (article == null && (returnIndex > 0 || returnOffset > 0)) listState.scrollToItem(returnIndex, returnOffset) }
    if (manageSources) {
        BackHandler { manageSources = false }
        SourceManager(sources, urlInput, { urlInput = it }, sourceError, { addSource() }, { manageSources = false },
            { source -> persistSources(sources.map { if (it.id == source.id) it.copy(enabled = !it.enabled) else it }) },
            { source, category -> persistSources(sources.map { if (it.id == source.id) it.copy(category = category) else it }) },
            { source -> persistSources(sources.filterNot { it.id == source.id }); refresh() })
        return
    }
    val displayItems = when (tab) { 1 -> readItems; 2 -> savedItems; else -> visibleItems }
    val heading = when (tab) { 1 -> "Notícias lidas"; 2 -> "Ler depois"; else -> selectedCategory?.label ?: "Principais e recentes" }
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    Column { Text("NewsRSS", style = MaterialTheme.typography.headlineLarge); Text("${sources.count { it.enabled }} fontes ativas", style = MaterialTheme.typography.bodyMedium) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton(onClick = { manageSources = true }) { Text("Fontes") }; Button(onClick = { refresh() }, enabled = !loading && !opening) { Text("Atualizar") } }
                }
                Spacer(Modifier.height(12.dp)); Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) { TabButton("Notícias", tab == 0) { tab = 0 }; TabButton("Lidas (${readItems.size})", tab == 1) { tab = 1 }; TabButton("Ler depois (${savedItems.size})", tab == 2) { tab = 2 } }
                if (tab == 0) { Spacer(Modifier.height(10.dp)); CategoryFilter(selectedCategory) { selectedCategory = it } }
            }
            when {
                tab == 0 && loading && items.isEmpty() -> LoadingView()
                tab == 0 && error != null && items.isEmpty() -> ErrorView(error!!) { refresh() }
                displayItems.isEmpty() -> Text(if (tab == 1) "Você ainda não leu nenhuma notícia." else if (tab == 2) "Nenhuma notícia salva para ler depois." else "Nenhuma notícia encontrada.", Modifier.padding(20.dp))
                else -> {
                    if (error != null && tab == 0) Text(error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 20.dp))
                    if (opening) Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { CircularProgressIndicator(); Text("Abrindo notícia...") }
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(20.dp)) {
                        item { Text(heading, style = MaterialTheme.typography.headlineSmall) }
                        items(displayItems, key = { it.id }) { item -> NewsCard(item, sources, item.url in savedUrls) { openItem(item) } }
                    }
                }
            }
        }
    }
}

@Composable private fun LoadingView() { Column(Modifier.padding(20.dp)) { CircularProgressIndicator(); Spacer(Modifier.height(12.dp)); Text("Atualizando todas as fontes...") } }
@Composable private fun ErrorView(message: String, onRetry: () -> Unit) { Column(Modifier.padding(20.dp)) { Text(message, color = MaterialTheme.colorScheme.error); Spacer(Modifier.height(12.dp)); Button(onClick = onRetry) { Text("Tentar novamente") } } }
@Composable private fun TabButton(label: String, selected: Boolean, onClick: () -> Unit) { if (selected) Button(onClick) { Text(label) } else OutlinedButton(onClick) { Text(label) } }
@Composable private fun CategoryFilter(selected: NewsCategory?, onSelected: (NewsCategory?) -> Unit) { Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), Arrangement.spacedBy(8.dp)) { TabButton("Todos", selected == null) { onSelected(null) }; NewsCategory.entries.forEach { c -> TabButton(c.label, selected == c) { onSelected(c) } } } }

@Composable
private fun SourceManager(sources: List<FeedSource>, urlInput: String, onUrlChange: (String) -> Unit, sourceError: String?, onAdd: () -> Unit, onBack: () -> Unit, onToggle: (FeedSource) -> Unit, onCategoryChange: (FeedSource, NewsCategory) -> Unit, onDelete: (FeedSource) -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(14.dp)); Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) { Text("Gerenciar fontes", style = MaterialTheme.typography.headlineMedium); OutlinedButton(onClick = onBack) { Text("Voltar") } }
        Text("Ative, desative ou altere a categoria de cada fonte.", style = MaterialTheme.typography.bodyMedium); Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) { OutlinedTextField(urlInput, onUrlChange, Modifier.weight(1f), singleLine = true, label = { Text("Adicionar site") }); Button(onClick = onAdd) { Text("Adicionar") } }
        sourceError?.let { Text(it, color = MaterialTheme.colorScheme.error) }; Spacer(Modifier.height(14.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) { items(sources, key = { it.id }) { source -> Card(Modifier.fillMaxWidth()) { Row(Modifier.fillMaxWidth().padding(14.dp), Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(source.name, style = MaterialTheme.typography.titleMedium); Text(source.siteUrl, style = MaterialTheme.typography.bodySmall); TextButton(onClick = { val i = NewsCategory.entries.indexOf(source.category); onCategoryChange(source, NewsCategory.entries[(i + 1) % NewsCategory.entries.size]) }) { Text(source.category.label) } }; Switch(source.enabled, onCheckedChange = { onToggle(source) }); if (source.id.startsWith("custom-")) TextButton(onClick = { onDelete(source) }) { Text("Excluir") } } } } }
    }
}

@Composable
private fun NewsCard(item: FeedItem, sources: List<FeedSource>, saved: Boolean, onClick: () -> Unit) {
    val source = sources.firstOrNull { it.id == item.sourceId }
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) { Column { item.imageUrl?.let { AsyncImage(it, item.title, Modifier.fillMaxWidth().height(190.dp), contentScale = ContentScale.Crop) }; Column(Modifier.padding(14.dp)) { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Text(source?.name ?: "Fonte", style = MaterialTheme.typography.labelMedium); item.publishedAt?.let { Text(publishedLabel(it), style = MaterialTheme.typography.labelMedium) } }; Spacer(Modifier.height(8.dp)); Text(item.title, style = MaterialTheme.typography.titleLarge); item.summary?.takeIf { it.isNotBlank() }?.let { Spacer(Modifier.height(6.dp)); Text(it, maxLines = 3, style = MaterialTheme.typography.bodyMedium) }; if (saved) { Spacer(Modifier.height(6.dp)); Text("🔖 Salva para ler depois", style = MaterialTheme.typography.labelMedium) } } } }
}

@Composable
private fun ReaderContent(article: Article, saved: Boolean, onBack: () -> Unit, onToggleSaved: () -> Unit) {
    val red = MaterialTheme.colorScheme.error
    Scaffold(topBar = { TopAppBar(title = { Text("Notícia") }, navigationIcon = { TextButton(onClick = onBack) { Text("Voltar") } }, actions = { IconButton(onClick = onToggleSaved) { Text("🔖") } }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 18.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            item { Row(Modifier.fillMaxWidth(), Arrangement.End) { TextButton(onClick = onToggleSaved) { Text(if (saved) "Remover de Ler depois" else "🔖 Ler depois") } } }
            item { Text(article.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, lineHeight = 38.sp) }
            article.subtitle?.takeIf { it.isNotBlank() }?.let { item { Text(it, style = MaterialTheme.typography.titleMedium, lineHeight = 25.sp) } }
            article.author?.takeIf { it.isNotBlank() }?.let { item { Text("Por $it", style = MaterialTheme.typography.labelLarge) } }
            article.publishedAt?.let { item { Text(publishedLabel(it), style = MaterialTheme.typography.labelMedium) } }
            if (!article.heroImageUrl.isNullOrBlank() && article.blocks.none { it is ArticleBlock.Image && it.url == article.heroImageUrl }) item { AsyncImage(article.heroImageUrl, article.title, Modifier.fillMaxWidth().heightIn(max = 300.dp), contentScale = ContentScale.FillWidth) }
            article.blocks.forEach { block -> item {
                when (block) {
                    is ArticleBlock.Paragraph -> Text(inlineAnnotated(block, red), style = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp, lineHeight = 29.sp))
                    is ArticleBlock.Heading -> Text(block.text, style = if (block.level <= 2) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, lineHeight = 31.sp)
                    is ArticleBlock.Image -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { AsyncImage(block.url, block.altText ?: block.caption, Modifier.fillMaxWidth().heightIn(max = 340.dp), contentScale = ContentScale.FillWidth); block.caption?.let { Text(it, style = MaterialTheme.typography.bodySmall) } }
                    is ArticleBlock.Quote -> Text("“${block.text}”${block.author?.let { " — $it" } ?: ""}", style = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp, lineHeight = 28.sp), fontStyle = FontStyle.Italic)
                    is ArticleBlock.ListBlock -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { block.items.forEachIndexed { i, text -> Text(if (block.ordered) "${i + 1}. $text" else "• $text", style = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp, lineHeight = 28.sp)) } }
                }
            } }
        }
    }
}

private fun inlineAnnotated(block: ArticleBlock.Paragraph, red: Color): AnnotatedString {
    val html = block.inlineHtml ?: return AnnotatedString(block.text)
    val root = org.jsoup.Jsoup.parseBodyFragment(html).body()
    return androidx.compose.ui.text.buildAnnotatedString { root.childNodes().forEach { appendInline(it, false, false, false, red) } }
}

private fun androidx.compose.ui.text.AnnotatedString.Builder.appendInline(node: Node, bold: Boolean, italic: Boolean, red: Boolean, redColor: Color) {
    if (node is TextNode) {
        val style = SpanStyle(fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal, color = if (red) redColor else Color.Unspecified)
        withStyle(style) { append(node.text()) }
        return
    }
    if (node !is Element) return
    val tag = node.tagName().lowercase()
    if (tag == "br") { append("\n"); return }
    val nextBold = bold || tag == "b" || tag == "strong"
    val nextItalic = italic || tag == "i" || tag == "em"
    val nextRed = red || tag == "a" || tag == "mark"
    node.childNodes().forEach { appendInline(it, nextBold, nextItalic, nextRed, redColor) }
}

private fun publishedLabel(value: Instant): String = value.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.getDefault()))
