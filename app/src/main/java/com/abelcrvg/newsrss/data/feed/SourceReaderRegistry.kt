package com.abelcrvg.newsrss.data.feed

import com.abelcrvg.newsrss.core.feed.FeedItem
import com.abelcrvg.newsrss.core.model.FeedSource
import java.net.URI
import java.time.Instant

/** Routes each source to its best ingestion strategy without coupling the UI to crawlers. */
class SourceReaderRegistry(
    private val homepageCrawler: HomepageNewsCrawler = HomepageNewsCrawler(),
    private val g1Crawler: G1SiteCrawler = G1SiteCrawler(),
    private val geCrawler: GESiteCrawler = GESiteCrawler(),
    private val uolCrawler: UolSiteCrawler = UolSiteCrawler(),
    private val tecmundoCrawler: TecmundoSiteCrawler = TecmundoSiteCrawler(),
    private val voxelCrawler: VoxelSiteCrawler = VoxelSiteCrawler(),
    private val ignCrawler: IgnBrasilSiteCrawler = IgnBrasilSiteCrawler(),
    private val theVergeCrawler: TheVergeSiteCrawler = TheVergeSiteCrawler(),
    private val skySportsCrawler: SkySportsSiteCrawler = SkySportsSiteCrawler(),
    private val espnCrawler: EspnSiteCrawler = EspnSiteCrawler(),
    private val cnnCrawler: CnnBrasilSiteCrawler = CnnBrasilSiteCrawler(),
    private val adrenalineCrawler: AdrenalineSiteCrawler = AdrenalineSiteCrawler(),
    private val reutersCrawler: ReutersSiteCrawler = ReutersSiteCrawler(),
    private val apCrawler: ApNewsSiteCrawler = ApNewsSiteCrawler(),
    private val rssCrawler: RssFeedCrawler = RssFeedCrawler()
) {
    suspend fun read(source: FeedSource): Result<List<FeedItem>> {
        val host = runCatching { URI(source.siteUrl).host.orEmpty().removePrefix("www.").lowercase() }.getOrDefault("")
        return when {
            source.id == "g1" -> g1Crawler.crawl(source)
            source.id == "ge" -> geCrawler.crawl(source)
            source.id == "uol" -> combineWithRss(uolCrawler.crawl(source), source, listOf(source.feedUrl, "https://rss.home.uol.com.br/index.xml", "https://rss.uol.com.br/feed/noticias.xml"))
            source.id == "tecmundo" -> tecmundoCrawler.crawl(source)
            source.id == "voxel" -> voxelCrawler.crawl(source)
            source.id == "ign-brasil" -> ignCrawler.crawl(source)
            source.id == "the-verge" || host == "theverge.com" -> fallbackToGeneric(theVergeCrawler.crawl(source), source)
            source.id == "sky-sports" || host == "skysports.com" || host.endsWith(".skysports.com") -> combineWithRss(skySportsCrawler.crawl(source), source, listOf(source.feedUrl, "https://www.skysports.com/rss/12040", "https://www.skysports.com/rss/11095"))
            source.id == "espn-brasil" || host == "espn.com.br" || host.endsWith(".espn.com.br") -> combineWithRss(espnCrawler.crawl(source), source, listOf(source.feedUrl, "https://www.espn.com/espn/rss/soccer/news"))
            source.id == "cnn-brasil" -> combineWithRss(cnnCrawler.crawl(source), source, listOf(source.feedUrl, "https://www.cnnbrasil.com.br/feed/"))
            source.id == "adrenaline" -> adrenalineCrawler.crawl(source)
            source.id == "reuters" -> fallbackToReuters(reutersCrawler.crawl(source), source)
            source.id == "ap-news" -> fallbackToGeneric(apCrawler.crawl(source), source)
            else -> combineWithRss(homepageCrawler.crawl(source), source, listOf(source.feedUrl))
        }
    }

    private suspend fun fallbackToReuters(direct: Result<List<FeedItem>>, source: FeedSource): Result<List<FeedItem>> {
        if (direct.isSuccess && direct.getOrNull().orEmpty().isNotEmpty()) return direct
        // Reuters currently exposes its headlines inconsistently to unauthenticated RSS clients.
        // Keep Reuters URLs as the source while using a search RSS fallback for availability.
        val mirror = "https://news.google.com/rss/search?q=site%3Areuters.com&hl=en-US&gl=US&ceid=US%3Aen"
        val rss = rssCrawler.crawl(source, listOf(mirror))
        return if (rss.isSuccess && rss.getOrNull().orEmpty().isNotEmpty()) rss else direct
    }

    private suspend fun fallbackToGeneric(specialized: Result<List<FeedItem>>, source: FeedSource): Result<List<FeedItem>> {
        if (specialized.isSuccess && specialized.getOrNull().orEmpty().isNotEmpty()) return specialized
        val generic = homepageCrawler.crawl(source)
        return if (generic.isSuccess && generic.getOrNull().orEmpty().isNotEmpty()) generic else specialized
    }

    private suspend fun combineWithRss(specialized: Result<List<FeedItem>>, source: FeedSource, feedUrls: List<String?>): Result<List<FeedItem>> {
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
            merged[feedItem.url] = if (previous == null) feedItem else previous.copy(
                title = feedItem.title.ifBlank { previous.title },
                summary = feedItem.summary?.takeIf { it.isNotBlank() } ?: previous.summary,
                publishedAt = feedItem.publishedAt ?: previous.publishedAt,
                imageUrl = feedItem.imageUrl?.takeIf { it.isNotBlank() } ?: previous.imageUrl
            )
        }
        return Result.success(merged.values.sortedWith(compareByDescending<FeedItem> { it.publishedAt ?: Instant.EPOCH }.thenBy { it.title.lowercase() }).take(MAX_PUBLISHER_ITEMS))
    }

    private companion object { const val MAX_PUBLISHER_ITEMS = 60 }
}
