package com.abelcrvg.newsrss.data.feed

import com.abelcrvg.newsrss.core.feed.FeedItem
import com.abelcrvg.newsrss.core.model.FeedSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZonedDateTime

/** Reads G1 directly, combining homepage highlights with the latest stories. */
class G1SiteCrawler {
    suspend fun crawl(source: FeedSource): Result<List<FeedItem>> = withContext(Dispatchers.IO) {
        runCatching {
            val base = source.siteUrl.trimEnd('/')
            val baseHost = URI(base).host?.removePrefix("www.") ?: error("URL inválida")
            val documents = mutableListOf<Pair<Document, Boolean>>()

            // Homepage: this is the primary source and must not be lost if plantao changes.
            runCatching { fetch(base) }.onSuccess { documents += it to true }

            // Plantao: supplement the homepage with a larger chronological backlog.
            val plantaoUrls = buildList {
                add("$base/plantao/")
                for (page in 2..MAX_PLANTAO_PAGES) {
                    add("$base/plantao/index/feed/pagina-$page.ghtml")
                }
            }
            plantaoUrls.forEach { url -> runCatching { fetch(url) }.onSuccess { documents += it to false } }

            val homepageItems = documents.firstOrNull { it.second }
                ?.let { extract(it.first, source, baseHost, true) }
                .orEmpty()

            val latestItems = documents.filterNot { it.second }
                .flatMap { extract(it.first, source, baseHost, false) }
                .distinctBy { it.url }
                .sortedWith(compareByDescending<FeedItem> { it.publishedAt ?: Instant.EPOCH }.thenBy { it.title })
                .take(MAX_LATEST_ITEMS)

            // Keep homepage stories first so the app visibly represents the G1 homepage,
            // then append latest stories that were not already featured.
            val combined = (homepageItems + latestItems)
                .distinctBy { it.url }
                .take(MAX_ITEMS)

            if (combined.isEmpty()) error("Nenhuma notícia foi identificada na página do G1")
            combined
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

    private fun extract(document: Document, source: FeedSource, baseHost: String, homepage: Boolean): List<FeedItem> {
        val candidates = document.select("a[href]").mapNotNull { link ->
            val url = link.absUrl("href").trim()
            val uri = runCatching { URI(url) }.getOrNull() ?: return@mapNotNull null
            val host = uri.host?.removePrefix("www.") ?: return@mapNotNull null
            val path = uri.path.orEmpty().lowercase()

            // G1 article URLs are normally /noticia/, but keep other article-like paths
            // instead of discarding valid homepage stories when the site changes markup.
            if (host != baseHost || !isArticlePath(path)) return@mapNotNull null

            val context = link.closest("article")
                ?: link.closest("[class*=feed-post], [class*=feed-item], [class*=card], [class*=story], [class*=headline], [class*=noticia], [class*=materia], [class*=post]")
                ?: link.parent()
                ?: return@mapNotNull null
            if (context.parents().any { it.tagName() in setOf("nav", "header", "footer", "aside") }) return@mapNotNull null

            val title = listOf(
                link.selectFirst("h1, h2, h3, h4, h5")?.text().orEmpty(),
                link.text(),
                link.attr("aria-label"),
                link.attr("title"),
                link.selectFirst("img")?.attr("alt").orEmpty()
            ).asSequence()
                .map { it.replace(Regex("\\s+"), " ").trim() }
                .firstOrNull { it.length in MIN_TITLE_LENGTH..MAX_TITLE_LENGTH }
                ?: return@mapNotNull null

            val publishedAt = extractDate(link, context, document, url)
            val summary = context.select("p")
                .map { it.text().replace(Regex("\\s+"), " ").trim() }
                .firstOrNull { it.length >= 30 && it != title }
            val image = context.select("img").asSequence()
                .map { image ->
                    listOf(
                        image.attr("src"), image.attr("data-src"), image.attr("data-lazy-src"),
                        image.attr("data-original"), image.attr("srcset").substringBefore(',')
                    ).firstOrNull { it.isNotBlank() }.orEmpty()
                }
                .map { normalizeUrl(it, document.baseUri()) }
                .firstOrNull { it.isNotBlank() && !it.lowercase().contains("logo") }

            FeedItem(
                (source.id + url).hashCode().toUInt().toString(16),
                source.id,
                title,
                url,
                summary,
                publishedAt,
                image
            )
        }

        return candidates.distinctBy { it.url }
            .sortedWith(compareByDescending<FeedItem> { it.publishedAt ?: Instant.EPOCH }.thenBy { it.title })
            .let { items -> if (homepage) items.take(MAX_HOMEPAGE_ITEMS) else items }
    }

    private fun isArticlePath(path: String): Boolean = path.startsWith("/noticia/") ||
        path.startsWith("/politica/") ||
        path.startsWith("/economia/") ||
        path.startsWith("/mundo/") ||
        path.startsWith("/brasil/") ||
        path.startsWith("/saude/") ||
        path.startsWith("/ciencia/") ||
        path.startsWith("/tecnologia/") ||
        path.startsWith("/esportes/") ||
        path.startsWith("/pop-arte/") ||
        path.startsWith("/g1-em-1-minuto/")

    private fun extractDate(link: Element, context: Element, document: Document, url: String): Instant? {
        val values = buildList {
            addAll(link.select("time[datetime], time[content], [itemprop=datePublished], [itemprop=dateModified]").flatMap { listOf(it.attr("datetime"), it.attr("content"), it.attr("datePublished")) })
            addAll(context.select("time[datetime], time[content], [itemprop=datePublished], [itemprop=dateModified], meta[property=article:published_time]").flatMap { listOf(it.attr("datetime"), it.attr("content"), it.attr("datePublished")) })
            addAll(document.select("meta[property=article:published_time][content], meta[name=date][content]").map { it.attr("content") })
        }
        values.asSequence().mapNotNull { parseDate(it) }.firstOrNull()?.let { return it }
        Regex("(20\\d{2}[-/]\\d{2}[-/]\\d{2})(?:[T/-](\\d{2}[-:]\\d{2}))?").find(url)?.let { match ->
            val time = match.groupValues.getOrNull(2)?.replace('-', ':') ?: "00:00"
            parseDate("${match.groupValues[1].replace('/', '-')}T$time:00")?.let { return it }
        }
        return null
    }

    private fun parseDate(value: String?): Instant? = value?.trim()?.takeIf(String::isNotBlank)?.let {
        runCatching { Instant.parse(it) }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull()
            ?: runCatching { ZonedDateTime.parse(it).toInstant() }.getOrNull()
    }

    private fun normalizeUrl(value: String, baseUri: String): String {
        if (value.isBlank()) return ""
        return runCatching { URI(baseUri).resolve(value).toString() }.getOrElse { value }
    }

    private companion object {
        const val TIMEOUT = 20_000
        const val MAX_HOMEPAGE_ITEMS = 80
        const val MAX_LATEST_ITEMS = 120
        const val MAX_ITEMS = 180
        const val MAX_PLANTAO_PAGES = 10
        const val MIN_TITLE_LENGTH = 15
        const val MAX_TITLE_LENGTH = 180
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36 NewsRSS/0.2"
    }
}
