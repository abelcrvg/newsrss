package com.abelcrvg.newsrss.data.feed

import com.abelcrvg.newsrss.core.feed.FeedItem
import com.abelcrvg.newsrss.core.model.FeedSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.net.URI
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Lightweight RSS/Atom fallback used when a publisher's HTML is difficult to crawl. */
class RssFeedCrawler(private val timeoutMillis: Int = 12_000) {
    suspend fun crawl(source: FeedSource, feedUrls: List<String>): Result<List<FeedItem>> = withContext(Dispatchers.IO) {
        runCatching {
            val items = LinkedHashMap<String, FeedItem>()
            val cutoff = Instant.now().minusSeconds(MAX_AGE_DAYS * 24L * 60L * 60L)
            feedUrls.distinct().forEach { feedUrl ->
                runCatching { fetch(feedUrl) }.getOrNull()?.select("item,entry")?.forEach { entry ->
                    candidate(source, entry)?.let { item ->
                        // Some discontinued/redirected feeds keep serving old archives.
                        // Never let those stale entries contaminate the current feed.
                        if (item.publishedAt == null || item.publishedAt >= cutoff) {
                            items.putIfAbsent(item.url, item)
                        }
                    }
                }
            }
            if (items.isEmpty()) error("Nenhuma notícia recente foi encontrada no RSS de ${source.name}")
            items.values
                .sortedWith(compareByDescending<FeedItem> { it.publishedAt ?: Instant.EPOCH }.thenBy { it.title })
                .take(MAX_ITEMS)
        }
    }

    private fun fetch(url: String) = Jsoup.connect(url)
        .userAgent(USER_AGENT)
        .referrer("https://www.google.com/")
        .header("Accept", "application/rss+xml, application/atom+xml, application/xml, text/xml;q=0.9, */*;q=0.5")
        .header("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.8")
        .timeout(timeoutMillis)
        .followRedirects(true)
        .parser(Parser.xmlParser())
        .get()

    private fun candidate(source: FeedSource, entry: Element): FeedItem? {
        val title = entry.selectFirst("title")?.text()?.replace(Regex("\\s+"), " ")?.trim()
            ?.takeIf { it.length in 8..240 } ?: return null

        val linkElement = entry.selectFirst("link[href]")
        val rawUrl = linkElement?.attr("href")?.takeIf(String::isNotBlank)
            ?: entry.selectFirst("link")?.text()?.trim()
        val url = normalizeUrl(rawUrl) ?: return null
        val host = runCatching { URI(url).host.orEmpty().lowercase().removePrefix("www.") }.getOrDefault("")
        if (host.isBlank()) return null

        val summary = entry.selectFirst("description,summary")?.text()
            ?.let { Jsoup.parse(it).text().replace(Regex("\\s+"), " ").trim() }
            ?.takeIf { it.isNotBlank() && it != title }
        val publishedAt = listOf("pubDate", "published", "updated", "date")
            .asSequence()
            .mapNotNull { name -> entry.children().firstOrNull { it.tagName().equals(name, ignoreCase = true) }?.text() }
            .mapNotNull(::parseDate)
            .firstOrNull()
        val imageUrl = extractImage(entry)

        return FeedItem(
            id = (source.id + url).hashCode().toUInt().toString(16),
            sourceId = source.id,
            title = title,
            url = url,
            summary = summary,
            publishedAt = publishedAt,
            imageUrl = imageUrl
        )
    }

    private fun extractImage(entry: Element): String? {
        val candidates = buildList {
            entry.select("enclosure[url], thumbnail[url], content[url], [url]").forEach { element ->
                element.attr("url").takeIf(String::isNotBlank)?.let(::add)
            }
            entry.select("enclosure[src], thumbnail[src], content[src], [src]").forEach { element ->
                element.attr("src").takeIf(String::isNotBlank)?.let(::add)
            }
        }
        return candidates.asSequence()
            .mapNotNull(::normalizeUrl)
            .firstOrNull { url -> !looksLikeNoise(url) }
    }

    private fun normalizeUrl(value: String?): String? {
        val raw = value?.trim()?.removeSurrounding("<![CDATA[", "]]>" ) ?: return null
        if (raw.isBlank()) return null
        val normalized = if (raw.startsWith("//")) "https:$raw" else raw
        return normalized.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    private fun looksLikeNoise(url: String): Boolean = listOf(
        "logo", "avatar", "icon", "favicon", "sprite", "placeholder", "tracking", "pixel", "1x1"
    ).any(url.lowercase()::contains)

    private fun parseDate(value: String): Instant? = value.trim().takeIf(String::isNotBlank)?.let {
        runCatching { Instant.parse(it) }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull()
            ?: runCatching { ZonedDateTime.parse(it).toInstant() }.getOrNull()
            ?: runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }.getOrNull()
    }

    private companion object {
        const val MAX_ITEMS = 40
        const val MAX_AGE_DAYS = 45L
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/140.0 Mobile Safari/537.36 NewsRSS/0.3"
    }
}
