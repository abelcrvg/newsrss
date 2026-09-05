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
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Finds news directly from a site's homepage when RSS/Atom is unavailable. */
class HomepageNewsCrawler {
    suspend fun crawl(source: FeedSource): Result<List<FeedItem>> = withContext(Dispatchers.IO) {
        runCatching {
            val baseHost = URI(source.siteUrl).host?.removePrefix("www.") ?: error("URL inválida")
            val documents = mutableListOf<Document>()
            if (isGloboPortal(source, baseHost)) {
                val plantaoUrls = buildList {
                    add("${source.siteUrl.trimEnd('/')}/plantao/")
                    for (page in 2..GLOBO_MAX_PAGES) add("${source.siteUrl.trimEnd('/')}/plantao/index/feed/pagina-$page.ghtml")
                }
                plantaoUrls.forEach { url -> runCatching { fetch(url) }.onSuccess { documents += it } }
            }
            if (documents.isEmpty()) documents += fetch(source.siteUrl)
            val candidates = documents.flatMap { document -> document.select("a[href]").mapNotNull { link -> candidate(source, baseHost, link, document) } }
                .groupBy { it.item.url }
                .values
                .map { it.maxBy { candidate -> candidate.score }.item }
                .distinctBy { it.url }
                .sortedWith(compareByDescending<FeedItem> { it.publishedAt ?: Instant.EPOCH }.thenBy { it.title })
                .take(MAX_ITEMS)
            if (candidates.isEmpty()) error("Nenhuma notícia foi identificada na página de ${source.name}")
            candidates
        }
    }

    private fun fetch(url: String): Document = Jsoup.connect(url).userAgent(USER_AGENT).referrer(REFERRER)
        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        .header("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.7,en;q=0.5").timeout(TIMEOUT).followRedirects(true).get()

    private fun candidate(source: FeedSource, baseHost: String, link: Element, document: Document): Candidate? {
        val url = link.absUrl("href").trim()
        if (!isValidUrl(url, baseHost)) return null
        val title = extractTitle(link)
        if (title.length !in MIN_TITLE_LENGTH..MAX_TITLE_LENGTH) return null
        val article = link.closest("article")
        val card = link.closest("[class*=feed-post], [class*=feed-item], [class*=card], [class*=story], [class*=headline], [class*=noticia], [class*=materia], [class*=post], [class*=feed]")
        val context = article ?: card ?: link.parent() ?: return null
        val contextText = context.text().replace(Regex("\\s+"), " ").trim()
        var score = 0
        if (article != null) score += 12
        if (card != null) score += 8
        if (link.closest("[itemtype*=Article], [itemtype*=NewsArticle]") != null) score += 9
        if (isGloboPortal(source, baseHost) && url.contains("/noticia/", true)) score += 8
        if (url.contains("/materia/", true)) score += 5
        if (url.matches(Regex(".*20\\d{2}/\\d{2}/\\d{2}.*"))) score += 5
        if (title.length >= 45) score += 2
        if (findImageInContext(link, context) != null) score += 5
        if (findDateText(link, context) != null) score += 5
        if (isNavigationLike(link, url)) score -= 20
        if (score < MIN_SCORE) return null
        val imageUrl = findImageInContext(link, context) ?: extractImageFromDocument(document, url)
        val publishedAt = extractPublishedAt(link, context, document, url, contextText)
        val summary = context.select("p").map { it.text().replace(Regex("\\s+"), " ").trim() }.firstOrNull { it.length >= 30 && it != title }
        return Candidate(FeedItem((source.id + url).hashCode().toUInt().toString(16), source.id, title, url, summary, publishedAt, imageUrl), score)
    }

    private fun findImageInContext(link: Element, context: Element): String? {
        val nodes = buildList {
            link.select("img, picture img, picture source, source").forEach { add(it) }
            context.select("img, picture img, picture source, source").take(12).forEach { add(it) }
        }.distinct()
        nodes.forEach { node -> imageSource(node)?.let { url -> if (!looksLikeLogo(url)) return url } }
        context.select("[style*=background-image], [data-background-image], [data-bg], [data-bg-src]").forEach { element ->
            backgroundImageSource(element)?.let { url -> if (!looksLikeLogo(url)) return url }
        }
        return null
    }

    private fun extractImageFromDocument(document: Document, articleUrl: String): String? {
        val metadata = document.select("meta[property=og:image][content], meta[property=og:image:url][content], meta[name=twitter:image][content], meta[name=twitter:image:src][content], link[rel=image_src][href]")
            .mapNotNull { it.attr("content").ifBlank { it.attr("href") }.takeIf(String::isNotBlank) }
            .map { normalizeImageUrl(it, document.baseUri()) }
            .firstOrNull { it.isNotBlank() && !looksLikeLogo(it) }
        if (metadata != null) return metadata
        document.select("[style*=background-image], [data-background-image], [data-bg], [data-bg-src]").forEach { element ->
            backgroundImageSource(element)?.let { image -> if (!looksLikeLogo(image) && articleUrl.isNotBlank()) return image }
        }
        return null
    }

