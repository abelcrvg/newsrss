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
import kotlinx.coroutines.withTimeoutOrNull
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

/** Dedicated direct G1 crawler: extracts the live homepage plus a small current-news overflow. */
class G1SiteCrawler {
    suspend fun crawl(source: FeedSource): Result<List<FeedItem>> = withContext(Dispatchers.IO) {
        runCatching {
            val base = source.siteUrl.trimEnd('/')
            val baseHost = URI(base).host?.removePrefix("www.") ?: error("URL inválida")
            val homepage = fetch(base)
            val homepageItems = extractHomepage(homepage, source, baseHost)

            // Keep the homepage exhaustive, then add only the first current Plantão page
            // as a small overflow. We deliberately do not paginate/archive it.
            val extraItems = runCatching {
                extractHomepage(fetch("$base/plantao/"), source, baseHost)
                    .filterNot { extra -> homepageItems.any { it.url == extra.url } }
                    .take(MAX_EXTRA_ITEMS)
            }.getOrDefault(emptyList())

            val discovered = (homepageItems + extraItems).distinctBy { it.url }

            // Homepage extraction already supplies title, date, summary and card image for
            // most items. Opening every article here made a large homepage refresh painfully
            // slow. Enrich only a bounded prefix and let the remaining cards render immediately.
            val enrichmentTargets = discovered.take(MAX_ENRICH_ITEMS)
            val enrichmentSemaphore = Semaphore(ENRICH_CONCURRENCY)
            val enrichedByUrl = coroutineScope {
                enrichmentTargets.map { item ->
                    async(Dispatchers.IO) {
                        enrichmentSemaphore.withPermit {
                            withTimeoutOrNull(ENRICH_TIMEOUT_MS) { enrich(item) } ?: item
                        }
                    }
                }.awaitAll()
            }.associateBy { it.url }

            discovered.map { enrichedByUrl[it.url] ?: it }
                .distinctBy { it.url }
                .sortedWith(
                    compareByDescending<FeedItem> { it.publishedAt ?: Instant.EPOCH }
                        .thenBy { it.title.lowercase() }
                )
        }
    }

    private fun fetch(url: String): Document = Jsoup.connect(url)
        .userAgent(USER_AGENT)
        .referrer("https://www.google.com/")
        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        .header("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.7,en;q=0.5")
        .timeout(TIMEOUT)
        .followRedirects(true)
        .get()

    private fun extractHomepage(document: Document, source: FeedSource, baseHost: String): List<FeedItem> {
        val structured = document.select(
            "a.feed-post-link[href], a[class*=feed-post-link][href], article a[href*=/noticia/], a[href*=/noticia/][class*=feed-post]"
        ).mapNotNull { extractCardItem(it, source, baseHost) }
        return (structured + extractLinks(document, source, baseHost)).distinctBy { it.url }
    }

