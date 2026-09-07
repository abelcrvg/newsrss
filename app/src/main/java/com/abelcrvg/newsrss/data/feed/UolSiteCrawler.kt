package com.abelcrvg.newsrss.data.feed

import com.abelcrvg.newsrss.core.feed.FeedItem
import com.abelcrvg.newsrss.core.model.FeedSource

class UolSiteCrawler {
    private val crawler = DirectSiteCrawler(DirectSiteCrawler.Config(
        sourceId = "uol",
        homeUrl = "https://www.uol.com.br/",
        allowedHosts = setOf("www.uol.com.br", "uol.com.br"),
        selectors = "article a[href], a[href], a[class*=tile][href], a[class*=card][href], a[class*=headline][href]",
        articlePath = { path ->
            path != "/" && path.length >= 10 && path.split('/').count { it.isNotBlank() } >= 2 &&
                !path.matches(Regex(".*/(busca|search|tags?|autor|autores|colunas?|colunistas?|videos?|podcasts?|esportes/futebol/times)(/|$).*")) &&
                !path.matches(Regex(".*\\.(jpg|jpeg|png|gif|webp|svg)$"))
        }
    ))

    suspend fun crawl(source: FeedSource): Result<List<FeedItem>> = crawler.crawl()
}