    private fun imageSource(image: Element): String? {
        val attrs = listOf("src", "data-src", "data-lazy-src", "data-original", "data-image", "data-url", "data-thumb", "data-image-url", "data-bg", "data-bg-src", "data-background-image", "data-original-src", "data-lazy", "data-fallback-src", "data-placeholder-src")
        attrs.firstNotNullOfOrNull { image.attr(it).takeIf(String::isNotBlank) }?.let { normalizeImageUrl(it, image.baseUri()).takeIf(String::isNotBlank) }?.let { return it }
        val srcset = image.attr("srcset").ifBlank { image.attr("data-srcset") }
        srcset.split(",").asSequence().map { it.trim().substringBefore(" ") }.map { normalizeImageUrl(it, image.baseUri()) }.filter { it.isNotBlank() }.maxByOrNull { imageWidthHint(it, srcset) }?.let { return it }
        return null
    }

    private fun backgroundImageSource(element: Element): String? {
        val direct = listOf("data-background-image", "data-bg", "data-bg-src").firstNotNullOfOrNull { element.attr(it).takeIf(String::isNotBlank) }
        if (!direct.isNullOrBlank()) return normalizeImageUrl(direct, element.baseUri()).takeIf { it.isNotBlank() }
        val match = Regex("url\\(\\s*['\\\"]?([^'\\\")]+)", RegexOption.IGNORE_CASE).find(element.attr("style"))
        return match?.groupValues?.getOrNull(1)?.let { normalizeImageUrl(it, element.baseUri()) }?.takeIf { it.isNotBlank() }
    }

    private fun imageWidthHint(url: String, srcset: String): Int = srcset.split(',').map { it.trim() }.firstOrNull { it.startsWith(url) }?.substringAfterLast(' ', "")?.removeSuffix("w")?.toIntOrNull() ?: 0

    private fun looksLikeLogo(url: String): Boolean {
        val value = url.lowercase()
        return listOf("logo", "brand", "avatar", "icon", "favicon", "placeholder", "sprite", "profile", "author", "tracking", "pixel", "1x1", "transparent").any(value::contains)
    }

    private fun extractPublishedAt(link: Element, context: Element, document: Document, url: String, text: String): Instant? {
        val values = mutableListOf<String>()
        values += link.select("time[datetime], time[content], [itemprop=datePublished], [itemprop=dateModified]").flatMap { e -> listOf(e.attr("datetime"), e.attr("content"), e.attr("datePublished"), e.text()) }
        values += context.select("time[datetime], time[content], [itemprop=datePublished], [itemprop=dateModified], [data-published], [data-date]").flatMap { e -> listOf(e.attr("datetime"), e.attr("content"), e.attr("datePublished"), e.attr("data-published"), e.attr("data-date"), e.text()) }
        values += context.select("meta[property=article:published_time], meta[property=datePublished], meta[name=date]").map { it.attr("content") }
        values += context.select("[class*=date], [class*=time], [class*=published], [class*=publish], [class*=data]").flatMap { e -> listOf(e.attr("datetime"), e.attr("content"), e.attr("data-date"), e.attr("data-datetime"), e.text()) }
        values.asSequence().mapNotNull { parseDate(it) ?: parseRelativeDate(it) }.firstOrNull()?.let { return it }
        findDateText(link, context)?.let { nearby -> parseDate(nearby)?.let { return it }; parseRelativeDate(nearby)?.let { return it } }
        parseRelativeDate(text)?.let { return it }
        DATE_IN_URL_REGEX.find(url)?.let { match -> parseDate("${match.groupValues[1].replace('/', '-')}T${match.groupValues.getOrNull(2)?.replace('-', ':') ?: "00:00"}:00")?.let { return it } }
        document.select("script[type=application/ld+json]").forEach { script -> DATE_PUBLISHED_REGEX.findAll(script.data()).forEach { match -> parseDate(match.groupValues[1])?.let { return it } } }
        return null
    }

    private fun findDateText(link: Element, context: Element): String? {
        val dateRegex = Regex("(?:há|ha)\\s+\\d+\\s*(?:min|minuto|minutos|h|hora|horas|d|dia|dias)|\\bontem\\b|\\bagora\\b", RegexOption.IGNORE_CASE)
        val elements = buildList { add(link); addAll(link.parent()?.children().orEmpty()); var parent = link.parent(); repeat(4) { if (parent != null) { add(parent); addAll(parent.children()); parent = parent?.parent() } }; add(context) }
        return elements.asSequence().map { it.text().replace(Regex("\\s+"), " ").trim() }.mapNotNull { dateRegex.find(it)?.value }.firstOrNull()
    }

