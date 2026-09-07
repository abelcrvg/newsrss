package com.abelcrvg.newsrss.data.feed

import com.abelcrvg.newsrss.core.feed.FeedItem
import com.abelcrvg.newsrss.core.feed.FeedReader
import com.abelcrvg.newsrss.core.model.FeedSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI

/** Selects the best crawler automatically from the source URL; users never configure a crawler manually. */
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
    private val espnCrawler: EspnSiteCrawler = EspnSiteCrawler()
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
            source.id == "the-verge" || host == "theverge.com" -> theVergeCrawler.crawl(source)
            host == "skysports.com" -> skySportsCrawler.crawl(source)
            host == "espn.com.br" || host.endsWith(".espn.com.br") -> espnCrawler.crawl(source)
            else -> homepageCrawler.crawl(source)
        }
    }
}
