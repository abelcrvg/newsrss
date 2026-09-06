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
            val ge = isGe(url)
            val g1 = isG1(url)
            removeNoise(document)
            val title = firstNonBlank(document.select("meta[property=og:title]").attr("content"), document.select("meta[name=twitter:title]").attr("content"), document.select("h1").first()?.text(), document.title()) ?: error("Article title not found")
            val subtitle = extractSubtitle(document, title)
            var blocks = if (theVerge) extractTheVergeBlocks(document, ge) else emptyList()
            var textLength = blocks.sumOf { textOf(it).length }
            val candidates = buildContentCandidates(document, theVerge, g1).sortedByDescending(::score)
            for (candidate in candidates) {
                val candidateBlocks = extractBlocks(candidate, if (theVerge) 1 else 8, ge)
                val candidateLength = candidateBlocks.sumOf { textOf(it).length }
                if (candidateLength > textLength) {
                    blocks = candidateBlocks
                    textLength = candidateLength
                }
            }
            blocks = removeDuplicateLead(blocks, title, subtitle)
            textLength = blocks.sumOf { textOf(it).length }
            if (textLength < MIN_CONTENT_LENGTH) {
                val fallbackText = subtitle
                if (!fallbackText.isNullOrBlank() && fallbackText.length >= MIN_FALLBACK_LENGTH) {
                    blocks = listOf(ArticleBlock.Paragraph(fallbackText))
                    textLength = fallbackText.length
                }
            }
            require(textLength >= MIN_CONTENT_LENGTH) { "Extracted content is too short" }
            Article(
                id = url.hashCode().toUInt().toString(16), sourceId = URI(url).host.orEmpty(), url = url, title = title,
                subtitle = subtitle,
                author = extractAuthor(document), publishedAt = extractPublishedAt(document),
                heroImageUrl = firstNonBlank(document.select("meta[property=og:image]").attr("content"), document.select("meta[name=twitter:image]").attr("content")),
                blocks = blocks.distinct()
            )
        }
    }

    private fun isTheVerge(url: String): Boolean = URI(url).host.orEmpty().lowercase().removePrefix("www.") == "theverge.com"
    private fun isGe(url: String): Boolean = URI(url).host.orEmpty().lowercase().removePrefix("www.") == "ge.globo.com"
    private fun isG1(url: String): Boolean = URI(url).host.orEmpty().lowercase().removePrefix("www.").endsWith("g1.globo.com")

    private fun extractTheVergeBlocks(document: org.jsoup.nodes.Document, ge: Boolean): List<ArticleBlock> {
        val result = mutableListOf<ArticleBlock>()
        document.select(".duet--article--article-body-component").forEach { component -> extractBlocks(component, 1, ge).forEach { if (it !in result) result.add(it) } }
        return result
    }

    private fun removeNoise(document: org.jsoup.nodes.Document) {
        document.select("script:not([type=application/ld+json]),style,noscript,iframe,canvas,svg,form,nav,footer,header,aside,[role=navigation],[role=banner],[role=contentinfo],.ad,.ads,.advert,.advertisement,.social,.share,.comments,.comment,.related,.recommendations,.recommended,.newsletter,.cookie,.cookies,.popup,.modal,.paywall,.login,.subscription").remove()
        document.select("[hidden],[aria-hidden=true]").remove()
    }

    private fun buildContentCandidates(document: org.jsoup.nodes.Document, theVerge: Boolean, g1: Boolean): List<Element> {
        val selectors = buildList {
            addAll(listOf("article", "main", "[role=main]", "[itemprop=articleBody]", ".article-body", ".article-content", ".article__body", ".article__content", ".story-body", ".story-content", ".post-content", ".entry-content", ".content-body", ".materia-conteudo", ".materia-corpo", ".article__text", ".article-text", ".content", ".main-content", ".single-content", ".post-body", ".story-body-content"))
            if (g1) addAll(listOf(".materia-conteudo", ".materia-conteudo__texto", ".materia-corpo", ".materia-corpo__texto", ".article-body", ".article-content", "[data-testid*=article]", "[data-testid*=content]"))
        }
        val result = mutableListOf<Element>()
        selectors.forEach { selector -> document.select(selector).forEach { if (it !in result) result.add(it) } }
        if (theVerge) document.select(".duet--article--article-body-component").forEach { if (it !in result) result.add(it) }
        document.select("div,section").asSequence().filter { it.select("p").size >= 2 && it.text().length >= 120 }.sortedByDescending(::score).forEach { if (it !in result) result.add(it) }
        return result
    }

    private fun score(element: Element): Int = element.text().length + element.select("p").size * 260 + element.select("h2,h3,h4").size * 80 + element.select("img").size * 25 - element.select("a").text().length / 3

    private fun extractBlocks(root: Element, minParagraphLength: Int, ge: Boolean): List<ArticleBlock> {
        val result = mutableListOf<ArticleBlock>()
        root.select("p,h2,h3,h4,blockquote,ul,ol,figure,img").forEach { element ->
            when (element.tagName()) {
                "p" -> element.text().trim().takeIf { it.length >= minParagraphLength }?.let { result.add(ArticleBlock.Paragraph(it, sanitizeInlineHtml(element))) }
                "h2", "h3", "h4" -> element.text().trim().takeIf { it.isNotBlank() }?.let { result.add(ArticleBlock.Heading(it, element.tagName().drop(1).toInt())) }
                "blockquote" -> element.text().trim().takeIf { it.isNotBlank() }?.let { result.add(ArticleBlock.Quote(it)) }
                "ul", "ol" -> { val items = element.children().filter { it.tagName() == "li" }.map { it.text().trim() }.filter { it.isNotBlank() }; if (items.isNotEmpty()) result.add(ArticleBlock.ListBlock(items, element.tagName() == "ol")) }
                "figure" -> element.selectFirst("img")?.let { image -> if (!isNoiseImage(image, ge)) addImage(result, image, element.selectFirst("figcaption")?.text()) }
                "img" -> if (element.parent()?.tagName() != "figure" && !isNoiseImage(element, ge)) addImage(result, element, null)
            }
        }
        return result.distinct()
    }

    private fun extractSubtitle(document: org.jsoup.nodes.Document, title: String): String? {
        val values = listOf(
            document.select("meta[name=description]").attr("content"),
            document.select("meta[property=og:description]").attr("content"),
            document.select("meta[name=twitter:description]").attr("content")
        ).map { it.replace(Regex("\\s+"), " ").trim() }.filter { it.isNotBlank() && !sameText(it, title) }
        return values.firstOrNull()
    }

    private fun removeDuplicateLead(blocks: List<ArticleBlock>, title: String, subtitle: String?): List<ArticleBlock> {
        if (subtitle.isNullOrBlank()) return blocks
        var removed = false
        return blocks.filter { block ->
            if (removed || block !is ArticleBlock.Paragraph) return@filter true
            val paragraph = block.text.text
            if (sameText(paragraph, subtitle) || isSubtitlePrefix(paragraph, subtitle)) {
                removed = true
                false
            } else true
        }.filterNot { block -> block is ArticleBlock.Paragraph && sameText(block.text.text, title) }
    }

    private fun isSubtitlePrefix(paragraph: String, subtitle: String): Boolean {
        val p = normalizeText(paragraph)
        val s = normalizeText(subtitle)
        return s.length >= 40 && p.length > s.length && p.startsWith(s) && p.substring(s.length).trim().length < 120
    }

    private fun sameText(a: String, b: String): Boolean = normalizeText(a) == normalizeText(b)

    private fun normalizeText(value: String): String = value.lowercase().replace(Regex("\\s+"), " ").trim().removeSuffix(".")

    /** Preserve only safe inline editorial markup and color/font-weight declarations. */
    private fun sanitizeInlineHtml(element: Element): String? {
        val copy = element.clone()
        copy.select("script,style,iframe,svg,img,video,audio,object,embed").remove()
        copy.select("*").forEach { node ->
            val keep = node.attributes().asList().filter { attribute ->
                when {
                    attribute.key == "href" && node.tagName() == "a" -> true
                    attribute.key == "title" -> true
                    attribute.key == "style" -> true
                    else -> false
                }
            }
            node.clearAttributes()
            keep.forEach { attribute ->
                if (attribute.key == "style") {
                    sanitizeStyle(attribute.value).takeIf { it.isNotBlank() }?.let { node.attr("style", it) }
                } else node.attr(attribute.key, attribute.value)
            }
        }
        val html = copy.html().trim()
        return html.takeIf { it.isNotBlank() && it != copy.text() }
    }

    private fun sanitizeStyle(style: String): String = style.split(';').mapNotNull { declaration ->
        val parts = declaration.split(':', limit = 2)
        if (parts.size != 2) return@mapNotNull null
        val property = parts[0].trim().lowercase()
        val value = parts[1].trim()
        if (property !in setOf("color", "font-weight")) return@mapNotNull null
        if (value.isBlank() || value.length > 80 || value.contains('{') || value.contains('}') || value.contains(';')) return@mapNotNull null
        "$property:$value"
    }.joinToString(";")

    /** GE pages contain team-lineup widgets with many club badges. */
    private fun isNoiseImage(image: Element, ge: Boolean): Boolean {
        if (!ge) return false
        val attributes = buildString { append(image.className()).append(' ').append(image.id()).append(' ').append(image.attr("alt")).append(' ').append(image.attr("title")).append(' ').append(image.attr("src")).append(' '); image.parents().take(5).forEach { append(it.className()).append(' ').append(it.id()).append(' ') } }.lowercase()
        return listOf("escudo", "escudos", "badge", "club-logo", "club_logo", "team-logo", "team_logo", "team-badge", "team_badge", "crest", "club-badge", "club_badge", "logo-time", "logo time", "abreviacao", "abreviação", "brasao", "brasão", "shield").any(attributes::contains)
    }

    private fun addImage(result: MutableList<ArticleBlock>, image: Element, caption: String?) {
        val src = firstNonBlank(image.absUrl("src"), image.absUrl("data-src"), image.absUrl("data-lazy-src"), image.absUrl("data-original"), image.absUrl("data-image"), image.absUrl("data-lazy"), image.absUrl("data-flickity-lazyload")) ?: return
        if (!src.startsWith("http://") && !src.startsWith("https://")) return
        result.add(ArticleBlock.Image(src, caption?.trim()?.takeIf { it.isNotBlank() }, image.attr("alt").trim().takeIf { it.isNotBlank() }))
    }

    private fun extractAuthor(document: org.jsoup.nodes.Document): String? = firstNonBlank(document.select("meta[name=author]").attr("content"), document.select("meta[property=article:author]").attr("content"), document.select("[rel=author]").first()?.text(), document.select(".author,.byline,.article-author,.article__author,.autor,.materia-cabecalho__autor").first()?.text())

    private fun extractPublishedAt(document: org.jsoup.nodes.Document): Instant? {
        val meta = firstNonBlank(document.select("meta[property=article:published_time]").attr("content"), document.select("meta[property=datePublished]").attr("content"), document.select("meta[name=date]").attr("content"), document.select("meta[itemprop=datePublished]").attr("content"), document.select("time[datetime]").first()?.attr("datetime"))
        parseDate(meta)?.let { return it }
        document.select("script[type=application/ld+json]").forEach { script -> DATE_PUBLISHED_REGEX.find(script.data())?.groupValues?.getOrNull(1)?.let { parseDate(it)?.let { date -> return date } } }
        return null
    }

    private fun parseDate(value: String?): Instant? = value?.trim()?.takeIf { it.isNotBlank() }?.let { runCatching { Instant.parse(it) }.getOrNull() ?: runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() ?: runCatching { ZonedDateTime.parse(it).toInstant() }.getOrNull() ?: runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }.getOrNull() }

    private fun textOf(block: ArticleBlock): String = when (block) {
        is ArticleBlock.Paragraph -> block.text.text
        is ArticleBlock.Heading -> block.text
        is ArticleBlock.Quote -> block.text
        is ArticleBlock.ListBlock -> block.items.joinToString(" ")
        is ArticleBlock.Image -> block.caption.orEmpty()
    }

    private fun firstNonBlank(vararg values: String?): String? = values.asSequence().mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }.firstOrNull()

    private companion object {
        const val MIN_CONTENT_LENGTH = 300
        const val MIN_FALLBACK_LENGTH = 120
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/140.0 Mobile Safari/537.36 NewsRSS/0.3"
        val DATE_PUBLISHED_REGEX = Regex("\\\"datePublished\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
    }
}
