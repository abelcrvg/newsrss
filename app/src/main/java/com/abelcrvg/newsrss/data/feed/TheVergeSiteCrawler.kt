package com.abelcrvg.newsrss.data.feed

import com.abelcrvg.newsrss.core.feed.FeedItem
import com.abelcrvg.newsrss.core.model.FeedSource

class TheVergeSiteCrawler {
    private val crawler = DirectSiteCrawler(DirectSiteCrawler.Config(
        sourceId = "the-verge",
        homeUrl = "https://www.theverge.com/",
        allowedHosts = setOf("www.theverge.com", "theverge.com"),
        selectors = "article a[href], a[href][data-analytics-link], a[href]",
        articlePath = { path ->
            path != "/" && path.length >= 8 && path.split('/').count { it.isNotBlank() } >= 2 &&
                !path.matches(Regex(".*/(authors?|tags?|search|videos?|podcasts?|newsletters?|about|privacy|contact)(/|$).*"))
        }
    ))

    suspend fun crawl(source: FeedSource): Result<List<FeedItem>> = crawler.crawl()
}
