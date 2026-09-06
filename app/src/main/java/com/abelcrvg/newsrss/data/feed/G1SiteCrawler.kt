package com.abelcrvg.newsrss.data.feed

import com.abelcrvg.newsrss.core.feed.FeedItem
import com.abelcrvg.newsrss.core.model.FeedSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Dedicated direct G1 crawler: homepage + Plantão, with structure-aware article discovery and metadata enrichment. */
class G1SiteCrawler {
    suspend fun crawl(source: FeedSource): Result<List<FeedItem>> = withContext(Dispatchers.IO) {
        runCatching {
            val base = source.siteUrl.trimEnd('/')
            val baseHost = URI(base).host?.removePrefix("www.") ?: error("URL inválida")
            val homepage = fetch(base)
            val plantaoUrls = buildList {
                add("$base/plantao/")
                for (page in 2..MAX_PLANTAO_PAGES) add("$base/plantao/index/feed/pagina-$page.ghtml")
            }

            // G1 exposes a stable feed-post structure on the home. Use it first, then
            // fall back to all internal /noticia/ anchors so new layouts do not silently disappear.
            val homepageItems = extractHomepage(homepage, source, baseHost)
            val plantaoItems = plantaoUrls.flatMap { url ->
                runCatching { extractLinks(fetch(url), source, baseHost) }.getOrDefault(emptyList())
            }

            val discovered = (homepageItems + plantaoItems)
                .distinctBy { it.url }
                .take(MAX_DISCOVERED)

            val enriched = coroutineScope {
                discovered.map { item -> async { enrich(item) } }.awaitAll()
            }
            enriched.distinctBy { it.url }.sortedWith(
                compareByDescending<FeedItem> { it.publishedAt ?: Instant.EPOCH }
                    .thenBy { it.title.lowercase() }
            )
        }
    }

