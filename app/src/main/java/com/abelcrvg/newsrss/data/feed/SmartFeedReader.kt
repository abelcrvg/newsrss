package com.abelcrvg.newsrss.data.feed

import android.content.Context
import com.abelcrvg.newsrss.NewsRssApplication
import com.abelcrvg.newsrss.core.feed.FeedItem
import com.abelcrvg.newsrss.core.feed.FeedReader
import com.abelcrvg.newsrss.core.model.FeedSource
import com.abelcrvg.newsrss.core.model.SourceLanguage
import com.abelcrvg.newsrss.data.translation.OnDeviceTranslator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.time.Instant

class SmartFeedReader(
    private val translationContext: Context? = runCatching { NewsRssApplication.appContext }.getOrNull(),
    private val homepageCrawler: HomepageNewsCrawler = HomepageNewsCrawler(), private val g1Crawler: G1SiteCrawler = G1SiteCrawler(), private val geCrawler: GESiteCrawler = GESiteCrawler(), private val uolCrawler: UolSiteCrawler = UolSiteCrawler(), private val tecmundoCrawler: TecmundoSiteCrawler = TecmundoSiteCrawler(), private val voxelCrawler: VoxelSiteCrawler = VoxelSiteCrawler(), private val ignCrawler: IgnBrasilSiteCrawler = IgnBrasilSiteCrawler(), private val theVergeCrawler: TheVergeSiteCrawler = TheVergeSiteCrawler(), private val skySportsCrawler: SkySportsSiteCrawler = SkySportsSiteCrawler(), private val espnCrawler: EspnSiteCrawler = EspnSiteCrawler(), private val rssCrawler: RssFeedCrawler = RssFeedCrawler()
) : FeedReader {
    override suspend fun read(source: FeedSource): Result<List<FeedItem>> = withContext(Dispatchers.IO) {
        val host = runCatching { URI(source.siteUrl).host.orEmpty().removePrefix("www.").lowercase() }.getOrDefault("")
        val result = when {
            // G1 is intentionally crawled from its live homepage only. Do not merge
            // RSS/archive items, so Home mirrors the stories currently exposed by G1.
            source.id == "g1" -> g1Crawler.crawl(source)
            source.id == "ge" -> geCrawler.crawl(source)
            source.id == "uol" -> combineWithRss(uolCrawler.crawl(source), source, listOf(source.feedUrl, "https://rss.home.uol.com.br/index.xml", "https://rss.uol.com.br/feed/noticias.xml"))
            source.id == "tecmundo" -> tecmundoCrawler.crawl(source)
            source.id == "voxel" -> voxelCrawler.crawl(source)
            source.id == "ign-brasil" -> ignCrawler.crawl(source)
            source.id == "the-verge" || host == "theverge.com" -> fallbackToGeneric(theVergeCrawler.crawl(source), source)
            source.id == "sky-sports" || host == "skysports.com" || host.endsWith(".skysports.com") -> combineWithRss(skySportsCrawler.crawl(source), source, listOf(source.feedUrl, "https://www.skysports.com/rss/12040", "https://www.skysports.com/rss/11095"))
            source.id == "espn-brasil" || host == "espn.com.br" || host.endsWith(".espn.com.br") -> combineWithRss(espnCrawler.crawl(source), source, listOf(source.feedUrl, "https://www.espn.com/espn/rss/soccer/news"))
            else -> combineWithRss(homepageCrawler.crawl(source), source, listOf(source.feedUrl))
        }
        val filtered = if (source.id == "trivela") filterTrivelaBettingContent(result) else result
        translateEnglishItems(source, filtered)
    }

    private suspend fun translateEnglishItems(source: FeedSource, result: Result<List<FeedItem>>): Result<List<FeedItem>> {
        val context = translationContext ?: return result
        if (source.language != SourceLanguage.ENGLISH || result.isFailure) return result
        val items = result.getOrNull().orEmpty()
        val targets = items.filter(::looksEnglish).take(MAX_ENGLISH_ITEMS)
        if (targets.isEmpty()) return result
        return runCatching {
            val translated = OnDeviceTranslator(context.applicationContext).translateFeedItems(targets)
            val byUrl = translated.associateBy { it.url }
            Result.success(items.map { byUrl[it.url] ?: it })
        }.getOrElse { result }
    }

    private fun looksEnglish(item: FeedItem): Boolean {
        val text = "${item.title} ${item.summary.orEmpty()}".lowercase()
        val englishSignals = listOf(" the ", " and ", " of ", " to ", " in ", " for ", " with ", " from ", " has ", " have ", " will ", " on ", " at ", " is ", " are ", " this ", " that ", " after ", " before ", " latest ", " news ", " report ")
        val portugueseSignals = listOf(" o ", " a ", " os ", " as ", " de ", " do ", " da ", " dos ", " das ", " para ", " com ", " que ", " em ", " no ", " na ", " uma ", " um ", " não ", " está ", " sobre ")
        return englishSignals.count(text::contains) >= 2 && englishSignals.count(text::contains) > portugueseSignals.count(text::contains)
    }

    private fun filterTrivelaBettingContent(result: Result<List<FeedItem>>): Result<List<FeedItem>> = result.map { items -> items.filterNot(::isBettingFocusedTrivelaArticle) }
    private fun isBettingFocusedTrivelaArticle(item: FeedItem): Boolean {
        val title = normalize(item.title); val summary = normalize(item.summary.orEmpty())
        val strong = listOf("casa de apostas", "casas de apostas", "apostas esportivas", "aposta esportiva", "apostadores", "apostador", "betting", "bookmaker", "odds", "cotacao das apostas", "cotacoes das apostas", "palpites", "prognostico", "prognosticos", "bonus de aposta", "bonus das casas", "melhores casas", "onde apostar", "como apostar", "cassino", "casino", "bet365", "betano", "sportingbet", "superbet", "novibet", "kto", "pixbet", "estrelabet")
        if (strong.any(title::contains)) return true
        val signals = listOf("apostas", "apostar", "apostadores", "odds", "betting", "bookmaker", "palpites", "prognostico", "prognosticos", "casa de apostas", "casas de apostas", "cassino", "casino", "bet365", "betano", "sportingbet", "superbet", "novibet", "kto", "pixbet", "estrelabet", "bonus de aposta")
        return signals.count { summary.contains(it) } >= 2
    }
    private fun normalize(value: String): String = value.lowercase().replace("á", "a").replace("à", "a").replace("ã", "a").replace("â", "a").replace("é", "e").replace("ê", "e").replace("í", "i").replace("ó", "o").replace("ô", "o").replace("õ", "o").replace("ú", "u").replace("ç", "c")
    private suspend fun fallbackToGeneric(specialized: Result<List<FeedItem>>, source: FeedSource): Result<List<FeedItem>> { if (specialized.isSuccess && specialized.getOrNull().orEmpty().isNotEmpty()) return specialized; val generic = homepageCrawler.crawl(source); if (generic.isSuccess && generic.getOrNull().orEmpty().isNotEmpty()) return generic; return specialized }
    private suspend fun combineWithRss(specialized: Result<List<FeedItem>>, source: FeedSource, feedUrls: List<String?>): Result<List<FeedItem>> {
        val direct = specialized.getOrNull().orEmpty(); val urls = feedUrls.filterNotNull().filter(String::isNotBlank).distinct(); val rss = if (urls.isNotEmpty()) rssCrawler.crawl(source, urls).getOrNull().orEmpty() else emptyList()
        if (direct.isEmpty() && rss.isEmpty()) { val generic = homepageCrawler.crawl(source); if (generic.isSuccess && generic.getOrNull().orEmpty().isNotEmpty()) return generic; return specialized }
        val merged = LinkedHashMap<String, FeedItem>(); direct.forEach { merged[it.url] = it }; rss.forEach { feedItem -> val previous = merged[feedItem.url]; merged[feedItem.url] = if (previous == null) feedItem else previous.copy(title = previous.title.ifBlank { feedItem.title }, summary = previous.summary?.takeIf { it.isNotBlank() } ?: feedItem.summary, publishedAt = feedItem.publishedAt ?: previous.publishedAt, imageUrl = previous.imageUrl?.takeIf { it.isNotBlank() } ?: feedItem.imageUrl) }
        return Result.success(merged.values.sortedWith(compareByDescending<FeedItem> { it.publishedAt ?: Instant.EPOCH }.thenBy { it.title.lowercase() }).take(MAX_PUBLISHER_ITEMS))
    }
    private companion object { const val MAX_PUBLISHER_ITEMS = 60; const val MAX_ENGLISH_ITEMS = 20 }
}
