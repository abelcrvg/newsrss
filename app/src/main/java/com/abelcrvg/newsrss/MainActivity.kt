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
    fun markRead(item: FeedItem) { if (item.url !in readUrls) { readUrls = readUrls + item.url; readItems = (listOf(item) + readItems).distinctBy { it.url }.take(300); scope.launch(Dispatchers.IO) { readStore.markRead(item) } } }
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
                async(Dispatchers.IO) {
                    source.id to runCatching { reader.read(source) }.getOrElse { Result.failure(it) }
                }
            }.awaitAll()
            val incoming = buildList {
                results.forEach { pair ->
                    val id = pair.first
                    val result = pair.second
                    result.getOrNull().orEmpty().take(80).forEach { add(it.copy(sourceId = id)) }
                }
            }
            failedSources = results.filter { it.second.isFailure }.mapTo(linkedSetOf()) { it.first }
            val merged = mergeFeedItems(items, incoming).take(800)
            items = merged
            newItems = incoming.count { it.url !in old }
            refreshing = false
            if (incoming.isNotEmpty()) withContext(Dispatchers.IO) { cacheStore.merge(incoming) }
        }
    }
    fun openItem(item: FeedItem) {
        currentItem = item; markRead(item); opening = true; error = null
        scope.launch(Dispatchers.IO) {
            val result = JsoupArticleExtractor().extract(item.url)
            val raw = result.getOrNull()
            val translated = if (raw != null && sourceById[item.sourceId]?.language == SourceLanguage.ENGLISH) runCatching { OnDeviceTranslator(app).translateArticle(raw) }.getOrNull() else raw
            withContext(Dispatchers.Main.immediate) {
                if (translated != null) article = translated.copy(publishedAt = translated.publishedAt ?: item.publishedAt) else error = result.exceptionOrNull()?.message ?: "Não foi possível abrir a notícia."
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
        val host = uri.host.removePrefix("www."); val base = "custom-" + host.replace(Regex("[^a-zA-Z0-9]+"), "-").trim('-').lowercase(Locale.ROOT)
        val id = if (sources.none { it.id == base }) base else "$base-${normalized.hashCode().toUInt().toString(16)}"
        saveSources(sources + FeedSource(id, host.substringBefore('.').replaceFirstChar { it.uppercase() }, normalized, category = NewsCategory.NEWS)); urlInput = ""
    }

    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) { InitialData(sourceStore.load(SourceRegistry.defaultSources), readStore.load(), readStore.loadItems(), savedStore.load(), savedStore.loadItems(), cacheStore.load()) }
        sources = loaded.sources; readUrls = loaded.readUrls; readItems = loaded.readItems.take(300); savedUrls = loaded.savedUrls; savedItems = loaded.savedItems.take(300); items = loaded.items.take(800); initialized = true
        NewsRefreshScheduler.schedule(app); refresh()
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

    val unread = remember(items, readUrls) { items.filterNot { it.url in readUrls } }
    val filtered = remember(unread, category, sourceById) { if (category == null) unread else unread.filter { sourceById[it.sourceId]?.category == category && sourceById[it.sourceId]?.enabled == true } }
    val display = when (tab) { 1 -> readItems; 2 -> savedItems; else -> filtered }
    Scaffold(bottomBar = { NavigationBar { NavigationBarItem(tab == 0, { tab = 0 }, icon = { Text("●") }, label = { Text("Notícias") }); NavigationBarItem(tab == 1, { tab = 1 }, icon = { Text("✓") }, label = { Text("Lidas") }); NavigationBarItem(tab == 2, { tab = 2 }, icon = { Text("★") }, label = { Text("Ler depois") }) } }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(16.dp, 14.dp, 16.dp, 96.dp)) {
                item { HomeHeader(refreshing, initialized, newItems, sources.count { it.enabled }, { manageSources = true }, { refresh() }) }
                if (tab == 0) item { CategoryFilter(category) { category = it } } else item { Text(if (tab == 1) "Notícias lidas" else "Ler depois", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
                if (opening) item { LoadingCard("Traduzindo e abrindo notícia…", true) }
                if (refreshing) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }