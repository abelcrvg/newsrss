package com.abelcrvg.newsrss.data.feed

import com.abelcrvg.newsrss.core.feed.FeedItem
import com.abelcrvg.newsrss.core.model.FeedSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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

class GESiteCrawler {
    suspend fun crawl(source: FeedSource): Result<List<FeedItem>> = withContext(Dispatchers.IO) {
        runCatching {
            val base = source.siteUrl.trimEnd('/')
            val host = URI(base).host?.removePrefix("www.") ?: error("URL inválida")
            val candidates = LinkedHashMap<String, FeedItem>()
            crawlSection(base, base, host, source, candidates)
            crawlSection("$base/plantao/", base, host, source, candidates)
            val semaphore = Semaphore(ENRICH_CONCURRENCY)
            val enriched = ArrayList<FeedItem>(candidates.size)
            candidates.values.toList().chunked(ENRICH_BATCH_SIZE).forEach { batch ->
                enriched += coroutineScope { batch.map { item -> async(Dispatchers.IO) { semaphore.withPermit { enrich(item) } } }.awaitAll() }
            }
            enriched.distinctBy { it.url }.sortedWith(compareByDescending<FeedItem> { it.publishedAt ?: Instant.EPOCH }.thenBy { it.title.lowercase() })
        }
    }

    private fun crawlSection(startUrl: String, base: String, host: String, source: FeedSource, candidates: MutableMap<String, FeedItem>) {
        val visited = HashSet<String>()
        var current: String? = startUrl
        while (current != null && visited.add(current)) {
            val document = runCatching { fetch(current!!) }.getOrNull() ?: break
            val before = candidates.size
            extractLinks(document, source, host).forEach { candidates.putIfAbsent(it.url, it) }
            val next = discoverNextPage(document, base, host, current, visited)
            if (candidates.size == before && next != null) break
            current = next
        }
    }

