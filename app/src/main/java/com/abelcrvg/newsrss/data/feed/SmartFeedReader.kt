package com.abelcrvg.newsrss.data.feed

import com.abelcrvg.newsrss.core.feed.FeedItem
import com.abelcrvg.newsrss.core.feed.FeedReader
import com.abelcrvg.newsrss.core.model.FeedSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

/** Uses RSS/Atom first and supplements it with direct site crawling when useful. */
class SmartFeedReader(
    private val rssReader: FeedReader = JsoupFeedReader(),
    private val homepageCrawler: HomepageNewsCrawler = HomepageNewsCrawler()
) : FeedReader {
    override suspend fun read(source: FeedSource): Result<List<FeedItem>> = withContext(Dispatchers.IO) {
        val rssResult = rssReader.read(source)
        val rssItems = rssResult.getOrNull().orEmpty()

        // The Verge's RSS is intentionally not treated as the complete catalog.
        // Always scan the public site sections too, then merge and deduplicate.
        val shouldCrawlDirectly = source.id == "the-verge"
        if (shouldCrawlDirectly || rssItems.size < MIN_RSS_ITEMS) {
            val crawlResult = homepageCrawler.crawl(source)
            val crawlItems = crawlResult.getOrNull().orEmpty()
            val merged = (rssItems + crawlItems)
                .distinctBy { it.url }
                .sortedWith(compareByDescending<FeedItem> { it.publishedAt ?: Instant.EPOCH }.thenBy { it.title })
                .take(MAX_ITEMS)

            if (merged.isNotEmpty()) return@withContext Result.success(merged)
            if (rssResult.isSuccess) return@withContext rssResult

            val rssError = rssResult.exceptionOrNull()?.message
            val crawlError = crawlResult.exceptionOrNull()?.message
            return@withContext Result.failure(
                IllegalStateException(
                    listOfNotNull(
                        "Não foi possível obter notícias de ${source.name}.",
                        rssError?.takeIf { it.isNotBlank() },
                        crawlError?.takeIf { it.isNotBlank() }
                    ).joinToString(" ")
                )
            )
        }

        rssResult
    }

    private companion object {
        const val MIN_RSS_ITEMS = 10
        const val MAX_ITEMS = 150
    }
}
