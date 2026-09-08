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

    suspend fun crawl(source: FeedSource): Result<List<FeedItem>> =
        crawler.crawl().map { items ->
            items.filterNot(::looksLikeNonNewsSiteCard)
        }.let { result ->
            if (result.isSuccess && result.getOrNull().orEmpty().isEmpty()) {
                Result.failure(IllegalStateException("Nenhuma notícia editorial válida foi encontrada no UOL"))
            } else result
        }

    /**
     * UOL's homepage can expose partner/site-directory cards whose visible title is
     * merely a site name rather than a news headline (e.g. "Site gripeseresfriado").
     * They must not enter the news feed.
     */
    private fun looksLikeNonNewsSiteCard(item: FeedItem): Boolean {
        val title = item.title.replace(Regex("\\s+"), " ").trim()
        if (title.isBlank()) return true

        // Explicitly reject the site-label format that caused the reported garbage.
        if (Regex("^site\\b", RegexOption.IGNORE_CASE).containsMatchIn(title)) return true

        // Also reject a small set of known site-directory labels without relying on
        // capitalization or accents. This keeps the rule focused on non-headlines.
        val normalized = title.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
        val knownSiteLabels = setOf(
            "gripeseresfriado",
            "nobreza do amor"
        )
        if (normalized in knownSiteLabels) return true

        return false
    }
}