    private fun fetch(url: String): Document = Jsoup.connect(url).userAgent(USER_AGENT).referrer("https://www.google.com/")
        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,*/*;q=0.8")
        .header("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.7,en;q=0.5").timeout(TIMEOUT).followRedirects(true).get()

    private fun discoverNextPage(document: Document, base: String, host: String, currentUrl: String, visited: Set<String>): String? {
        val links = document.select("a[href]").mapNotNull { link ->
            val url = link.absUrl("href").trim().substringBefore('#')
            if (url.isBlank()) return@mapNotNull null
            val uri = runCatching { URI(url) }.getOrNull() ?: return@mapNotNull null
            val path = uri.path.orEmpty().lowercase()
            val text = link.text().trim().lowercase()
            val validPath = path.startsWith("/plantao") && (path.contains("/feed/") || path.matches(Regex(".*/pagina-\\d+.*")))
            if (uri.host?.removePrefix("www.") != host || !url.startsWith(base) || !validPath) return@mapNotNull null
            Triple(url, text, paginationNumber(path))
        }
        val currentNumber = paginationNumber(runCatching { URI(currentUrl).path.orEmpty() }.getOrDefault("")) ?: 1
        links.firstOrNull { link ->
            val n = link.third
            link.first !in visited && (link.second.contains("próxima") || link.second.contains("proxima") || link.second == "next" || link.second == "›" || link.second == "»") && (n == null || n > currentNumber)
        }?.first?.let { return it }
        return links.mapNotNull { link ->
            val n = link.third
            if (link.first in visited || (n != null && n <= currentNumber)) null else link.first to (n ?: Int.MAX_VALUE)
        }.minByOrNull { it.second }?.first
    }

    private fun paginationNumber(path: String): Int? = Regex("(?:pagina-|page/)(\\d+)", RegexOption.IGNORE_CASE).find(path)?.groupValues?.getOrNull(1)?.toIntOrNull()

    private fun extractLinks(document: Document, source: FeedSource, host: String): List<FeedItem> = document.select("a[href]").mapNotNull { link ->
        val url = link.absUrl("href").trim()
        val uri = runCatching { URI(url) }.getOrNull() ?: return@mapNotNull null
        if (uri.host?.removePrefix("www.") != host || !isNewsArticle(uri.path.orEmpty())) return@mapNotNull null
        val context = findContentContext(link) ?: link
        val title = extractTitle(link, context) ?: return@mapNotNull null
        val summary = context.select("p,[class*=resumo],[class*=subtitle],[class*=subtitulo]").map { it.text().replace(Regex("\\s+"), " ").trim() }.firstOrNull { it.length >= 20 && it != title }
        FeedItem((source.id + url).hashCode().toUInt().toString(16), source.id, title, url, summary, extractDate(link, context, document), extractImage(link, context, document))
    }.distinctBy { it.url }

    private suspend fun enrich(item: FeedItem): FeedItem = withContext(Dispatchers.IO) {
        runCatching { fetch(item.url) }.map { document -> item.copy(publishedAt = extractArticlePublishedDate(document) ?: item.publishedAt, imageUrl = extractArticleImage(document) ?: item.imageUrl, summary = extractArticleSummary(document) ?: item.summary) }.getOrElse { item }
    }

    private fun findContentContext(link: Element): Element? = link.closest("article") ?: link.closest("[class*=feed-post], [class*=feed-item], [class*=card], [class*=story], [class*=headline], [class*=noticia], [class*=materia], [class*=post], [class*=content]") ?: link.parent()
    private fun extractTitle(link: Element, context: Element): String? = listOf(link.selectFirst("h1,h2,h3,h4,h5,h6")?.text().orEmpty(), context.selectFirst("h1,h2,h3,h4,h5,h6")?.text().orEmpty(), link.text(), link.attr("aria-label"), link.attr("title"), link.selectFirst("img")?.attr("alt").orEmpty()).asSequence().map { it.replace(Regex("\\s+"), " ").trim() }.firstOrNull { it.length in MIN_TITLE_LENGTH..MAX_TITLE_LENGTH }
    private fun isNewsArticle(path: String): Boolean { val normalized = path.lowercase().substringBefore('?').trimEnd('/'); return normalized.isNotBlank() && !normalized.startsWith("/plantao") && normalized.contains("/noticia/") }
    private fun extractImage(link: Element, context: Element, document: Document): String? = (link.select("img, picture img, picture source, source") + context.select("img, picture img, picture source, source")).distinct().asSequence().mapNotNull { imageSource(it) }.map { normalizeImageUrl(it, document.baseUri()) }.firstOrNull(::isUsableImage) ?: extractArticleImage(document)
    private fun imageSource(image: Element): String? { listOf("src", "data-src", "data-lazy-src", "data-original", "data-image", "data-image-url", "data-url", "data-thumb", "data-original-src", "data-lazy", "data-fallback-src").firstNotNullOfOrNull { image.attr(it).takeIf(String::isNotBlank) }?.let { return it }; return image.attr("srcset").ifBlank { image.attr("data-srcset") }.split(',').asSequence().map { it.trim().split(Regex("\\s+"), limit = 2).firstOrNull().orEmpty() }.firstOrNull { it.isNotBlank() } }
    private fun extractArticleImage(document: Document): String? = document.select("meta[property=og:image][content], meta[property=og:image:url][content], meta[name=twitter:image][content], meta[name=twitter:image:src][content]").mapNotNull { it.attr("content").takeIf(String::isNotBlank) }.map { normalizeImageUrl(it, document.baseUri()) }.firstOrNull(::isUsableImage)
    private fun extractArticleSummary(document: Document): String? = document.select("meta[name=description][content], meta[property=og:description][content], meta[name=twitter:description][content]").map { it.attr("content").replace(Regex("\\s+"), " ").trim() }.firstOrNull { it.length >= 30 }
    private fun isUsableImage(url: String): Boolean = (url.startsWith("http://") || url.startsWith("https://")) && listOf("logo", "brand", "avatar", "icon", "favicon", "placeholder", "sprite", "profile", "author", "tracking", "pixel", "1x1", "transparent").none(url.lowercase()::contains)
    private fun extractDate(link: Element, context: Element, document: Document): Instant? { val values = (link.select("time[datetime], time[content], [itemprop=datePublished]") + context.select("time[datetime], time[content], [itemprop=datePublished]") + document.select("meta[property=article:published_time], meta[property=datePublished], meta[name=date]")).flatMap { listOf(it.attr("datetime"), it.attr("content"), it.attr("datePublished")) }; return values.asSequence().mapNotNull(::parseDate).firstOrNull() }
    private fun extractArticlePublishedDate(document: Document): Instant? { val metadata = document.select("meta[property=article:published_time][content], meta[property=datePublished][content], meta[name=date][content], meta[itemprop=datePublished][content], time[itemprop=datePublished][datetime], time[datetime]").mapNotNull { it.attr("content").ifBlank { it.attr("datetime") }.takeIf(String::isNotBlank) }; metadata.asSequence().mapNotNull(::parseDate).firstOrNull()?.let { return it }; return document.select("script[type=application/ld+json]").asSequence().flatMap { DATE_PUBLISHED.findAll(it.data()).asSequence() }.mapNotNull { parseDate(it.groupValues[1]) }.firstOrNull() }
    private fun parseDate(value: String?): Instant? = value?.trim()?.takeIf(String::isNotBlank)?.let { runCatching { Instant.parse(it) }.getOrNull() ?: runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() ?: runCatching { ZonedDateTime.parse(it).toInstant() }.getOrNull() ?: runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }.getOrNull() ?: runCatching { LocalDateTime.parse(it, DateTimeFormatter.ISO_LOCAL_DATE_TIME).atZone(ZoneId.systemDefault()).toInstant() }.getOrNull() }
    private fun normalizeImageUrl(value: String, baseUri: String): String { val trimmed = value.trim().removeSurrounding("\""); if (trimmed.startsWith("//")) return "https:$trimmed"; return runCatching { URI(baseUri).resolve(trimmed).toString() }.getOrElse { trimmed } }
    private companion object { const val TIMEOUT = 15_000; const val ENRICH_CONCURRENCY = 2; const val ENRICH_BATCH_SIZE = 10; const val MIN_TITLE_LENGTH = 8; const val MAX_TITLE_LENGTH = 220; const val USER_AGENT = "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/140.0.0.0 Mobile Safari/537.36 NewsRSS/0.3"; val DATE_PUBLISHED = Regex("""[\"']datePublished[\"']\s*:\s*[\"']([^\"']+)[\"']""", RegexOption.IGNORE_CASE) }
}
