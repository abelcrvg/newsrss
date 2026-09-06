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

/** Dedicated direct crawler for GE. Pagination follows discovered pagination links and never uses an arbitrary article/page cap. */
class GESiteCrawler {
    suspend fun crawl(source: FeedSource): Result<List<FeedItem>> = withContext(Dispatchers.IO) {
        runCatching {
            val base = source.siteUrl.trimEnd('/')
            val host = URI(base).host?.removePrefix("www.") ?: error("URL inválida")
            val documents = mutableListOf<Document>()
            val visitedPages = mutableSetOf<String>()
            val pendingPages = ArrayDeque<String>()
            pendingPages.add(base)
            pendingPages.add("$base/plantao/")

            while (pendingPages.isNotEmpty()) {
                val url = pendingPages.removeFirst()
                if (!visitedPages.add(url)) continue
                val document = runCatching { fetch(url) }.getOrNull() ?: continue
                documents += document
                discoverPaginationUrls(document, base, host).forEach { next ->
                    if (next !in visitedPages && next !in pendingPages) pendingPages.addLast(next)
                }
            }

            val items = documents.flatMap { extractLinks(it, source, host) }
                .distinctBy { it.url }

            coroutineScope {
                items.map { item -> async { enrich(item) } }.awaitAll()
            }.distinctBy { it.url }
                .sortedWith(
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

    private fun discoverPaginationUrls(document: Document, base: String, host: String): Set<String> =
        document.select("a[href]").asSequence().mapNotNull { link ->
            val url = link.absUrl("href").trim().substringBefore('#')
            if (url.isBlank()) return@mapNotNull null
            val uri = runCatching { URI(url) }.getOrNull() ?: return@mapNotNull null
            val path = uri.path.orEmpty().lowercase()
            val text = link.text().trim().lowercase()
            val paginationPath = path.startsWith("/plantao") && (
                path.contains("/feed/") ||
                    path.matches(Regex(".*/pagina-\\d+.*")) ||
                    text.matches(Regex("(próxima|proxima|next|›|»|\\d+)"))
                )
            url.takeIf { uri.host?.removePrefix("www.") == host && paginationPath && it.startsWith(base) }
        }.toSet()

    private fun discoverArticleUrls(document: Document, host: String): Set<String> =
        document.select("a[href]").asSequence().mapNotNull { link ->
            val url = link.absUrl("href").trim()
            val uri = runCatching { URI(url) }.getOrNull() ?: return@mapNotNull null
            url.takeIf { uri.host?.removePrefix("www.") == host && isNewsArticle(uri.path.orEmpty()) }
        }.toSet()

    private fun extractLinks(document: Document, source: FeedSource, host: String): List<FeedItem> =
        document.select("a[href]").mapNotNull { link ->
            val url = link.absUrl("href").trim()
            val uri = runCatching { URI(url) }.getOrNull() ?: return@mapNotNull null
            if (uri.host?.removePrefix("www.") != host || !isNewsArticle(uri.path.orEmpty())) return@mapNotNull null
            val context = findContentContext(link) ?: link
            val title = extractTitle(link, context) ?: return@mapNotNull null
            val summary = context.select("p, [class*=resumo], [class*=subtitle], [class*=subtitulo]")
                .map { it.text().replace(Regex("\\s+"), " ").trim() }
                .firstOrNull { it.length >= 20 && it != title }
            FeedItem((source.id + url).hashCode().toUInt().toString(16), source.id, title, url, summary, extractDate(link, context, document), extractImage(link, context, document))
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
        link.closest("article") ?: link.closest("[class*=feed-post], [class*=feed-item], [class*=card], [class*=story], [class*=headline], [class*=noticia], [class*=materia], [class*=post], [class*=content]") ?: link.parent()

    private fun extractTitle(link: Element, context: Element): String? = listOf(
        link.selectFirst("h1,h2,h3,h4,h5,h6")?.text().orEmpty(), context.selectFirst("h1,h2,h3,h4,h5,h6")?.text().orEmpty(),
        link.text(), link.attr("aria-label"), link.attr("title"), link.selectFirst("img")?.attr("alt").orEmpty()
    ).asSequence().map { it.replace(Regex("\\s+"), " ").trim() }.firstOrNull { it.length in MIN_TITLE_LENGTH..MAX_TITLE_LENGTH }

    private fun isNewsArticle(path: String): Boolean {
        val normalized = path.lowercase().substringBefore('?').trimEnd('/')
        return normalized.isNotBlank() && !normalized.startsWith("/plantao") && normalized.contains("/noticia/")
    }

    private fun extractImage(link: Element, context: Element, document: Document): String? {
        val nodes = (link.select("img, picture img, picture source, source") + context.select("img, picture img, picture source, source")).distinct()
        return nodes.asSequence().mapNotNull { imageSource(it) }.map { normalizeImageUrl(it, document.baseUri()) }.firstOrNull { isUsableImage(it) } ?: extractArticleImage(document)
    }

    private fun imageSource(image: Element): String? {
        listOf("src", "data-src", "data-lazy-src", "data-original", "data-image", "data-image-url", "data-url", "data-thumb", "data-original-src", "data-lazy", "data-fallback-src").firstNotNullOfOrNull { image.attr(it).takeIf(String::isNotBlank) }?.let { return it }
        val srcset = image.attr("srcset").ifBlank { image.attr("data-srcset") }
        return srcset.split(',').asSequence().map { it.trim().split(Regex("\\s+"), limit = 2).firstOrNull().orEmpty() }.firstOrNull { it.isNotBlank() }
    }

    private fun extractArticleImage(document: Document): String? = document.select("meta[property=og:image][content], meta[property=og:image:url][content], meta[name=twitter:image][content], meta[name=twitter:image:src][content]")
        .mapNotNull { it.attr("content").takeIf(String::isNotBlank) }.map { normalizeImageUrl(it, document.baseUri()) }.firstOrNull { isUsableImage(it) }

    private fun extractArticleSummary(document: Document): String? = document.select("meta[name=description][content], meta[property=og:description][content], meta[name=twitter:description][content]")
        .map { it.attr("content").replace(Regex("\\s+"), " ").trim() }.firstOrNull { it.length >= 30 }

    private fun isUsableImage(url: String): Boolean = (url.startsWith("http://") || url.startsWith("https://")) && listOf("logo", "brand", "avatar", "icon", "favicon", "placeholder", "sprite", "profile", "author", "tracking", "pixel", "1x1", "transparent").none(url.lowercase()::contains)

    private fun extractDate(link: Element, context: Element, document: Document): Instant? {
        val values = (link.select("time[datetime], time[content], [itemprop=datePublished]") + context.select("time[datetime], time[content], [itemprop=datePublished]") + document.select("meta[property=article:published_time], meta[property=datePublished], meta[name=date]")).flatMap { listOf(it.attr("datetime"), it.attr("content"), it.attr("datePublished")) }
        return values.asSequence().mapNotNull(::parseDate).firstOrNull()
    }

    private fun extractArticlePublishedDate(document: Document): Instant? {
        val metadata = document.select("meta[property=article:published_time][content], meta[property=datePublished][content], meta[name=date][content], meta[itemprop=datePublished][content], time[itemprop=datePublished][datetime], time[datetime]").mapNotNull { it.attr("content").ifBlank { it.attr("datetime") }.takeIf(String::isNotBlank) }
        metadata.asSequence().mapNotNull(::parseDate).firstOrNull()?.let { return it }
        return document.select("script[type=application/ld+json]").asSequence().flatMap { DATE_PUBLISHED.findAll(it.data()).asSequence() }.mapNotNull { parseDate(it.groupValues[1]) }.firstOrNull()
    }

    private fun parseDate(value: String?): Instant? = value?.trim()?.takeIf(String::isNotBlank)?.let {
        runCatching { Instant.parse(it) }.getOrNull() ?: runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull()
            ?: runCatching { ZonedDateTime.parse(it).toInstant() }.getOrNull() ?: runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(it, DateTimeFormatter.ISO_LOCAL_DATE_TIME).atZone(ZoneId.systemDefault()).toInstant() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(it, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).atZone(ZoneId.systemDefault()).toInstant() }.getOrNull()
    }

    private fun normalizeImageUrl(value: String, baseUri: String): String {
        val trimmed = value.trim().removeSurrounding("\"")
        if (trimmed.startsWith("//")) return "https:$trimmed"
        return runCatching { URI(baseUri).resolve(trimmed).toString() }.getOrElse { trimmed }
    }

    private companion object {
        const val TIMEOUT = 15_000
        const val MIN_TITLE_LENGTH = 8
        const val MAX_TITLE_LENGTH = 220
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36 NewsRSS/0.3"
        val DATE_PUBLISHED = Regex("""[\"']datePublished[\"']\s*:\s*[\"']([^\"']+)[\"']""", RegexOption.IGNORE_CASE)
    }
}
