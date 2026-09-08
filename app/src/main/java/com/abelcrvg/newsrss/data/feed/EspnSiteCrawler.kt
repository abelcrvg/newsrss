package com.abelcrvg.newsrss.data.feed

import com.abelcrvg.newsrss.core.feed.FeedItem
import com.abelcrvg.newsrss.core.model.FeedSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.jsoup.nodes.Element
import org.jsoup.Jsoup
import java.net.URI
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZonedDateTime

/** Dedicated crawler for ESPN Brasil football pages. */
class EspnSiteCrawler {
    suspend fun crawl(source: FeedSource): Result<List<FeedItem>> = withContext(Dispatchers.IO) {
        runCatching {
            val documents = coroutineScope {
                listOf("https://www.espn.com.br/futebol", "https://www.espn.com.br/futebol/noticias", "https://www.espn.com.br/futebol/resultados")
                    .distinct().map { url -> async(Dispatchers.IO) { runCatching { fetch(url) }.getOrNull() } }.awaitAll().filterNotNull()
            }
            val candidates = LinkedHashMap<String, FeedItem>()
            documents.forEach { document -> document.select("a[href]").forEach { link ->
                candidate(source, link)?.let { item ->
                    val previous = candidates[item.url]
                    candidates[item.url] = when {
                        previous == null -> item
                        previous.imageUrl == null && item.imageUrl != null -> previous.copy(imageUrl = item.imageUrl)
                        previous.publishedAt == null && item.publishedAt != null -> previous.copy(publishedAt = item.publishedAt)
                        else -> previous
                    }
                }
            } } 
            if (candidates.isEmpty()) error("Nenhuma notícia da ESPN foi identificada")
            candidates.values.sortedWith(compareByDescending<FeedItem> { it.publishedAt ?: Instant.EPOCH }.thenBy { it.title })
        }
    }

    private fun fetch(url: String) = Jsoup.connect(url).userAgent(USER_AGENT).referrer("https://www.google.com/")
        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        .header("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.7").timeout(TIMEOUT).followRedirects(true).get()

    private fun candidate(source: FeedSource, link: Element): FeedItem? {
        val url = link.absUrl("href").trim()
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val host = uri.host?.lowercase()?.removePrefix("www.") ?: return null
        if (host != "espn.com.br" && !host.endsWith(".espn.com.br")) return null
        val path = uri.path.orEmpty().lowercase()
        if (!path.startsWith("/futebol/")) return null
        if (path.contains("/resultados") || path.contains("/fixtures") || path.contains("/classificacao") || path.contains("/time/") || path.contains("/jogador/") || path.contains("/partida") || path.contains("/video/")) return null
        if (path == "/futebol/noticias" || path == "/futebol/artigos") return null
        val articlePath = path.contains("/artigo/") || path.contains("/noticias/") || path.contains("/story/") || path.contains("/news/")
        if (!articlePath) return null

        val title = sequenceOf(link.selectFirst("h1,h2,h3,h4,h5")?.text(), link.text(), link.attr("aria-label"), link.attr("title"))
            .mapNotNull { it?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { value -> value.length in 12..240 } }.firstOrNull() ?: return null
        val context = link.closest("article") ?: link.closest("li") ?: link.closest("section") ?: link.parent() ?: return null
        if (context.parents().any { it.tagName() in setOf("nav", "header", "footer") }) return null

        // ESPN often keeps the image in a wrapper above the immediate article node.
        // Search the link, its card and a few ancestors, including lazy-loading attributes.
        val ancestors = sequenceOf(link, context) + context.parents().take(5).asSequence()
        val image = ancestors.flatMap { it.select("img,source").asSequence() }
            .mapNotNull(::imageSource).firstOrNull()
        val date = ancestors.flatMap { it.select("time[datetime], [itemprop=datePublished], meta[property=datePublished], meta[name=date]").asSequence() }
            .mapNotNull { parseDate(it.attr("datetime").ifBlank { it.attr("content") }.ifBlank { it.text() }) }.firstOrNull()
        val summary = context.select("p").map { it.text().trim() }.firstOrNull { it.length >= 30 && it != title }
        return FeedItem((source.id + url).hashCode().toUInt().toString(16), source.id, title, url, summary, date, image)
    }

    private fun imageSource(element: Element): String? {
        val raw = listOf("src", "data-src", "data-lazy-src", "data-original", "data-image", "data-image-url", "data-img-src", "data-image-src", "data-srcset")
            .firstNotNullOfOrNull { element.attr(it).takeIf(String::isNotBlank) }
            ?: element.attr("srcset").split(',').asSequence().map { it.trim().substringBefore(' ') }.firstOrNull { it.isNotBlank() }
        if (raw.isNullOrBlank()) return null
        val normalized = if (raw.startsWith("//")) "https:$raw" else runCatching { URI(element.baseUri()).resolve(raw).toString() }.getOrElse { raw }
        return normalized.takeIf { (it.startsWith("http://") || it.startsWith("https://")) && !looksLikeNoise(it) }
    }

    private fun looksLikeNoise(url: String): Boolean = listOf("logo", "avatar", "icon", "sprite", "placeholder", "tracking", "pixel", "1x1").any(url.lowercase()::contains)
    private fun parseDate(value: String): Instant? = runCatching { Instant.parse(value) }.getOrNull() ?: runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull() ?: runCatching { ZonedDateTime.parse(value).toInstant() }.getOrNull()

    private companion object {
        const val TIMEOUT = 12_000
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/140.0.0.0 Mobile Safari/537.36 NewsRSS/0.3"
    }
}
