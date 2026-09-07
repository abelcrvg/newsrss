package com.abelcrvg.newsrss.data.feed

import com.abelcrvg.newsrss.core.feed.FeedItem
import com.abelcrvg.newsrss.core.model.FeedSource

class IgnBrasilSiteCrawler {
    private val crawler = DirectSiteCrawler(DirectSiteCrawler.Config(
        sourceId = "ign-brasil",
        homeUrl = "https://br.ign.com/",
        allowedHosts = setOf("br.ign.com"),
        selectors = "article a[href], a[href], a[class*=item][href], a[class*=card][href]",
        articlePath = { path ->
            path != "/" && path.length >= 8 && path.split('/').count { it.isNotBlank() } >= 2 &&
                !path.matches(Regex(".*/(tag|tags?|autor|authors?|busca|search|videos?|video|podcasts?|listas?|lista|especial)(/|$).*"))
        }
    ))

    suspend fun crawl(source: FeedSource): Result<List<FeedItem>> = crawler.crawl()
}
