package com.abelcrvg.newsrss.data.feed

import com.abelcrvg.newsrss.core.feed.FeedItem
import com.abelcrvg.newsrss.core.feed.FeedReader
import com.abelcrvg.newsrss.core.model.FeedSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.time.Instant

/** Selects the best crawler automatically and combines direct HTML with RSS metadata for difficult publishers. */
class SmartFeedReader(
    private val homepageCrawler: HomepageNewsCrawler = HomepageNewsCrawler(),
    private val g1Crawler: G1SiteCrawler = G1SiteCrawler(),
    private val geCrawler: GESiteCrawler = GESiteCrawler(),
    private val uolCrawler: UolSiteCrawler = UolSiteCrawler(),
    private val tecmundoCrawler: TecmundoSiteCrawler = TecmundoSiteCrawler(),
    private val voxelCrawler: VoxelSiteCrawler = VoxelSiteCrawler(),
    private val ignCrawler: IgnBrasilSiteCrawler = IgnBrasilSiteCrawler(),
    private val theVergeCrawler: TheVergeSiteCrawler = TheVergeSiteCrawler(),
    private val skySportsCrawler: SkySportsCrawler = SkySportsCrawler(),
    private val espnCrawler: EspnSiteCrawler = EspnSiteCrawler(),
    private val rssCrawler: RssFeedCrawler = RssFeedCrawler()
) : FeedReader {
    override suspend fun read(source: FeedSource): Result<List<FeedItem>> = withContext(Dispatchers.IO) {
        val host = runCatching { URI(source.siteUrl).host.orEmpty().removePrefix("www.").lowercase() }.getOrDefault("")
        val result = when {
            source.id == "g1" -> combineWithRss(
                specialized = g1Crawler.crawl(source),
                source = source,
                feedUrls = listOf(source.feedUrl, "https://g1.globo.com/dynamo/rss2.xml")
            )
            source.id == "ge" -> geCrawler.crawl(source)
            source.id == "uol" -> combineWithRss(
                specialized = uolCrawler.crawl(source),
                source = source,
                feedUrls = listOf(source.feedUrl, "https://rss.home.uol.com.br/index.xml", "https://rss.uol.com.br/feed/noticias.xml")
            )
            source.id == "tecmundo" -> tecmundoCrawler.crawl(source)
            source.id == "voxel" -> voxelCrawler.crawl(source)
            source.id == "ign-brasil" -> ignCrawler.crawl(source)
            source.id == "the-verge" || host == "theverge.com" -> fallbackToGeneric(theVergeCrawler.crawl(source), source)
            source.id == "sky-sports" || host == "skysports.com" || host.endsWith(".skysports.com") ->
                combineWithRss(
                    specialized = skySportsCrawler.crawl(source),
                    source = source,
                    feedUrls = listOf(source.feedUrl, "https://www.skysports.com/rss/12040", "https://www.skysports.com/rss/11095")
                )
            source.id == "espn-brasil" || host == "espn.com.br" || host.endsWith(".espn.com.br") ->
                combineWithRss(
                    specialized = espnCrawler.crawl(source),
                    source = source,
                    feedUrls = listOf(source.feedUrl, "https://www.espn.com/espn/rss/soccer/news")
                )
            else -> combineWithRss(
                specialized = homepageCrawler.crawl(source),
                source = source,
                feedUrls = listOf(source.feedUrl)
            )
        }

        if (source.id == "trivela") filterTrivelaBettingContent(result) else result
    }

    private fun filterTrivelaBettingContent(result: Result<List<FeedItem>>): Result<List<FeedItem>> =
        result.map { items -> items.filterNot(::isBettingFocusedTrivelaArticle) }

    /** Trivela also publishes betting/affiliate content. */
    private fun isBettingFocusedTrivelaArticle(item: FeedItem): Boolean {
        val title = normalize(item.title)
        val summary = normalize(item.summary.orEmpty())
        val strongTitleTerms = listOf(
            "casa de apostas", "casas de apostas", "apostas esportivas", "aposta esportiva",
            "apostadores", "apostador", "betting", "bookmaker", "odds", "cotacao das apostas",
            "cotacoes das apostas", "palpites", "prognostico", "prognosticos", "bonus de aposta",
            "bonus das casas", "melhores casas", "onde apostar", "como apostar", "cassino",
            "casino", "bet365", "betano", "sportingbet", "superbet", "novibet", "kto",
            "pixbet", "estrelabet"
        )
        if (strongTitleTerms.any(title::contains)) return true
        val bettingSignals = listOf(
            "apostas", "apostar", "apostadores", "odds", "betting", "bookmaker", "palpites",
            "prognostico", "prognosticos", "casa de apostas", "casas de apostas", "cassino",
            "casino", "bet365", "betano", "sportingbet", "superbet", "novibet", "kto",
            "pixbet", "estrelabet", "bonus de aposta"
        )
        return bettingSignals.count { summary.contains(it) } >= 2
    }

    private fun normalize(value: String): String =
        value.lowercase()
            .replace("á", "a").replace("à", "a").replace("ã", "a").replace("â", "a")
            .replace("é", "e").replace("ê", "e").replace("í", "i").replace("ó", "o")
            .replace("ô", "o").replace("õ", "o").replace("ú", "u").replace("ç", "c")

    private suspend fun fallbackToGeneric(specialized: Result<List<FeedItem>>, source: FeedSource): Result<List<FeedItem>> {
        if (specialized.isSuccess && specialized.getOrNull().orEmpty().isNotEmpty()) return specialized
        val generic = homepageCrawler.crawl(source)
        if (generic.isSuccess && generic.getOrNull().orEmpty().isNotEmpty()) return generic
        return specialized
    }

    private suspend fun combineWithRss(
        specialized: Result<List<FeedItem>>,
        source: FeedSource,
        feedUrls: List<String?>
    ): Result<List<FeedItem>> {
        val direct = specialized.getOrNull().orEmpty()
        val urls = feedUrls.filterNotNull().filter(String::isNotBlank).distinct()
        val rss = if (urls.isNotEmpty()) rssCrawler.crawl(source, urls).getOrNull().orEmpty() else emptyList()

        if (direct.isEmpty() && rss.isEmpty()) {
            val generic = homepageCrawler.crawl(source)
            if (generic.isSuccess && generic.getOrNull().orEmpty().isNotEmpty()) return generic
            return specialized
        }

        val merged = LinkedHashMap<String, FeedItem>()
        direct.forEach { merged[it.url] = it }
        rss.forEach { feedItem ->
            val previous = merged[feedItem.url]
            merged[feedItem.url] = if (previous == null) {
                feedItem
            } else {
                previous.copy(
                    title = previous.title.ifBlank { feedItem.title },
                    summary = previous.summary?.takeIf { it.isNotBlank() } ?: feedItem.summary,
                    // RSS publication time is authoritative when available: homepage HTML can
                    // expose an update/modified time or an ambiguous relative label.
                    publishedAt = feedItem.publishedAt ?: previous.publishedAt,
                    imageUrl = previous.imageUrl?.takeIf { it.isNotBlank() } ?: feedItem.imageUrl
                )
            }
        }

        return Result.success(
            merged.values
                .sortedWith(compareByDescending<FeedItem> { it.publishedAt ?: Instant.EPOCH }.thenBy { it.title.lowercase() })
                .take(MAX_PUBLISHER_ITEMS)
        )
    }

    private companion object {
        const val MAX_PUBLISHER_ITEMS = 60
    }
}
