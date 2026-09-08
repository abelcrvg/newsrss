package com.abelcrvg.newsrss.data.feed

import com.abelcrvg.newsrss.core.feed.FeedItem
import com.abelcrvg.newsrss.core.model.FeedSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZonedDateTime

/** Dedicated crawler for Sky Sports Football. */
class SkySportsSiteCrawler {
    suspend fun crawl(source: FeedSource): Result<List<FeedItem>> = withContext(Dispatchers.IO) {
        runCatching {
            val documents = coroutineScope {
                listOf(
                    "https://www.skysports.com/football/news",
                    "https://www.skysports.com/football",
                    "https://www.skysports.com/football/transfer-news"
                ).distinct().map { url ->
                    async(Dispatchers.IO) { runCatching { fetch(url) }.getOrNull() }
                }.awaitAll().filterNotNull()
            }

            val candidates = LinkedHashMap<String, FeedItem>()
            documents.forEach { document ->
                document.select("a[href]").forEach { link ->
                    candidate(source, link)?.let { item ->
                        val previous = candidates[item.url]
                        if (previous == null || (previous.imageUrl == null && item.imageUrl != null) ||
                            (previous.publishedAt == null && item.publishedAt != null)) {
                            candidates[item.url] = item
                        }
                    }
                }
            }

            if (candidates.isEmpty()) error("Nenhuma notícia do Sky Sports foi identificada")
            candidates.values
                .sortedWith(compareByDescending<FeedItem> { it.publishedAt ?: Instant.EPOCH }.thenBy { it.title })
                .take(MAX_ITEMS)
        }
    }

    private fun fetch(url: String) = Jsoup.connect(url)
        .userAgent(USER_AGENT)
        .referrer("https://www.google.com/")
        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        .header("Accept-Language", "en-GB,en;q=0.9")
        .timeout(TIMEOUT)
        .followRedirects(true)
        .get()

    private fun candidate(source: FeedSource, link: Element): FeedItem? {
        val url = link.absUrl("href").trim()
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val host = uri.host?.lowercase()?.removePrefix("www.") ?: return null
        if (host != "skysports.com") return null

        val path = uri.path.orEmpty().lowercase()
        if (!path.startsWith("/football/")) return null

        // Sky Sports frequently exposes live-blog URLs alongside normal articles.
        // Those pages can legitimately return "Sorry, this blog is currently unavailable"
        // after the live event has ended, so they must not enter the normal article feed.
        if (path.startsWith("/football/live-blog/") || path.contains("/live-blog/") || path.contains("/live/")) return null

        if (path == "/football" || path == "/football/news" ||
            path.contains("/results") || path.contains("/fixtures") || path.contains("/tables") ||
            path.contains("/scores") || path.contains("/watch") || path.contains("/video/") ||
            path.contains("/podcast")) return null
        if (path.count { it == '/' } < 3) return null

        val title = sequenceOf(
            link.selectFirst("h1,h2,h3,h4,h5")?.text(),
            link.text(),
            link.attr("aria-label"),
            link.attr("title")
        ).mapNotNull {
            it?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { value -> value.length in 12..220 }
        }.firstOrNull() ?: return null

        val context = link.closest("article") ?: link.closest("li") ?: link.closest("section") ?: link.parent() ?: return null
        if (context.parents().any { it.tagName() in setOf("nav", "header", "footer") }) return null

        val lower = (title + " " + path + " " + context.className() + " " + context.id()).lowercase()
        if (listOf("video", "watch", "score", "fixture", "table", "podcast", "sky bet").any(lower::contains)) return null

        val image = context.select("img,source").asSequence().mapNotNull(::imageSource).firstOrNull()
        val date = context.select("time[datetime], [itemprop=datePublished]").asSequence()
            .mapNotNull { parseDate(it.attr("datetime").ifBlank { it.attr("content") }.ifBlank { it.text() }) }
            .firstOrNull()
        val summary = context.select("p").map { it.text().trim() }.firstOrNull { it.length >= 30 && it != title }

        return FeedItem(
            (source.id + url).hashCode().toUInt().toString(16),
            source.id,
            title,
            url,
            summary,
            date,
            image
        )
    }

    private fun imageSource(element: Element): String? {
        val raw = listOf("src", "data-src", "data-lazy-src", "data-original", "data-image", "data-image-url")
            .firstNotNullOfOrNull { element.attr(it).takeIf(String::isNotBlank) }
            ?: element.attr("srcset").split(',').asSequence()
                .map { it.trim().substringBefore(' ') }
                .firstOrNull { it.isNotBlank() }

        if (raw.isNullOrBlank()) return null
        val normalized = if (raw.startsWith("//")) "https:$raw"
        else runCatching { URI(element.baseUri()).resolve(raw).toString() }.getOrElse { raw }
        return normalized.takeIf {
            (it.startsWith("http://") || it.startsWith("https://")) && !looksLikeNoise(it)
        }
    }

    private fun looksLikeNoise(url: String): Boolean = listOf(
        "logo", "avatar", "icon", "sprite", "placeholder", "tracking", "pixel", "1x1"
    ).any(url.lowercase()::contains)

    private fun parseDate(value: String): Instant? =
        runCatching { Instant.parse(value) }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()
            ?: runCatching { ZonedDateTime.parse(value).toInstant() }.getOrNull()

    private companion object {
        const val TIMEOUT = 12_000
        const val MAX_ITEMS = 60
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/140.0.0.0 Mobile Safari/537.36 NewsRSS/0.3"
    }
}
