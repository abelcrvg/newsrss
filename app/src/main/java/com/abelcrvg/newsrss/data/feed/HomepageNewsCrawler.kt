package com.abelcrvg.newsrss.data.feed

import com.abelcrvg.newsrss.core.feed.FeedItem
import com.abelcrvg.newsrss.core.model.FeedSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Finds likely news links directly from a site's homepage when RSS/Atom is unavailable. */
class HomepageNewsCrawler {
    suspend fun crawl(source: FeedSource): Result<List<FeedItem>> = withContext(Dispatchers.IO) {
        runCatching {
            val document = Jsoup.connect(source.siteUrl)
                .userAgent(USER_AGENT)
                .referrer(REFERRER)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                .header("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.7,en;q=0.5")
                .timeout(TIMEOUT)
                .followRedirects(true)
                .get()

            val baseHost = URI(source.siteUrl).host?.removePrefix("www.") ?: error("URL inválida")
            val candidates = document.select("a[href]")
                .mapNotNull { link -> candidate(source, baseHost, link) }
                .groupBy { it.item.url }
                .values
                .map { matches -> matches.maxBy { it.score }.item }
                .distinctBy { it.url }
                .sortedWith(compareByDescending<FeedItem> { it.publishedAt ?: Instant.EPOCH }.thenBy { it.title })
                .take(MAX_ITEMS)

            if (candidates.isEmpty()) error("Nenhuma notícia foi identificada na página de ${source.name}")
            candidates
        }
    }

    private fun candidate(source: FeedSource, baseHost: String, link: Element): Candidate? {
        val url = link.absUrl("href").trim()
        if (!isValidUrl(url, baseHost)) return null
        val title = extractTitle(link)
        if (title.length !in MIN_TITLE_LENGTH..MAX_TITLE_LENGTH) return null

        val article = link.closest("article")
        val main = link.closest("main")
        val context = article ?: main ?: link.parent() ?: return null
        val contextText = context.text().replace(Regex("\\s+"), " ").trim().lowercase()
        val urlLower = url.lowercase()
        var score = 0

        if (article != null) score += 10
        if (link.closest("[itemtype*=Article], [itemtype*=NewsArticle]") != null) score += 9
        if (link.closest("[class*=card], [class*=story], [class*=headline], [class*=noticia], [class*=materia], [class*=post]") != null) score += 5
        if (main != null) score += 2
        if (urlLower.matches(Regex(".*(/noticia[s]?/|/news/|/story/|/materia[s]?/|/post/|/article/).*"))) score += 8
        if (urlLower.matches(Regex(".*20\\d{2}/\\d{2}/\\d{2}.*"))) score += 5
        if (title.length >= 45) score += 2
        if (link.selectFirst("img") != null || article?.selectFirst("img") != null) score += 4
        if (context.selectFirst("time[datetime]") != null) score += 5
        if (context.selectFirst("time") != null) score += 2
        if (contextText.contains("agora") || contextText.contains("min atrás") || contextText.contains("hora atrás")) score += 2

        if (isNavigationLike(link, urlLower)) score -= 15
        if (urlLower.contains("/tag/") || urlLower.contains("/tags/") || urlLower.contains("/categoria/") || urlLower.contains("/category/")) score -= 10
        if (urlLower.contains("/busca") || urlLower.contains("/search") || urlLower.contains("/login") || urlLower.contains("/entrar") || urlLower.contains("/newsletter") || urlLower.contains("/assine")) score -= 20
        if (score < MIN_SCORE) return null

        val imageUrl = link.selectFirst("img")?.let(::imageSource) ?: article?.selectFirst("img")?.let(::imageSource)
        val publishedAt = extractPublishedAt(context, document = link.ownerDocument())
        val summary = article?.select("p")?.map { it.text().replace(Regex("\\s+"), " ").trim() }?.firstOrNull { it.length >= 30 && it != title }

        return Candidate(
            FeedItem(
                id = (source.id + url).hashCode().toUInt().toString(16),
                sourceId = source.id,
                title = title,
                url = url,
                summary = summary,
                publishedAt = publishedAt,
                imageUrl = imageUrl
            ),
            score
        )
    }

