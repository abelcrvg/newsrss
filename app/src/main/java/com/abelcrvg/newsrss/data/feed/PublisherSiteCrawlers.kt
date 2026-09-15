package com.abelcrvg.newsrss.data.feed

import com.abelcrvg.newsrss.core.feed.FeedItem
import com.abelcrvg.newsrss.core.model.FeedSource
import java.time.Instant

private const val PUBLISHER_MAX_AGE_DAYS = 7L
private fun Result<List<FeedItem>>.recentOnly(): Result<List<FeedItem>> = map { items ->
    val cutoff = Instant.now().minusSeconds(PUBLISHER_MAX_AGE_DAYS * 24L * 60L * 60L)
    items.filter { it.publishedAt == null || it.publishedAt >= cutoff }
}

class CnnBrasilSiteCrawler {
    private val crawler = DirectSiteCrawler(DirectSiteCrawler.Config(sourceId = "cnn-brasil", homeUrl = "https://www.cnnbrasil.com.br/ultimas-noticias/", allowedHosts = setOf("cnnbrasil.com.br"), selectors = "a[href], article a[href], h2 a[href], h3 a[href]", articlePath = { path -> path != "/" && !path.startsWith("/ultimas-noticias") && !path.matches(Regex(".*/(tag|tags?|autor|authors?|busca|search|videos?|video|podcasts?|programas?|especiais?|ao-vivo|tv)(/|$).*")) && path.count { it == '/' } >= 2 }))
    suspend fun crawl(source: FeedSource): Result<List<FeedItem>> = crawler.crawl().recentOnly()
}

class AdrenalineSiteCrawler {
    private val crawler = DirectSiteCrawler(DirectSiteCrawler.Config(sourceId = "adrenaline", homeUrl = "https://www.adrenaline.com.br/noticias/", allowedHosts = setOf("adrenaline.com.br"), selectors = "article a[href], a[href], h2 a[href], h3 a[href], [class*=post] a[href], [class*=card] a[href]", articlePath = { path -> path != "/" && !path.matches(Regex(".*/(noticias|games|hardware|reviews|guias|colunas|videos|tags?|autor|busca)(/|$).*")) && path.count { it == '/' } >= 2 }))
    suspend fun crawl(source: FeedSource): Result<List<FeedItem>> = crawler.crawl().recentOnly()
}

class ReutersSiteCrawler {
    private val crawler = DirectSiteCrawler(DirectSiteCrawler.Config(sourceId = "reuters", homeUrl = "https://www.reuters.com/world/", allowedHosts = setOf("reuters.com"), selectors = "article a[href], a[href], h2 a[href], h3 a[href]", articlePath = { path -> path.startsWith("/world/") || path.startsWith("/business/") || path.startsWith("/technology/") || path.startsWith("/markets/") || path.startsWith("/sports/") || path.startsWith("/lifestyle/") || path.startsWith("/commentary/") }))
    suspend fun crawl(source: FeedSource): Result<List<FeedItem>> = crawler.crawl()
}

class ApNewsSiteCrawler {
    private val crawler = DirectSiteCrawler(DirectSiteCrawler.Config(sourceId = "ap-news", homeUrl = "https://apnews.com/", allowedHosts = setOf("apnews.com"), selectors = "article a[href], a[href], h2 a[href], h3 a[href]", articlePath = { path -> path != "/" && path.count { it == '/' } >= 2 && !path.matches(Regex(".*/(hub|search|video|videos|photography|gallery|live|topic)(/|$).*")) }))
    suspend fun crawl(source: FeedSource): Result<List<FeedItem>> = crawler.crawl()
}
