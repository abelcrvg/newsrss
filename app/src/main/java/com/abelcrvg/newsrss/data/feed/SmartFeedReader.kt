package com.abelcrvg.newsrss.data.feed

import com.abelcrvg.newsrss.core.feed.FeedItem
import com.abelcrvg.newsrss.core.feed.FeedReader
import com.abelcrvg.newsrss.core.model.FeedSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

/** Uses RSS/Atom first and supplements short feeds with homepage crawling. */
class SmartFeedReader(
    private val rssReader: FeedReader = JsoupFeedReader(),
    private val homepageCrawler: HomepageNewsCrawler = HomepageNewsCrawler()
) : FeedReader {
    override suspend fun read(source: FeedSource): Result<List<FeedItem>> = withContext(Dispatchers.IO) {
        val rssResult = rssReader.read(source)
        val rssItems = rssResult.getOrNull().orEmpty()

        // Some publishers expose only a handful of items through RSS even though
        // their homepage contains many more. Supplement short RSS results instead
        // of treating a non-empty RSS response as complete.
        if (rssItems.size >= MIN_RSS_ITEMS) return@withContext rssResult

        val crawlResult = homepageCrawler.crawl(source)
        val crawlItems = crawlResult.getOrNull().orEmpty()
        val merged = (rssItems + crawlItems)
            .distinctBy { it.url }
            .sortedWith(compareByDescending<FeedItem> { it.publishedAt ?: Instant.EPOCH }.thenBy { it.title })
            .take(MAX_ITEMS)

        if (merged.isNotEmpty()) return@withContext Result.success(merged)

        val rssError = rssResult.exceptionOrNull()?.message
        val crawlError = crawlResult.exceptionOrNull()?.message
        Result.failure(
            IllegalStateException(
                listOfNotNull(
                    "Não foi possível obter notícias de ${source.name}.",
                    rssError?.takeIf { it.isNotBlank() },
                    crawlError?.takeIf { it.isNotBlank() }
                ).joinToString(" ")
            )
        )
    }

    private companion object {
        const val MIN_RSS_ITEMS = 10
        const val MAX_ITEMS = 100
    }
}