    private fun extractCardItem(link: Element, source: FeedSource, baseHost: String): FeedItem? {
        val url = link.absUrl("href").trim()
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.host?.removePrefix("www.") != baseHost || !isNewsArticle(uri.path.orEmpty())) return null
        val card = link.closest("article, .feed-post, [class*=feed-post], [class*=feed-item], [class*=story], [class*=card]") ?: link.parent()
        val title = link.text().replace(Regex("\\s+"), " ").trim()
            .takeIf { it.length in MIN_TITLE_LENGTH..MAX_TITLE_LENGTH }
            ?: extractTitle(link, card ?: link) ?: return null
        val summary = card?.select(".feed-post-body-resumo, [class*=resumo], p")?.map { it.text().replace(Regex("\\s+"), " ").trim() }?.firstOrNull { it.length >= 20 && it != title }
        return FeedItem(
            id = (source.id + url).hashCode().toUInt().toString(16), sourceId = source.id, title = title,
            url = url, summary = summary, publishedAt = extractDate(link, card ?: link, url),
            imageUrl = card?.let { extractCardImage(it) }
        )
    }

    private fun extractLinks(document: Document, source: FeedSource, baseHost: String): List<FeedItem> =
        document.select("a[href]").mapNotNull { link ->
            val url = link.absUrl("href").trim()
            val uri = runCatching { URI(url) }.getOrNull() ?: return@mapNotNull null
            if (uri.host?.removePrefix("www.") != baseHost || !isNewsArticle(uri.path.orEmpty())) return@mapNotNull null
            val context = findContentContext(link) ?: link
            val title = extractTitle(link, context) ?: return@mapNotNull null
            FeedItem(
                id = (source.id + url).hashCode().toUInt().toString(16), sourceId = source.id, title = title,
                url = url,
                summary = context.select("p").map { it.text().replace(Regex("\\s+"), " ").trim() }.firstOrNull { it.length >= 20 && it != title },
                publishedAt = extractDate(link, context, url), imageUrl = extractCardImage(context)
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
        link.closest("article") ?: link.closest("[class*=feed-post], [class*=feed-item], [class*=card], [class*=story], [class*=headline], [class*=noticia], [class*=materia], [class*=post], [class*=content]")
            ?: link.parent()?.takeIf { it.tagName() in setOf("a", "div", "li") }

    private fun extractTitle(link: Element, context: Element): String? = listOf(
        link.selectFirst("h1,h2,h3,h4,h5,h6")?.text().orEmpty(), context.selectFirst("h1,h2,h3,h4,h5,h6")?.text().orEmpty(),
        link.text(), link.attr("aria-label"), link.attr("title"), link.selectFirst("img")?.attr("alt").orEmpty()
    ).asSequence().map { it.replace(Regex("\\s+"), " ").trim() }.firstOrNull { it.length in MIN_TITLE_LENGTH..MAX_TITLE_LENGTH }

    private fun isNewsArticle(path: String): Boolean {
        val normalized = path.lowercase().substringBefore('?').trimEnd('/')
        return normalized.isNotBlank() && normalized.contains("/noticia/")
    }

    /** Only inspect the actual card. Never fall back to the homepage og:image, which is the G1 logo. */
    private fun extractCardImage(card: Element): String? {
        val candidates = card.select("img, picture img, picture source, source").asSequence()
            .flatMap { image -> sequenceOf(
                image.attr("src"), image.attr("data-src"), image.attr("data-lazy-src"), image.attr("data-original"),
                image.attr("data-image"), image.attr("data-image-url"), image.attr("data-url"), image.attr("data-thumb"),
                image.attr("data-original-src"), image.attr("data-lazy"), image.attr("data-fallback-src"),
                image.attr("srcset"), image.attr("data-srcset")
            ) }
            .flatMap { value -> value.split(',').asSequence().map { it.trim().split(Regex("\\s+"), limit = 2).firstOrNull().orEmpty() } }
            .mapNotNull { normalizeImageUrl(it, card.baseUri()).takeIf(String::isNotBlank) }
            .firstOrNull(::isUsableImage)
        return candidates
    }

    private fun extractArticleImage(document: Document): String? = document.select(
        "meta[property=og:image][content], meta[property=og:image:url][content], meta[name=twitter:image][content], meta[name=twitter:image:src][content], link[rel=image_src][href]"
    ).mapNotNull { it.attr("content").ifBlank { it.attr("href") }.takeIf(String::isNotBlank) }
        .map { normalizeImageUrl(it, document.baseUri()) }.firstOrNull(::isUsableImage)

    private fun extractArticleSummary(document: Document): String? = document.select(
        "meta[name=description][content], meta[property=og:description][content], meta[name=twitter:description][content]"
    ).map { it.attr("content").trim() }.firstOrNull { it.length >= 30 }

    private fun isUsableImage(url: String): Boolean {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false
        val value = url.lowercase()
        return listOf("logo", "brand", "avatar", "icon", "favicon", "placeholder", "sprite", "profile", "author", "tracking", "pixel", "1x1", "transparent").none(value::contains)
    }

    private fun extractDate(link: Element, context: Element, url: String): Instant? {
        val values = buildList {
            addAll(link.select("time[datetime],time[content],[itemprop=datePublished],[itemprop=dateModified]").flatMap { listOf(it.attr("datetime"), it.attr("content"), it.attr("datePublished")) })
            addAll(context.select("time[datetime],time[content],[itemprop=datePublished],[itemprop=dateModified]").flatMap { listOf(it.attr("datetime"), it.attr("content"), it.attr("datePublished")) })
        }
        values.asSequence().mapNotNull(::parseDate).firstOrNull()?.let { return it }
        DATE_IN_URL.find(url)?.let { match ->
            val time = match.groupValues.getOrNull(2)?.replace('-', ':') ?: "00:00"
            parseDate("${match.groupValues[1].replace('/', '-')}T$time:00")?.let { return it }
        }
        return null
    }

    private fun extractArticlePublishedDate(document: Document): Instant? {
        document.select("meta[property=article:published_time][content], meta[property=datePublished][content], meta[name=date][content], meta[itemprop=datePublished][content], time[itemprop=datePublished][datetime], time[datetime]")
            .mapNotNull { it.attr("content").ifBlank { it.attr("datetime") }.takeIf(String::isNotBlank) }
            .asSequence().mapNotNull(::parseDate).firstOrNull()?.let { return it }
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
        if (trimmed.isBlank()) return ""
        if (trimmed.startsWith("//")) return "https:$trimmed"
        return runCatching { URI(baseUri).resolve(trimmed).toString() }.getOrElse { trimmed }
    }

    private companion object {
        const val TIMEOUT = 10_000
        const val ENRICH_CONCURRENCY = 6
        const val MAX_ENRICH_ITEMS = 20
        const val ENRICH_TIMEOUT_MS = 8_000L
        const val MAX_EXTRA_ITEMS = 40
        const val MIN_TITLE_LENGTH = 8
        const val MAX_TITLE_LENGTH = 220
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36 NewsRSS/0.3"
        val DATE_IN_URL = Regex("(20\\d{2}[-/]\\d{2}[-/]\\d{2})(?:[T/-](\\d{2}[-:]\\d{2}))?")
        val DATE_PUBLISHED = Regex("""[\"']datePublished[\"']\s*:\s*[\"']([^\"']+)[\"']""", RegexOption.IGNORE_CASE)
    }
}
