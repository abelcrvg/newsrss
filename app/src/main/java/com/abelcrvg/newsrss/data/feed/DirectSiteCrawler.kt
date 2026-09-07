package com.abelcrvg.newsrss.data.feed

import com.abelcrvg.newsrss.core.feed.FeedItem

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

/** Source-specific direct HTML crawler engine. No RSS and no artificial article-count limit. */
class DirectSiteCrawler(private val config: Config) {
    data class Config(
        val sourceId: String,
        val homeUrl: String,
        val allowedHosts: Set<String>,
        val articlePath: (String) -> Boolean,
        val selectors: String,
        val excludedText: Set<String> = emptySet()
    )

    suspend fun crawl(): Result<List<FeedItem>> = withContext(Dispatchers.IO) {
        runCatching {
            val document = fetch(config.homeUrl)
            val found = linkedMapOf<String, FeedItem>()
            document.select(config.selectors).forEach { link ->
                val url = link.absUrl("href").trim()
                if (!isArticle(url)) return@forEach
                val context = link.closest("article, li, [class*=card], [class*=item], [class*=story], [class*=tile], [class*=post]") ?: link
                val title = titleOf(link, context) ?: return@forEach
                if (config.excludedText.any { title.equals(it, ignoreCase = true) }) return@forEach
                val item = FeedItem(stableId(config.sourceId, url), config.sourceId, title, url, summaryOf(context, title), dateOf(link, context), imageOf(context, config.homeUrl))
                val old = found[url]
                if (old == null || (old.imageUrl == null && item.imageUrl != null)) found[url] = item
            }
            val semaphore = Semaphore(ENRICH_CONCURRENCY)
            coroutineScope {
                found.values.map { item -> async(Dispatchers.IO) { semaphore.withPermit { enrich(item) } } }.awaitAll()
            }.distinctBy { it.url }
                .sortedWith(compareByDescending<FeedItem> { it.publishedAt ?: Instant.EPOCH }.thenBy { it.title.lowercase() })
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

    private fun isArticle(url: String): Boolean = runCatching {
        val uri = URI(url)
        val host = uri.host?.lowercase()?.removePrefix("www.") ?: return@runCatching false
        val allowed = config.allowedHosts.map { it.lowercase().removePrefix("www.") }.toSet()
        host in allowed && config.articlePath(uri.path.orEmpty().lowercase())
    }.getOrDefault(false)

    private fun titleOf(link: Element, context: Element): String? = sequenceOf(context.selectFirst("h1,h2,h3,h4,h5,h6")?.text(), link.text(), link.attr("aria-label"), link.attr("title"), link.selectFirst("img")?.attr("alt"))
        .mapNotNull(::clean).firstOrNull { it.length in MIN_TITLE..MAX_TITLE }

    private fun summaryOf(context: Element, title: String): String? = context.select("p,[class*=summary],[class*=subtitle],[class*=description],[class*=resumo],[class*=subtitulo]")
        .mapNotNull { clean(it.text()) }.firstOrNull { it.length >= 30 && !it.equals(title, true) }

    private fun imageOf(context: Element, base: String): String? = context.select("img,source").asSequence()
        .flatMap { node -> sequenceOf(node.attr("src"), node.attr("data-src"), node.attr("data-lazy-src"), node.attr("data-original"), node.attr("data-image"), node.attr("data-image-url"), node.attr("srcset"), node.attr("data-srcset")) }
        .flatMap { value -> value.split(',').asSequence().map { it.trim().split(Regex("\\s+")).firstOrNull().orEmpty() } }
        .mapNotNull { absolute(it, base) }.firstOrNull(::usableImage)

    private fun dateOf(link: Element, context: Element): Instant? = (link.select("time[datetime],time[content],[itemprop=datePublished]") + context.select("time[datetime],time[content],[itemprop=datePublished]"))
        .asSequence().flatMap { sequenceOf(it.attr("datetime"), it.attr("content"), it.attr("datePublished")) }.mapNotNull(::parseDate).firstOrNull()

    private suspend fun enrich(item: FeedItem): FeedItem = withContext(Dispatchers.IO) {
        runCatching {
            val doc = fetch(item.url)
            item.copy(
                title = clean(doc.select("meta[property=og:title][content],meta[name=twitter:title][content]").firstOrNull()?.attr("content")) ?: item.title,
                summary = clean(doc.select("meta[property=og:description][content],meta[name=description][content],meta[name=twitter:description][content]").firstOrNull()?.attr("content")) ?: item.summary,
                publishedAt = metadataDate(doc) ?: item.publishedAt,
                imageUrl = clean(doc.select("meta[property=og:image][content],meta[name=twitter:image][content]").firstOrNull()?.attr("content"))?.let { absolute(it, item.url) } ?: item.imageUrl
            )
        }.getOrElse { item }
    }

    private fun metadataDate(doc: Document): Instant? {
        val metadata = doc.select("meta[property=article:published_time][content],meta[property=datePublished][content],meta[name=datePublished][content],meta[itemprop=datePublished][content],time[itemprop=datePublished][datetime],time[datetime]")
            .mapNotNull { it.attr("content").ifBlank { it.attr("datetime") }.takeIf(String::isNotBlank) }
        metadata.asSequence().mapNotNull(::parseDate).firstOrNull()?.let { return it }
        return doc.select("script[type=application/ld+json]").asSequence().flatMap { DATE_PUBLISHED.findAll(it.data()).asSequence() }.mapNotNull { parseDate(it.groupValues[1]) }.firstOrNull()
    }

    private fun parseDate(value: String?): Instant? = value?.trim()?.takeIf(String::isNotBlank)?.let { runCatching { Instant.parse(it) }.getOrNull() ?: runCatching { java.time.OffsetDateTime.parse(it).toInstant() }.getOrNull() ?: runCatching { java.time.ZonedDateTime.parse(it).toInstant() }.getOrNull() ?: runCatching { java.time.ZonedDateTime.parse(it, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }.getOrNull() ?: runCatching { java.time.LocalDateTime.parse(it, java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME).atZone(java.time.ZoneId.systemDefault()).toInstant() }.getOrNull() }

    private fun clean(value: String?): String? = value?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotBlank() }

    private fun absolute(value: String, base: String): String? = if (value.isBlank()) null else runCatching { URI(base).resolve(value.trim()).toString() }.getOrNull()?.takeIf { it.startsWith("http://") || it.startsWith("https://") }

    private fun usableImage(url: String): Boolean = listOf("logo", "avatar", "author", "icon", "sprite", "pixel", "tracking", "placeholder", "banner", "favicon", "1x1", "transparent").none(url.lowercase()::contains)

    private fun stableId(source: String, url: String) = (source + url).hashCode().toUInt().toString(16)

    companion object {
        private const val TIMEOUT = 15_000
        private const val ENRICH_CONCURRENCY = 6
        private const val MIN_TITLE = 12
        private const val MAX_TITLE = 240
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/140.0.0.0 Mobile Safari/537.36 NewsRSS/0.3"
        private val DATE_PUBLISHED = Regex("""[\"']datePublished[\"']\s*:\s*[\"']([^\"']+)[\"']""", RegexOption.IGNORE_CASE)
    }
}