    private fun extractPublishedAt(context: Element, document: org.jsoup.nodes.Document): Instant? {
        val direct = sequenceOf(
            context.selectFirst("time[datetime]")?.attr("datetime"),
            context.selectFirst("time")?.text(),
            context.selectFirst("[itemprop=datePublished]")?.attr("datetime"),
            context.selectFirst("[itemprop=datePublished]")?.attr("content"),
            context.selectFirst("meta[property=article:published_time]")?.attr("content"),
            context.selectFirst("meta[property=datePublished]")?.attr("content")
        ).firstOrNull { !it.isNullOrBlank() }
        parseDate(direct)?.let { return it }

        // Search JSON-LD for the article's canonical publication timestamp.
        document.select("script[type=application/ld+json]").forEach { script ->
            val json = script.data()
            val match = DATE_PUBLISHED_REGEX.find(json)?.groupValues?.getOrNull(1)
            parseDate(match)?.let { return it }
        }
        return null
    }

    private fun extractTitle(link: Element): String = listOf(
        link.selectFirst("h1, h2, h3, h4, h5")?.text().orEmpty(),
        link.text(),
        link.attr("aria-label"),
        link.attr("title"),
        link.selectFirst("img")?.attr("alt").orEmpty()
    ).asSequence()
        .map { it.replace(Regex("\\s+"), " ").trim() }
        .firstOrNull { it.length in MIN_TITLE_LENGTH..MAX_TITLE_LENGTH }
        .orEmpty()

    private fun isValidUrl(url: String, baseHost: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host?.removePrefix("www.") ?: return false
        val samePortal = host == baseHost || host.endsWith(".$baseHost")
        return uri.scheme in listOf("http", "https") && samePortal && uri.fragment == null && !uri.path.isNullOrBlank() && uri.path != "/"
    }

    private fun isNavigationLike(link: Element, url: String): Boolean {
        val parents = link.parents().toList()
        if (parents.any { it.tagName() in setOf("nav", "header", "footer", "aside") }) return true
        val structuralText = (listOf(link.className(), link.id()) + parents.take(5).flatMap { listOf(it.className(), it.id()) }).joinToString(" ").lowercase()
        if (listOf("menu", "navigation", "breadcrumb", "social", "share", "login", "entrar", "newsletter").any { structuralText.contains(it) }) return true
        return listOf("facebook", "instagram", "youtube", "twitter", "x.com", "whatsapp", "login", "entrar", "assine", "assinatura").any { url.contains(it) }
    }

    private fun imageSource(image: Element): String? {
        val direct = listOf("src", "data-src", "data-lazy-src", "data-original", "data-image").firstNotNullOfOrNull { attr -> image.attr(attr).takeIf { it.startsWith("http") } }
        if (direct != null) return direct
        return image.attr("srcset").split(",").asSequence().map { it.trim().substringBefore(" ") }.firstOrNull { it.startsWith("http") }
    }

    private fun parseDate(value: String?): Instant? = value?.trim()?.takeIf { it.isNotBlank() }?.let {
        runCatching { Instant.parse(it) }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull()
            ?: runCatching { ZonedDateTime.parse(it).toInstant() }.getOrNull()
            ?: runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }.getOrNull()
    }

    private data class Candidate(val item: FeedItem, val score: Int)

    private companion object {
        const val TIMEOUT = 20_000
        const val MAX_ITEMS = 50
        const val MIN_SCORE = 5
        const val MIN_TITLE_LENGTH = 25
        const val MAX_TITLE_LENGTH = 180
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36 NewsRSS/0.1"
        const val REFERRER = "https://www.google.com/"
        val DATE_PUBLISHED_REGEX = Regex("\\\"datePublished\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
    }
}
