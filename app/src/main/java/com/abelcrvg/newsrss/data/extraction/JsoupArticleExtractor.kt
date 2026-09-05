package com.abelcrvg.newsrss.data.extraction

import com.abelcrvg.newsrss.core.extraction.ArticleExtractor
import com.abelcrvg.newsrss.core.model.Article
import com.abelcrvg.newsrss.core.model.ArticleBlock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Generic reader-mode extractor for publicly available article HTML. */
class JsoupArticleExtractor(private val timeoutMillis: Int = 20_000) : ArticleExtractor {
    override suspend fun extract(url: String): Result<Article> = withContext(Dispatchers.IO) {
        runCatching {
            require(url.startsWith("http://") || url.startsWith("https://"))
            val document = Jsoup.connect(url).userAgent(USER_AGENT).timeout(timeoutMillis).followRedirects(true)
                .referrer("https://www.google.com/")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                .header("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.8").get()

            val theVerge = isTheVerge(url)
            removeNoise(document)
            val title = firstNonBlank(
                document.select("meta[property=og:title]").attr("content"),
                document.select("meta[name=twitter:title]").attr("content"),
                document.select("h1").first()?.text(),
                document.title()
            ) ?: error("Article title not found")

            var blocks = if (theVerge) extractTheVergeBlocks(document) else emptyList()
            var textLength = blocks.sumOf { textOf(it).length }

            for (candidate in buildContentCandidates(document, theVerge).sortedByDescending(::score)) {
                val candidateBlocks = extractBlocks(candidate, if (theVerge) 1 else 8)
                val candidateLength = candidateBlocks.sumOf { textOf(it).length }
                if (candidateLength > textLength) {
                    blocks = candidateBlocks
                    textLength = candidateLength
                }
                if (textLength >= MIN_CONTENT_LENGTH) break
            }

            if (textLength < MIN_CONTENT_LENGTH) {
                val fallbackText = firstNonBlank(
                    document.select("meta[name=description]").attr("content"),
                    document.select("meta[property=og:description]").attr("content"),
                    document.select("meta[name=twitter:description]").attr("content")
                )
                if (!fallbackText.isNullOrBlank() && fallbackText.length >= MIN_FALLBACK_LENGTH) {
                    blocks = listOf(ArticleBlock.Paragraph(fallbackText))
                    textLength = fallbackText.length
                }
            }

            require(textLength >= MIN_CONTENT_LENGTH) { "Extracted content is too short" }

            Article(
                id = url.hashCode().toUInt().toString(16),
                sourceId = URI(url).host.orEmpty(),
                url = url,
                title = title,
                subtitle = firstNonBlank(
                    document.select("meta[name=description]").attr("content"),
                    document.select("meta[property=og:description]").attr("content"),
                    document.select("meta[name=twitter:description]").attr("content")
                ),
                author = extractAuthor(document),
                publishedAt = extractPublishedAt(document),
                heroImageUrl = firstNonBlank(
                    document.select("meta[property=og:image]").attr("content"),
                    document.select("meta[name=twitter:image]").attr("content")
                ),
                blocks = blocks.distinct()
            )
        }
    }

    private fun isTheVerge(url: String): Boolean =
        URI(url).host.orEmpty().lowercase().removePrefix("www.") == "theverge.com"

    private fun extractTheVergeBlocks(document: org.jsoup.nodes.Document): List<ArticleBlock> {
        val result = mutableListOf<ArticleBlock>()
        document.select(".duet--article--article-body-component").forEach { component ->
            extractBlocks(component, 1).forEach { if (it !in result) result += it }
        }
        return result
    }

    private fun removeNoise(document: org.jsoup.nodes.Document) {
        document.select(
            "script:not([type=application/ld+json]),style,noscript,iframe,canvas,svg,form,nav,footer,header,aside,[role=navigation],[role=banner],[role=contentinfo],.ad,.ads,.advert,.advertisement,.social,.share,.comments,.comment,.related,.recommendations,.recommended,.newsletter,.cookie,.cookies,.popup,.modal,.paywall,.login,.subscription"
        ).remove()
        document.select("[hidden],[aria-hidden=true]").remove()
    }

    private fun buildContentCandidates(document: org.jsoup.nodes.Document, theVerge: Boolean): List<Element> {
        val selectors = listOf(
            "article", "main", "[role=main]", "[itemprop=articleBody]",
            ".article-body", ".article-content", ".article__body", ".article__content",
            ".story-body", ".story-content", ".post-content", ".entry-content", ".content-body",
            ".materia-conteudo", ".materia-corpo", ".article__text", ".article-text",
            ".content", ".main-content", ".single-content", ".post-body", ".story-body-content"
        )
        val result = mutableListOf<Element>()
        selectors.forEach { selector -> document.select(selector).forEach { if (it !in result) result += it } }
        if (theVerge) document.select(".duet--article--article-body-component").forEach { if (it !in result) result += it }

        // Handle publishers with generated CSS classes by selecting text-dense containers.
        document.select("div,section").asSequence()
            .filter { it.select("p").size >= 2 && it.text().length >= 80 }
            .sortedByDescending(::score)
            .take(12)
            .forEach { if (it !in result) result += it }
        return result
    }

