package com.abelcrvg.newsrss.data.extraction

import com.abelcrvg.newsrss.NewsRssApplication
import com.abelcrvg.newsrss.core.extraction.ArticleExtractor
import com.abelcrvg.newsrss.core.model.Article
import com.abelcrvg.newsrss.core.model.ArticleBlock
import com.abelcrvg.newsrss.data.translation.OnDeviceTranslator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Generic reader-mode extractor. Public article text is translated to Portuguese on-device. */
class JsoupArticleExtractor(private val timeoutMillis: Int = 20_000) : ArticleExtractor {
    override suspend fun extract(url: String): Result<Article> = withContext(Dispatchers.IO) {
        runCatching {
            require(url.startsWith("http://") || url.startsWith("https://"))
            val document = Jsoup.connect(url).userAgent(USER_AGENT).timeout(timeoutMillis).followRedirects(true)
                .referrer("https://www.google.com/")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                .header("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.8").get()
            removeNoise(document)
            val title = firstNonBlank(
                document.select("meta[property=og:title]").attr("content"),
                document.select("meta[name=twitter:title]").attr("content"),
                document.select("h1").first()?.text(),
                document.title()
            ) ?: error("Article title not found")
            val root = findContentRoot(document) ?: error("Article content not found")
            var blocks = extractBlocks(root)
            var textLength = blocks.joinToString(" ") { textOf(it) }.length
            if (textLength < MIN_CONTENT_LENGTH) {
                val alternatives = document.select("article,main,[role=main],.article,.article-body,.article-content,.article__body,.story-body,.story-content,.post-content,.entry-content,.content-body,.materia-conteudo").distinct()
                for (candidate in alternatives.sortedByDescending(::score)) {
                    val candidateBlocks = extractBlocks(candidate)
                    val candidateLength = candidateBlocks.joinToString(" ") { textOf(it) }.length
                    if (candidateLength > textLength) {
                        blocks = candidateBlocks
                        textLength = candidateLength
                    }
                    if (textLength >= MIN_CONTENT_LENGTH) break
                }
            }
            require(textLength >= MIN_CONTENT_LENGTH) { "Extracted content is too short" }

            val article = Article(
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
                blocks = blocks
            )

            // Translation is intentionally performed only when an article is opened.
            // This keeps feed refreshes fast and avoids unnecessary model downloads.
            OnDeviceTranslator(NewsRssApplication.appContext).translateArticle(article)
        }
    }

    private fun removeNoise(document: org.jsoup.nodes.Document) {
        document.select("script:not([type=application/ld+json]),style,noscript,iframe,canvas,svg,form,nav,footer,header,aside,[role=navigation],[role=banner],[role=contentinfo],.ad,.ads,.advert,.advertisement,.social,.share,.comments,.comment,.related,.recommendations,.recommended,.newsletter,.cookie,.cookies,.popup,.modal,.paywall,.login,.subscription").remove()
        document.select("[hidden],[aria-hidden=true]").remove()
    }

    private fun findContentRoot(document: org.jsoup.nodes.Document): Element? {
        document.select("article").maxByOrNull(::score)?.let { return it }
        document.select("main,[role=main],.article-body,.article-content,.article__body,.story-body,.story-content,.post-content,.entry-content,.content-body,.materia-conteudo").maxByOrNull(::score)?.let { return it }
        return document.body().select("div,section").maxByOrNull(::score)
    }

    private fun score(element: Element): Int =
        element.text().length + element.select("p").size * 260 + element.select("h2,h3,h4").size * 80 + element.select("img").size * 25 - element.select("a").text().length / 3

    private fun extractBlocks(root: Element): List<ArticleBlock> {
        val result = mutableListOf<ArticleBlock>()
        root.select("p,h2,h3,h4,blockquote,ul,ol,figure,img").forEach { element ->
            when (element.tagName()) {
                "p" -> element.text().trim().takeIf { it.length >= 20 }?.let { result += ArticleBlock.Paragraph(it) }
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
            image.absUrl("data-original"), image.absUrl("data-image"), image.absUrl("data-lazy"),
            image.absUrl("data-flickity-lazyload")
        ) ?: return
        if (!src.startsWith("http://") && !src.startsWith("https://")) return
        result += ArticleBlock.Image(
            src,
            caption?.trim()?.takeIf { it.isNotBlank() },
            image.attr("alt").trim().takeIf { it.isNotBlank() }
        )
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
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/140.0 Mobile Safari/537.36 NewsRSS/0.2"
        val DATE_PUBLISHED_REGEX = Regex("\\\"datePublished\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
    }
}
