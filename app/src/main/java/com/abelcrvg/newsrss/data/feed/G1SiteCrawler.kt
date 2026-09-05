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

/** Reads G1 directly, treating homepage content as the source of truth. */
class G1SiteCrawler {
    suspend fun crawl(source: FeedSource): Result<List<FeedItem>> = withContext(Dispatchers.IO) {
        runCatching {
            val base = source.siteUrl.trimEnd('/')
            val baseHost = URI(base).host?.removePrefix("www.") ?: error("URL inválida")
            val homepage = fetch(base)

            // The homepage is authoritative: collect every plausible G1 content link
            // found there, without an artificial item limit. Plantao is only a supplement.
            val homepageItems = extractHomepage(homepage, source, baseHost)

            val plantaoUrls = buildList {
                add("$base/plantao/")
                for (page in 2..MAX_PLANTAO_PAGES) {
                    add("$base/plantao/index/feed/pagina-$page.ghtml")
                }
            }
            val latestItems = plantaoUrls.flatMap { url ->
                runCatching { extractPlantao(fetch(url), source, baseHost) }.getOrDefault(emptyList())
            }.distinctBy { it.url }
                .sortedWith(compareByDescending<FeedItem> { it.publishedAt ?: Instant.EPOCH }.thenBy { it.title })

            val combined = (homepageItems + latestItems).distinctBy { it.url }
            if (combined.isEmpty()) error("Nenhum conteúdo foi identificado na página do G1")
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

    private fun extractHomepage(document: Document, source: FeedSource, baseHost: String): List<FeedItem> {
        return document.select("a[href]").mapNotNull { link ->
            val url = link.absUrl("href").trim()
            val uri = runCatching { URI(url) }.getOrNull() ?: return@mapNotNull null
            val host = uri.host?.removePrefix("www.") ?: return@mapNotNull null
            if (host != baseHost) return@mapNotNull null

            // On the G1 homepage, don't require a specific article URL prefix.
            // If a link belongs to the content area and has a meaningful title, it is
            // treated as a G1 story. This deliberately favors completeness over guessing
            // which editorial section the story belongs to.
            if (!isContentLink(uri.path.orEmpty())) return@mapNotNull null

            val context = findContentContext(link)
            if (context == null || context.parents().any { it.tagName() in setOf("nav", "header", "footer") }) {
                return@mapNotNull null
            }

            val title = extractTitle(link, context) ?: return@mapNotNull null
            val publishedAt = extractDate(link, context, document, url)
            val summary = context.select("p")
                .map { it.text().replace(Regex("\\s+"), " ").trim() }
                .firstOrNull { it.length >= 20 && it != title }
            val image = extractImage(context, document)

            FeedItem(
                (source.id + url).hashCode().toUInt().toString(16),
                source.id,
                title,
                url,
                summary,
                publishedAt,
                image
            )
        }.distinctBy { it.url }
    }

    private fun extractPlantao(document: Document, source: FeedSource, baseHost: String): List<FeedItem> {
        return document.select("a[href]").mapNotNull { link ->
            val url = link.absUrl("href").trim()
            val uri = runCatching { URI(url) }.getOrNull() ?: return@mapNotNull null
            if (uri.host?.removePrefix("www.") != baseHost) return@mapNotNull null
            if (!isContentLink(uri.path.orEmpty())) return@mapNotNull null
            val context = findContentContext(link) ?: link.parent() ?: return@mapNotNull null
            val title = extractTitle(link, context) ?: return@mapNotNull null
            FeedItem(
                (source.id + url).hashCode().toUInt().toString(16),
                source.id,
                title,
                url,
                context.select("p").map { it.text().trim() }.firstOrNull { it.length >= 20 },
                extractDate(link, context, document, url),
                extractImage(context, document)
            )
        }.distinctBy { it.url }
    }

    private fun findContentContext(link: Element): Element? =
        link.closest("article")
            ?: link.closest("[class*=feed-post], [class*=feed-item], [class*=card], [class*=story], [class*=headline], [class*=noticia], [class*=materia], [class*=post], [class*=content]")
            ?: link.parent()?.takeIf { it.tagName() in setOf("a", "div", "li") }

    private fun extractTitle(link: Element, context: Element): String? = listOf(
        link.selectFirst("h1, h2, h3, h4, h5, h6")?.text().orEmpty(),
        context.selectFirst("h1, h2, h3, h4, h5, h6")?.text().orEmpty(),
        link.text(),
        link.attr("aria-label"),
        link.attr("title"),
        link.selectFirst("img")?.attr("alt").orEmpty()
    ).asSequence()
        .map { it.replace(Regex("\\s+"), " ").trim() }
        .firstOrNull { it.length in MIN_TITLE_LENGTH..MAX_TITLE_LENGTH }

    private fun isContentLink(path: String): Boolean {
        val normalized = path.lowercase().substringBefore('?')
        if (normalized.isBlank() || normalized == "/" || normalized.startsWith("/plantao")) return false
        val blocked = listOf(
            "/login", "/busca", "/newsletter", "/videos", "/podcasts", "/radio", "/ao-vivo",
            "/fale-conosco", "/sobre", "/termos", "/privacidade", "/globoplay", "/gshow"
        )
        if (blocked.any { normalized.startsWith(it) }) return false
        return normalized.count { it == '/' } >= 2 || normalized.contains("-")
    }

    private fun extractImage(context: Element, document: Document): String? = context.select("img").asSequence()
        .map { image ->
            listOf(
                image.attr("src"), image.attr("data-src"), image.attr("data-lazy-src"),
                image.attr("data-original"), image.attr("srcset").substringBefore(',')
            ).firstOrNull { it.isNotBlank() }.orEmpty()
        }
        .map { normalizeUrl(it, document.baseUri()) }
        .firstOrNull { it.isNotBlank() && !it.lowercase().contains("logo") }

    private fun extractDate(link: Element, context: Element, document: Document, url: String): Instant? {
        val values = buildList {
            addAll(link.select("time[datetime], time[content], [itemprop=datePublished], [itemprop=dateModified]").flatMap { listOf(it.attr("datetime"), it.attr("content"), it.attr("datePublished")) })
            addAll(context.select("time[datetime], time[content], [itemprop=datePublished], [itemprop=dateModified], meta[property=article:published_time]").flatMap { listOf(it.attr("datetime"), it.attr("content"), it.attr("datePublished")) })
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
        const val MAX_PLANTAO_PAGES = 10
        const val MIN_TITLE_LENGTH = 8
        const val MAX_TITLE_LENGTH = 220
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36 NewsRSS/0.2"
    }
}