    private fun parseRelativeDate(value: String?): Instant? {
        if (value.isNullOrBlank()) return null
        val text = value.lowercase().replace(Regex("\\s+"), " ").trim()
        val now = Instant.now()
        Regex("(?:há|ha)\\s+(\\d+)\\s*(?:min|minuto|minutos)").find(text)?.groupValues?.get(1)?.toLongOrNull()?.let { return now.minusSeconds(it * 60) }
        Regex("(?:há|ha)\\s+(\\d+)\\s*(?:h|hora|horas)").find(text)?.groupValues?.get(1)?.toLongOrNull()?.let { return now.minusSeconds(it * 3600) }
        Regex("(?:há|ha)\\s+(\\d+)\\s*(?:d|dia|dias)").find(text)?.groupValues?.get(1)?.toLongOrNull()?.let { return now.minusSeconds(it * 86400) }
        if (text.contains("ontem")) return now.minusSeconds(86400)
        if (text.contains("agora")) return now
        return null
    }

    private fun extractTitle(link: Element): String = listOf(link.selectFirst("h1, h2, h3, h4, h5")?.text().orEmpty(), link.text(), link.attr("aria-label"), link.attr("title"), link.selectFirst("img")?.attr("alt").orEmpty()).asSequence().map { it.replace(Regex("\\s+"), " ").trim() }.firstOrNull { it.length in MIN_TITLE_LENGTH..MAX_TITLE_LENGTH }.orEmpty()

    private fun isValidUrl(url: String, baseHost: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host?.removePrefix("www.") ?: return false
        return uri.scheme in listOf("http", "https") && (host == baseHost || host.endsWith(".$baseHost")) && uri.fragment == null && !uri.path.isNullOrBlank() && uri.path != "/"
    }

    private fun isNavigationLike(link: Element, url: String): Boolean {
        if (link.parents().any { it.tagName() in setOf("nav", "header", "footer", "aside") }) return true
        val structure = (listOf(link.className(), link.id()) + link.parents().take(5).flatMap { listOf(it.className(), it.id()) }).joinToString(" ").lowercase()
        return listOf("menu", "navigation", "breadcrumb", "social", "share", "login", "entrar", "newsletter").any(structure::contains) || listOf("facebook", "instagram", "youtube", "twitter", "whatsapp", "login", "entrar", "assine").any(url.lowercase()::contains)
    }

    private fun normalizeImageUrl(value: String, baseUri: String): String {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return ""
        if (trimmed.startsWith("//")) return "https:$trimmed"
        return runCatching { URI(baseUri).resolve(trimmed).toString() }.getOrElse { trimmed }.takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: ""
    }

    private fun isGloboPortal(source: FeedSource, baseHost: String): Boolean = baseHost == "g1.globo.com" || baseHost == "ge.globo.com" || source.id == "g1" || source.id == "ge"

    private fun parseDate(value: String?): Instant? = value?.trim()?.takeIf(String::isNotBlank)?.let {
        runCatching { Instant.parse(it) }.getOrNull() ?: runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() ?: runCatching { ZonedDateTime.parse(it).toInstant() }.getOrNull() ?: runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }.getOrNull() ?: runCatching { LocalDateTime.parse(it, DateTimeFormatter.ISO_LOCAL_DATE_TIME).atZone(ZoneId.systemDefault()).toInstant() }.getOrNull() ?: runCatching { LocalDateTime.parse(it, DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")).atZone(ZoneId.systemDefault()).toInstant() }.getOrNull() ?: runCatching { LocalDateTime.parse(it, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).atZone(ZoneId.systemDefault()).toInstant() }.getOrNull()
    }

    private data class Candidate(val item: FeedItem, val score: Int)

    private companion object {
        const val TIMEOUT = 20_000
        const val MAX_ITEMS = 150
        const val GLOBO_MAX_PAGES = 10
        const val MIN_SCORE = 3
        const val MIN_TITLE_LENGTH = 25
        const val MAX_TITLE_LENGTH = 180
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36 NewsRSS/0.1"
        const val REFERRER = "https://www.google.com/"
        val DATE_PUBLISHED_REGEX = Regex("\\\"datePublished\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
        val DATE_IN_URL_REGEX = Regex("(20\\d{2}[-/]\\d{2}[-/]\\d{2})(?:[T/-](\\d{2}[-:]\\d{2}))?")
    }
}
