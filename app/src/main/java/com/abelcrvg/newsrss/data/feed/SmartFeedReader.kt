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
    private val skySportsCrawler: SkySportsSiteCrawler = SkySportsSiteCrawler(),
    private val espnCrawler: EspnSiteCrawler = EspnSiteCrawler(),
    private val rssCrawler: RssFeedCrawler = RssFeedCrawler()
) : FeedReader {
    override suspend fun read(source: FeedSource): Result<List<FeedItem>> = withContext(Dispatchers.IO) {
        val host = runCatching { URI(source.siteUrl).host.orEmpty().removePrefix("www.").lowercase() }.getOrDefault("")
        when {
            source.id == "g1" -> g1Crawler.crawl(source)
            source.id == "ge" -> geCrawler.crawl(source)
            source.id == "uol" -> uolCrawler.crawl(source)
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
            else -> homepageCrawler.crawl(source)
        }
    }

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
        val urls = feedUrls.filterNotNull().filter(String::isNotBlank)
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
                    publishedAt = previous.publishedAt ?: feedItem.publishedAt,
                    imageUrl = previous.imageUrl?.takeIf { it.isNotBlank() } ?: feedItem.imageUrl
                )
            }
        }

        return Result.success(
            merged.values
                .sortedWith(compareByDescending<FeedItem> { it.publishedAt ?: Instant.EPOCH }.thenByDescending { it.id })
                .take(MAX_PUBLISHER_ITEMS)
        )
    }

    private companion object {
        const val MAX_PUBLISHER_ITEMS = 60
    }
}
