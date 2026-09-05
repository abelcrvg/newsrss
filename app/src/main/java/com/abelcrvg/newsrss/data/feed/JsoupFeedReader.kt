package com.abelcrvg.newsrss.data.feed

import com.abelcrvg.newsrss.core.feed.FeedItem
import com.abelcrvg.newsrss.core.feed.FeedReader
import com.abelcrvg.newsrss.core.model.FeedSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** RSS/Atom reader with automatic feed discovery from a site's HTML. */
class JsoupFeedReader : FeedReader {
    override suspend fun read(source: FeedSource): Result<List<FeedItem>> = withContext(Dispatchers.IO) {
        runCatching {
            val feedUrl = source.feedUrl ?: discoverFeedUrl(source.siteUrl, source.name)
            val xml = Jsoup.connect(feedUrl)
                .userAgent(USER_AGENT)
                .referrer(REFERRER)
                .header("Accept", "application/rss+xml, application/atom+xml, application/xml, text/xml;q=0.9, */*;q=0.8")
                .header("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.7,en;q=0.5")
                .timeout(TIMEOUT)
                .followRedirects(true)
                .ignoreContentType(true)
                .execute()
                .body()
            parseFeed(source, xml)
        }
    }

    private fun discoverFeedUrl(siteUrl: String, sourceName: String): String {
        val document = Jsoup.connect(siteUrl)
            .userAgent(USER_AGENT)
            .referrer(REFERRER)
            .header("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.7,en;q=0.5")
            .timeout(TIMEOUT)
            .followRedirects(true)
            .get()

        document.select("link[type=application/rss+xml],link[type=application/atom+xml]")
            .mapNotNull { it.absUrl("href").takeIf(String::isNotBlank) }
            .firstOrNull()?.let { return it }
        val base = siteUrl.trimEnd('/')
        listOf("/rss", "/feed", "/rss.xml", "/feed.xml", "/atom.xml").firstOrNull { path ->
            runCatching {
                Jsoup.connect(base + path)
                    .userAgent(USER_AGENT)
                    .timeout(5_000)
                    .ignoreContentType(true)
                    .execute()
                    .contentType()
                    .orEmpty()
                    .contains("xml", true)
            }.getOrDefault(false)
        }?.let { return base + it }
        error("Não foi possível encontrar um feed RSS/Atom para $sourceName")
    }

    private fun parseFeed(source: FeedSource, xml: String): List<FeedItem> {
        val document = Jsoup.parse(xml, "", org.jsoup.parser.Parser.xmlParser())
        val atom = document.selectFirst("feed") != null
        val entries = if (atom) document.select("feed > entry") else document.select("rss > channel > item, channel > item")
        return entries.mapNotNull { entry ->
            val title = entry.selectFirst("title")?.text()?.trim().takeIf { !it.isNullOrBlank() } ?: return@mapNotNull null
            val url = if (atom) entry.select("link[href]").firstOrNull()?.attr("href") else entry.selectFirst("link")?.text()
            val cleanUrl = url?.trim()?.takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: return@mapNotNull null
            FeedItem(
                id = (source.id + cleanUrl).hashCode().toUInt().toString(16),
                sourceId = source.id,
                title = title,
                url = cleanUrl,
                summary = firstText(entry, "description,summary,content")?.let { Jsoup.parse(it).text() },
                publishedAt = parseDate(firstText(entry, "pubDate,published,updated,date")),
                imageUrl = entry.selectFirst("enclosure")?.attr("url")?.takeIf { it.startsWith("http") }
            )
        }.distinctBy { it.url }
    }

    private fun firstText(entry: org.jsoup.nodes.Element, selector: String): String? =
        entry.selectFirst(selector)?.text()?.trim()?.takeIf { it.isNotBlank() }

    private fun parseDate(value: String?): Instant? = value?.let {
        runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }.getOrNull()
            ?: runCatching { Instant.parse(it) }.getOrNull()
            ?: runCatching { ZonedDateTime.parse(it).toInstant() }.getOrNull()
    }

    private companion object {
        const val TIMEOUT = 20_000
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36 NewsRSS/0.1"
        const val REFERRER = "https://www.google.com/"
    }
}
