package com.abelcrvg.newsrss.data.feed

import com.abelcrvg.newsrss.core.feed.FeedItem
import com.abelcrvg.newsrss.core.feed.FeedReader
import com.abelcrvg.newsrss.core.model.FeedSource

/** Uses RSS/Atom when available and crawls the homepage when it is not. */
class SmartFeedReader(
    private val rssReader: FeedReader = JsoupFeedReader(),
    private val homepageCrawler: HomepageNewsCrawler = HomepageNewsCrawler()
) : FeedReader {
    override suspend fun read(source: FeedSource): Result<List<FeedItem>> {
        val rssResult = rssReader.read(source)
        if (rssResult.isSuccess && !rssResult.getOrNull().isNullOrEmpty()) {
            return rssResult
        }

        val crawlResult = homepageCrawler.crawl(source)
        if (crawlResult.isSuccess && !crawlResult.getOrNull().isNullOrEmpty()) {
            return crawlResult
        }

        return Result.failure(
            rssResult.exceptionOrNull()
                ?: crawlResult.exceptionOrNull()
                ?: IllegalStateException("Não foi possível encontrar notícias em ${source.name}")
        )
    }
}
