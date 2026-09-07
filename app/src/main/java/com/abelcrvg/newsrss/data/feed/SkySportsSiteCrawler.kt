package com.abelcrvg.newsrss.data.feed

import com.abelcrvg.newsrss.core.feed.FeedItem
import com.abelcrvg.newsrss.core.model.FeedSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZonedDateTime

/** Dedicated crawler for Sky Sports Football, whose cards do not follow the generic card selectors. */
class SkySportsSiteCrawler {
    suspend fun crawl(source: FeedSource): Result<List<FeedItem>> = withContext(Dispatchers.IO) {
        runCatching {
            val base = source.siteUrl.trimEnd('/')
            val documents = listOf(base, "$base/news")
                .distinct()
                .mapNotNull { url -> runCatching { fetch(url) }.getOrNull() }

            val candidates = LinkedHashMap<String, FeedItem>()
            documents.forEach { document ->
                document.select("a[href]").forEach { link ->
                    val item = candidate(source, link) ?: return@forEach
                    candidates.putIfAbsent(item.url, item)
                }
            }

            if (candidates.isEmpty()) error("Nenhuma notícia do Sky Sports foi identificada")
            candidates.values.sortedWith(compareByDescending<FeedItem> { it.publishedAt ?: Instant.EPOCH }.thenBy { it.title })
        }
    }

    private fun fetch(url: String) = Jsoup.connect(url)
        .userAgent(USER_AGENT)
        .referrer("https://www.google.com/")
        .timeout(TIMEOUT)
        .followRedirects(true)
        .get()

    private fun candidate(source: FeedSource, link: Element): FeedItem? {
        val url = link.absUrl("href").trim()
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val host = uri.host?.removePrefix("www.") ?: return null
        if (host != "skysports.com") return null
        val path = uri.path.orEmpty()
        if (!path.startsWith("/football/news/")) return null
        if (path.count { it == '/' } < 4) return null

        val title = listOf(
            link.selectFirst("h1,h2,h3,h4,h5")?.text().orEmpty(),
            link.text(),
            link.attr("aria-label"),
            link.attr("title")
        ).asSequence().map { it.replace(Regex("\\s+"), " ").trim() }
            .firstOrNull { it.length in 12..220 }
            ?: return null

        val context = link.closest("article") ?: link.closest("li") ?: link.parent() ?: return null
        if (context.parents().any { it.tagName() in setOf("nav", "header", "footer") }) return null
        val lower = (title + " " + context.className() + " " + context.id()).lowercase()
        if (listOf("video", "watch", "score", "fixture", "table", "bet", "podcast").any(lower::contains)) return null

        val image = context.select("img, source").asSequence()
            .mapNotNull { imageSource(it) }
            .firstOrNull()
        val date = context.select("time[datetime], [itemprop=datePublished]")
            .asSequence()
            .mapNotNull { parseDate(it.attr("datetime").ifBlank { it.attr("content") }.ifBlank { it.text() }) }
            .firstOrNull()

        val summary = context.select("p").map { it.text().trim() }
            .firstOrNull { it.length >= 30 && it != title }

        return FeedItem(
            id = (source.id + url).hashCode().toUInt().toString(16),
            sourceId = source.id,
            title = title,
            url = url,
            summary = summary,
            publishedAt = date,
            imageUrl = image
        )
    }

    private fun imageSource(element: Element): String? {
        val raw = listOf("src", "data-src", "data-lazy-src", "data-original", "data-image", "data-image-url")
            .firstNotNullOfOrNull { element.attr(it).takeIf(String::isNotBlank) }
            ?: element.attr("srcset").split(',').asSequence().map { it.trim().substringBefore(' ') }.firstOrNull { it.isNotBlank() }
        if (raw.isNullOrBlank()) return null
        val normalized = if (raw.startsWith("//")) "https:$raw" else runCatching { URI(element.baseUri()).resolve(raw).toString() }.getOrElse { raw }
        return normalized.takeIf { (it.startsWith("http://") || it.startsWith("https://")) && !looksLikeNoise(it) }
    }

    private fun looksLikeNoise(url: String): Boolean = listOf("logo", "avatar", "icon", "sprite", "placeholder", "tracking", "pixel", "1x1").any(url.lowercase()::contains)

    private fun parseDate(value: String): Instant? = runCatching { Instant.parse(value) }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()
        ?: runCatching { ZonedDateTime.parse(value).toInstant() }.getOrNull()

    private companion object {
        const val TIMEOUT = 15_000
        const val USER_AGENT = "Mozilla/5.0 (Android) AppleWebKit/537.36 Chrome/128 Mobile Safari/537.36"
    }
}
