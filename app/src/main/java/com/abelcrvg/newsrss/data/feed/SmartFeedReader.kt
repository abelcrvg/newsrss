package com.abelcrvg.newsrss.data.feed

import com.abelcrvg.newsrss.core.feed.FeedItem
import com.abelcrvg.newsrss.core.feed.FeedReader
import com.abelcrvg.newsrss.core.model.FeedSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Reads bundled sources directly from their websites; generic crawling remains only for custom sources. */
class SmartFeedReader(
    private val homepageCrawler: HomepageNewsCrawler = HomepageNewsCrawler(),
    private val g1Crawler: G1SiteCrawler = G1SiteCrawler(),
    private val geCrawler: GESiteCrawler = GESiteCrawler(),
    private val uolCrawler: UolSiteCrawler = UolSiteCrawler(),
    private val tecmundoCrawler: TecmundoSiteCrawler = TecmundoSiteCrawler(),
    private val voxelCrawler: VoxelSiteCrawler = VoxelSiteCrawler(),
    private val ignCrawler: IgnBrasilSiteCrawler = IgnBrasilSiteCrawler(),
    private val theVergeCrawler: TheVergeSiteCrawler = TheVergeSiteCrawler()
) : FeedReader {
    override suspend fun read(source: FeedSource): Result<List<FeedItem>> = withContext(Dispatchers.IO) {
        when (source.id) {
            "g1" -> g1Crawler.crawl(source)
            "ge" -> geCrawler.crawl(source)
            "uol" -> uolCrawler.crawl(source)
            "tecmundo" -> tecmundoCrawler.crawl(source)
            "voxel" -> voxelCrawler.crawl(source)
            "ign-brasil" -> ignCrawler.crawl(source)
            "the-verge" -> theVergeCrawler.crawl(source)
            else -> homepageCrawler.crawl(source)
        }
    }
}
