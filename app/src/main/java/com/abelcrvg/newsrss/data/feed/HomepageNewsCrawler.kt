package com.abelcrvg.newsrss.data.feed

import com.abelcrvg.newsrss.core.feed.FeedItem
import com.abelcrvg.newsrss.core.model.FeedSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Finds likely news links directly from a site's homepage when RSS/Atom is unavailable. */
class HomepageNewsCrawler {
    suspend fun crawl(source: FeedSource): Result<List<FeedItem>> = withContext(Dispatchers.IO) {
        runCatching {
            val document = Jsoup.connect(source.siteUrl)
                .userAgent(USER_AGENT)
                .timeout(TIMEOUT)
                .followRedirects(true)
                .get()

            val baseHost = URI(source.siteUrl).host?.removePrefix("www.") ?: error("URL inválida")
            val candidates = document.select("main a[href], article a[href], a[href]")
                .mapNotNull { link -> candidate(source, baseHost, link) }
                .groupBy { it.item.url }
                .values
                .map { matches -> matches.maxBy { it.score }.item }
                .distinctBy { it.url }
                .sortedWith(compareByDescending<FeedItem> { it.publishedAt ?: Instant.EPOCH }.thenBy { it.title })
                .take(MAX_ITEMS)

            candidates
        }
    }

    private fun candidate(source: FeedSource, baseHost: String, link: Element): Candidate? {
        val url = link.absUrl("href").trim()
        val title = link.text().replace(Regex("\\s+"), " ").trim()
        if (!isValidUrl(url, baseHost) || title.length !in MIN_TITLE_LENGTH..MAX_TITLE_LENGTH) return null

        val context = link.closest("article") ?: link.closest("main") ?: link.parent()
        val contextText = context.text().lowercase()
        val urlLower = url.lowercase()
        var score = 0

        if (link.closest("article") != null) score += 8
        if (link.closest("main") != null) score += 3
        if (urlLower.matches(Regex(".*(/noticia[s]?/|/news/|/story/|/materia[s]?/|/post/|/202[0-9]/).*"))) score += 7
        if (urlLower.matches(Regex(".*\\d{4}/\\d{2}/\\d{2}.*"))) score += 5
        if (title.length >= 45) score += 2
        if (link.select("img").isNotEmpty() || link.closest("article")?.select("img")?.isNotEmpty() == true) score += 4
        if (context.selectFirst("time[datetime]") != null) score += 5
        if (contextText.contains("agora") || contextText.contains("min atrás") || contextText.contains("hora atrás")) score += 2

        if (isNavigationLike(link, contextText, urlLower)) score -= 12
        if (urlLower.contains("/tag/") || urlLower.contains("/tags/") || urlLower.contains("/categoria/") || urlLower.contains("/category/")) score -= 10
        if (urlLower.contains("/busca") || urlLower.contains("/search") || urlLower.contains("/login") || urlLower.contains("/entrar")) score -= 20
        if (score < MIN_SCORE) return null

        val imageUrl = link.selectFirst("img")?.let { imageSource(it) }
            ?: link.closest("article")?.selectFirst("img")?.let { imageSource(it) }
        val publishedAt = context.selectFirst("time[datetime]")?.attr("datetime")?.let(::parseDate)
            ?: context.selectFirst("time")?.text()?.let(::parseDate)
        val summary = context.select("p").map { it.text().trim() }.firstOrNull { it.length >= 30 }

        return Candidate(
            item = FeedItem(
                id = (source.id + url).hashCode().toUInt().toString(16),
                sourceId = source.id,
                title = title,
                url = url,
                summary = summary,
                publishedAt = publishedAt,
                imageUrl = imageUrl
            ),
            score = score
        )
    }

    private fun isValidUrl(url: String, baseHost: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host?.removePrefix("www.") ?: return false
        if (uri.scheme !in listOf("http", "https") || host != baseHost) return false
        if (uri.fragment != null || uri.path.isNullOrBlank() || uri.path == "/") return false
        return true
    }

    private fun isNavigationLike(link: Element, contextText: String, url: String): Boolean {
        val parentTags = link.parents().map { it.tagName() }.toSet()
        if ("nav" in parentTags || "header" in parentTags || "footer" in parentTags || "aside" in parentTags) return true
        return listOf("menu", "login", "entrar", "assine", "assinatura", "newsletter", "facebook", "instagram", "youtube", "twitter", "whatsapp").any {
            contextText.contains(it) || url.contains(it)
        }
    }

    private fun imageSource(image: Element): String? {
        val direct = listOf("src", "data-src", "data-lazy-src")
            .firstNotNullOfOrNull { attr -> image.attr(attr).takeIf { it.startsWith("http") } }
        if (direct != null) return direct
        return image.attr("srcset").split(",").firstOrNull()?.trim()?.substringBefore(" ")?.takeIf { it.startsWith("http") }
    }

    private fun parseDate(value: String?): Instant? = value?.trim()?.takeIf { it.isNotBlank() }?.let {
        runCatching { Instant.parse(it) }.getOrNull()
            ?: runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }.getOrNull()
            ?: runCatching { ZonedDateTime.parse(it).toInstant() }.getOrNull()
    }

    private data class Candidate(val item: FeedItem, val score: Int)

    private companion object {
        const val TIMEOUT = 15_000
        const val MAX_ITEMS = 50
        const val MIN_SCORE = 5
        const val MIN_TITLE_LENGTH = 25
        const val MAX_TITLE_LENGTH = 180
        const val USER_AGENT = "NewsRSS/0.1 (Android; open-source reader)"
    }
}