    private fun score(element: Element): Int =
        element.text().length + element.select("p").size * 260 + element.select("h2,h3,h4").size * 80 + element.select("img").size * 25 - element.select("a").text().length / 3

    private fun extractBlocks(root: Element, minParagraphLength: Int): List<ArticleBlock> {
        val result = mutableListOf<ArticleBlock>()
        root.select("p,h2,h3,h4,blockquote,ul,ol,figure,img").forEach { element ->
            when (element.tagName()) {
                "p" -> element.text().trim().takeIf { it.length >= minParagraphLength }?.let { result += ArticleBlock.Paragraph(it) }
                "h2", "h3", "h4" -> element.text().trim().takeIf { it.isNotBlank() }?.let { result += ArticleBlock.Heading(it, element.tagName().drop(1).toInt()) }
                "blockquote" -> element.text().trim().takeIf { it.isNotBlank() }?.let { result += ArticleBlock.Quote(it) }
                "ul", "ol" -> {
                    val items = element.children().filter { it.tagName() == "li" }.map { it.text().trim() }.filter { it.isNotBlank() }
                    if (items.isNotEmpty()) result += ArticleBlock.ListBlock(items, element.tagName() == "ol")
                }
                "figure" -> element.selectFirst("img")?.let { addImage(result, it, element.selectFirst("figcaption")?.text()) }
                "img" -> if (element.parent()?.tagName() != "figure") addImage(result, element, null)
            }
        }
        return result.distinct()
    }

    private fun addImage(result: MutableList<ArticleBlock>, image: Element, caption: String?) {
        val src = firstNonBlank(
            image.absUrl("src"), image.absUrl("data-src"), image.absUrl("data-lazy-src"),
            image.absUrl("data-original"), image.absUrl("data-image"), image.absUrl("data-lazy"), image.absUrl("data-flickity-lazyload")
        ) ?: return
        if (!src.startsWith("http://") && !src.startsWith("https://")) return
        result += ArticleBlock.Image(src, caption?.trim()?.takeIf { it.isNotBlank() }, image.attr("alt").trim().takeIf { it.isNotBlank() })
    }

    private fun extractAuthor(document: org.jsoup.nodes.Document): String? = firstNonBlank(
        document.select("meta[name=author]").attr("content"),
        document.select("meta[property=article:author]").attr("content"),
        document.select("[rel=author]").first()?.text(),
        document.select(".author,.byline,.article-author,.article__author,.autor,.materia-cabecalho__autor").first()?.text()
    )

    private fun extractPublishedAt(document: org.jsoup.nodes.Document): Instant? {
        val meta = firstNonBlank(
            document.select("meta[property=article:published_time]").attr("content"),
            document.select("meta[property=datePublished]").attr("content"),
            document.select("meta[name=date]").attr("content"),
            document.select("meta[itemprop=datePublished]").attr("content"),
            document.select("time[datetime]").first()?.attr("datetime")
        )
        parseDate(meta)?.let { return it }
        document.select("script[type=application/ld+json]").forEach { script ->
            DATE_PUBLISHED_REGEX.find(script.data())?.groupValues?.getOrNull(1)?.let { parseDate(it)?.let { date -> return date } }
        }
        return null
    }

    private fun parseDate(value: String?): Instant? = value?.trim()?.takeIf { it.isNotBlank() }?.let {
        runCatching { Instant.parse(it) }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull()
            ?: runCatching { ZonedDateTime.parse(it).toInstant() }.getOrNull()
            ?: runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }.getOrNull()
    }

    private fun textOf(block: ArticleBlock): String = when (block) {
        is ArticleBlock.Paragraph -> block.text
        is ArticleBlock.Heading -> block.text
        is ArticleBlock.Quote -> block.text
        is ArticleBlock.ListBlock -> block.items.joinToString(" ")
        is ArticleBlock.Image -> block.caption.orEmpty()
    }

    private fun firstNonBlank(vararg values: String?): String? =
        values.asSequence().mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }.firstOrNull()

    private companion object {
        const val MIN_CONTENT_LENGTH = 120
        const val MIN_FALLBACK_LENGTH = 80
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/140.0 Mobile Safari/537.36 NewsRSS/0.2"
        val DATE_PUBLISHED_REGEX = Regex("\\\"datePublished\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
    }
}