    private fun fetch(url: String): Document = Jsoup.connect(url)
        .userAgent(USER_AGENT)
        .referrer("https://www.google.com/")
        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,*/*;q=0.8")
        .header("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.7,en;q=0.5")
        .timeout(TIMEOUT)
        .followRedirects(true)
        .get()

    private fun extractHomepage(document: Document, source: FeedSource, baseHost: String): List<FeedItem> {
        val structured = document.select(
            "a.feed-post-link[href], a[class*=feed-post-link][href], article a[href*=/noticia/], " +
                "a[href*=/noticia/][class*=feed-post][href]"
        ).mapNotNull { link -> extractCardItem(link, source, baseHost) }

        val fallback = extractLinks(document, source, baseHost)
        return (structured + fallback).distinctBy { it.url }
    }

    private fun extractCardItem(link: Element, source: FeedSource, baseHost: String): FeedItem? {
        val url = link.absUrl("href").trim()
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.host?.removePrefix("www.") != baseHost || !isNewsArticle(uri.path.orEmpty())) return null

        val card = link.closest("article, .feed-post, [class*=feed-post], [class*=feed-item], [class*=story], [class*=card]") ?: link.parent()
        val title = link.text().replace(Regex("\\s+"), " ").trim()
            .takeIf { it.length in MIN_TITLE_LENGTH..MAX_TITLE_LENGTH }
            ?: extractTitle(link, card ?: link)
            ?: return null
        val summary = card?.select(".feed-post-body-resumo, [class*=resumo], p")
            ?.map { it.text().replace(Regex("\\s+"), " ").trim() }
            ?.firstOrNull { it.length >= 20 && it != title }
        val image = if (card != null) extractImage(link, card, card.ownerDocument()) else null
        val date = extractDate(link, card ?: link, card?.ownerDocument() ?: link.ownerDocument(), url)

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

    private fun extractLinks(document: Document, source: FeedSource, baseHost: String): List<FeedItem> =
        document.select("a[href]").mapNotNull { link ->
            val url = link.absUrl("href").trim()
            val uri = runCatching { URI(url) }.getOrNull() ?: return@mapNotNull null
            if (uri.host?.removePrefix("www.") != baseHost || !isNewsArticle(uri.path.orEmpty())) return@mapNotNull null
            val context = findContentContext(link) ?: link
            val title = extractTitle(link, context) ?: return@mapNotNull null
            val image = extractImage(link, context, document)
            val date = extractDate(link, context, document, url)
            val summary = context.select("p").map { it.text().replace(Regex("\\s+"), " ").trim() }
                .firstOrNull { it.length >= 20 && it != title }
            FeedItem(
                id = (source.id + url).hashCode().toUInt().toString(16),
                sourceId = source.id,
                title = title,
                url = url,
                summary = summary,
                publishedAt = date,
                imageUrl = image
            )
        }.distinctBy { it.url }

    private suspend fun enrich(item: FeedItem): FeedItem = withContext(Dispatchers.IO) {
        runCatching {
            val document = fetch(item.url)
            item.copy(
                publishedAt = extractArticlePublishedDate(document) ?: item.publishedAt,
                imageUrl = extractArticleImage(document) ?: item.imageUrl,
                summary = extractArticleSummary(document) ?: item.summary
            )
        }.getOrElse { item }
    }

    private fun findContentContext(link: Element): Element? =
        link.closest("article")
            ?: link.closest("[class*=feed-post], [class*=feed-item], [class*=card], [class*=story], [class*=headline], [class*=noticia], [class*=materia], [class*=post], [class*=content]")
            ?: link.parent()?.takeIf { it.tagName() in setOf("a", "div", "li") }

    private fun extractTitle(link: Element, context: Element): String? = listOf(
        link.selectFirst("h1, h2, h3, h4, h5, h6")?.text().orEmpty(),
        context.selectFirst("h1, h2, h3, h4, h5, h6")?.text().orEmpty(),
        link.text(), link.attr("aria-label"), link.attr("title"), link.selectFirst("img")?.attr("alt").orEmpty()
    ).asSequence().map { it.replace(Regex("\\s+"), " ").trim() }
        .firstOrNull { it.length in MIN_TITLE_LENGTH..MAX_TITLE_LENGTH }

    private fun isNewsArticle(path: String): Boolean {
        val normalized = path.lowercase().substringBefore('?').trimEnd('/')
        return normalized.isNotBlank() && !normalized.startsWith("/plantao") && normalized.contains("/noticia/")
    }

    private fun extractImage(link: Element, context: Element, document: Document): String? {
        val nodes = buildList {
            addAll(link.select("img, picture img, picture source, source"))
            addAll(context.select("img, picture img, picture source, source").take(30))
        }.distinct()
        nodes.asSequence().mapNotNull { imageSource(it) }
            .map { normalizeImageUrl(it, document.baseUri()) }
            .firstOrNull { isUsableImage(it) }
            ?.let { return it }
        return extractArticleImage(document)
    }

    private fun imageSource(image: Element): String? {
        val attrs = listOf("src", "data-src", "data-lazy-src", "data-original", "data-image", "data-image-url", "data-url", "data-thumb", "data-original-src", "data-lazy", "data-fallback-src")
        attrs.firstNotNullOfOrNull { image.attr(it).takeIf(String::isNotBlank) }?.let { return it }
        val srcset = image.attr("srcset").ifBlank { image.attr("data-srcset") }
        return srcset.split(',').asSequence()
            .map { it.trim().split(Regex("\\s+"), limit = 2).firstOrNull().orEmpty() }
            .firstOrNull { it.isNotBlank() }
    }

    private fun extractArticleImage(document: Document): String? = document.select(
        "meta[property=og:image][content], meta[property=og:image:url][content], meta[name=twitter:image][content], meta[name=twitter:image:src][content], link[rel=image_src][href]"
    ).mapNotNull { it.attr("content").ifBlank { it.attr("href") }.takeIf(String::isNotBlank) }
        .map { normalizeImageUrl(it, document.baseUri()) }.firstOrNull { isUsableImage(it) }

    private fun extractArticleSummary(document: Document): String? = document.select(
        "meta[name=description][content], meta[property=og:description][content], meta[name=twitter:description][content]"
    ).map { it.attr("content").trim() }.firstOrNull { it.length >= 30 }

    private fun isUsableImage(url: String): Boolean {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false
        val value = url.lowercase()
        return listOf("logo", "brand", "avatar", "icon", "favicon", "placeholder", "sprite", "profile", "author", "tracking", "pixel", "1x1", "transparent").none(value::contains)
    }

    private fun extractDate(link: Element, context: Element, document: Document, url: String): Instant? {
        val values = buildList {
            addAll(link.select("time[datetime], time[content], [itemprop=datePublished], [itemprop=dateModified]").flatMap { listOf(it.attr("datetime"), it.attr("content"), it.attr("datePublished")) })
            addAll(context.select("time[datetime], time[content], [itemprop=datePublished], [itemprop=dateModified], meta[property=article:published_time]").flatMap { listOf(it.attr("datetime"), it.attr("content"), it.attr("datePublished")) })
            addAll(document.select("meta[property=article:published_time], meta[property=datePublished], meta[name=date]").map { it.attr("content") })
        }
        values.asSequence().mapNotNull { parseDate(it) }.firstOrNull()?.let { return it }
        DATE_IN_URL.find(url)?.let { match ->
            val time = match.groupValues.getOrNull(2)?.replace('-', ':') ?: "00:00"
            parseDate("${match.groupValues[1].replace('/', '-')}T$time:00")?.let { return it }
        }
        return document.select("script[type=application/ld+json]").asSequence()
            .flatMap { DATE_PUBLISHED.findAll(it.data()).asSequence() }
            .mapNotNull { parseDate(it.groupValues[1]) }.firstOrNull()
    }

    private fun extractArticlePublishedDate(document: Document): Instant? {
        val metadata = document.select("meta[property=article:published_time][content], meta[property=datePublished][content], meta[name=date][content], meta[itemprop=datePublished][content], time[itemprop=datePublished][datetime], time[datetime]")
            .mapNotNull { node -> node.attr("content").ifBlank { node.attr("datetime") }.takeIf(String::isNotBlank) }
        metadata.asSequence().mapNotNull { parseDate(it) }.firstOrNull()?.let { return it }
        return document.select("script[type=application/ld+json]").asSequence()
            .flatMap { DATE_PUBLISHED.findAll(it.data()).asSequence() }
            .mapNotNull { parseDate(it.groupValues[1]) }.firstOrNull()
    }

    private fun parseDate(value: String?): Instant? = value?.trim()?.takeIf(String::isNotBlank)?.let {
        runCatching { Instant.parse(it) }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull()
            ?: runCatching { ZonedDateTime.parse(it).toInstant() }.getOrNull()
            ?: runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(it, DateTimeFormatter.ISO_LOCAL_DATE_TIME).atZone(ZoneId.systemDefault()).toInstant() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(it, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).atZone(ZoneId.systemDefault()).toInstant() }.getOrNull()
    }

    private fun normalizeImageUrl(value: String, baseUri: String): String {
        val trimmed = value.trim().removeSurrounding("\"")
        if (trimmed.isBlank()) return ""
        if (trimmed.startsWith("//")) return "https:$trimmed"
        return runCatching { URI(baseUri).resolve(trimmed).toString() }.getOrElse { trimmed }
    }

    private companion object {
        const val TIMEOUT = 15_000
        const val MAX_PLANTAO_PAGES = 10
        const val MAX_DISCOVERED = 500
        const val MIN_TITLE_LENGTH = 8
        const val MAX_TITLE_LENGTH = 220
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36 NewsRSS/0.3"
        val DATE_IN_URL = Regex("(20\\d{2}[-/]\\d{2}[-/]\\d{2})(?:[T/-](\\d{2}[-:]\\d{2}))?")
        val DATE_PUBLISHED = Regex("\\\"datePublished\\\"\\s*:\s*\\\"([^\\\"]+)\\\"", RegexOption.IGNORE_CASE)
    }
}
