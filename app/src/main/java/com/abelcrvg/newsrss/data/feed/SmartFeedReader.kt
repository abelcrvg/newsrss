package com.abelcrvg.newsrss.data.feed

import com.abelcrvg.newsrss.core.feed.FeedItem
import com.abelcrvg.newsrss.core.feed.FeedReader
import com.abelcrvg.newsrss.core.model.FeedSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI

/** Selects the best crawler automatically and uses RSS only as a last-resort publisher fallback. */
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
            source.id == "the-verge" || host == "theverge.com" ->
                fallbackToGeneric(theVergeCrawler.crawl(source), source)
            source.id == "sky-sports" || host == "skysports.com" || host.endsWith(".skysports.com") ->
                fallbackToRss(
                    specialized = skySportsCrawler.crawl(source),
                    source = source,
                    feedUrls = listOf(
                        source.feedUrl,
                        "https://www.skysports.com/rss/12040",
                        "https://www.skysports.com/rss/11095"
                    )
                )
            source.id == "espn-brasil" || host == "espn.com.br" || host.endsWith(".espn.com.br") ->
                fallbackToRss(
                    specialized = espnCrawler.crawl(source),
                    source = source,
                    feedUrls = listOf(
                        source.feedUrl,
                        "https://www.espn.com/espn/rss/soccer/news"
                    )
                )
            else -> homepageCrawler.crawl(source)
        }
    }

    private suspend fun fallbackToGeneric(
        specialized: Result<List<FeedItem>>,
        source: FeedSource
    ): Result<List<FeedItem>> {
        if (specialized.isSuccess && specialized.getOrNull().orEmpty().isNotEmpty()) return specialized
        val generic = homepageCrawler.crawl(source)
        if (generic.isSuccess && generic.getOrNull().orEmpty().isNotEmpty()) return generic
        return specialized
    }

    private suspend fun fallbackToRss(
        specialized: Result<List<FeedItem>>,
        source: FeedSource,
        feedUrls: List<String?>
    ): Result<List<FeedItem>> {
        if (specialized.isSuccess && specialized.getOrNull().orEmpty().isNotEmpty()) return specialized

        val generic = runCatching { homepageCrawler.crawl(source).getOrThrow() }
        if (generic.isSuccess && generic.getOrNull().orEmpty().isNotEmpty()) return generic

        val urls = feedUrls.filterNotNull().filter(String::isNotBlank)
        if (urls.isNotEmpty()) {
            val rss = rssCrawler.crawl(source, urls)
            if (rss.isSuccess && rss.getOrNull().orEmpty().isNotEmpty()) return rss
        }

        return specialized
    }
}
