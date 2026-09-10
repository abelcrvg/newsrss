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

/** Generic reader-mode extractor with source-specific boundaries and JSON-LD recovery. */
class JsoupArticleExtractor(private val timeoutMillis: Int = 20_000) : ArticleExtractor {
    override suspend fun extract(url: String): Result<Article> = withContext(Dispatchers.IO) {
        runCatching {
            require(url.startsWith("http://") || url.startsWith("https://"))
            val document = Jsoup.connect(url).userAgent(USER_AGENT).timeout(timeoutMillis).followRedirects(true)
                .referrer("https://www.google.com/")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9,pt-BR;q=0.7,pt;q=0.6").get()
            val theVerge = isTheVerge(url); val ge = isGe(url); val g1 = isG1(url)
            removeNoise(document)
            val title = firstNonBlank(document.select("meta[property=og:title]").attr("content"), document.select("meta[name=twitter:title]").attr("content"), document.select("h1").first()?.text(), document.title()) ?: error("Article title not found")
            val subtitle = extractSubtitle(document, title)

            var blocks: List<ArticleBlock> = if (theVerge) {
                extractTheVergeBlocks(document).ifEmpty { bestGenericBlocks(document, false, false) }
            } else {
                bestGenericBlocks(document, false, g1)
            }

            var textLength = blocks.sumOf { textOf(it).length }
            if (textLength < MIN_CONTENT_LENGTH) {
                val jsonBlocks = extractJsonLdArticleBody(document, ge)
                val jsonLength = jsonBlocks.sumOf { textOf(it).length }
                if (jsonLength > textLength) { blocks = jsonBlocks; textLength = jsonLength }
            }
            blocks = removeDuplicateLead(blocks, title, subtitle)
            blocks = removeDuplicateContent(blocks)
            textLength = blocks.sumOf { textOf(it).length }
            if (textLength < MIN_CONTENT_LENGTH) {
                val fallback = listOfNotNull(subtitle?.takeIf { it.length >= MIN_FALLBACK_LENGTH }?.let { ArticleBlock.Paragraph(it) })
                if (fallback.sumOf { textOf(it).length } > textLength) { blocks = fallback; textLength = fallback.sumOf { textOf(it).length } }
            }
            require(textLength >= MIN_CONTENT_LENGTH) { "Extracted content is too short" }
            Article(
                id = url.hashCode().toUInt().toString(16), sourceId = URI(url).host.orEmpty(), url = url, title = title,
                subtitle = subtitle, author = extractAuthor(document), publishedAt = extractPublishedAt(document),
                heroImageUrl = extractHeroImage(document), blocks = blocks.distinct()
            )
        }
    }

    private fun bestGenericBlocks(document: org.jsoup.nodes.Document, theVerge: Boolean, g1: Boolean): List<ArticleBlock> =
        buildContentCandidates(document, theVerge, g1).sortedByDescending(::score)
            .fold(emptyList()) { best, candidate ->
                val candidateBlocks = extractBlocks(candidate, 3, isGeHost(document.baseUri()))
                if (candidateBlocks.sumOf { textOf(it).length } > best.sumOf { textOf(it).length }) candidateBlocks else best
            }

    private fun isTheVerge(url: String) = URI(url).host.orEmpty().lowercase().removePrefix("www.") == "theverge.com"
    private fun isGe(url: String) = URI(url).host.orEmpty().lowercase().removePrefix("www.") == "ge.globo.com"
    private fun isGeHost(url: String?) = url?.let { runCatching { isGe(it) }.getOrDefault(false) } ?: false
    private fun isG1(url: String) = URI(url).host.orEmpty().lowercase().removePrefix("www.").endsWith("g1.globo.com")

    private fun extractTheVergeBlocks(document: org.jsoup.nodes.Document): List<ArticleBlock> = buildList {
        document.select(".duet--article--article-body-component,.duet--article--article-body,[data-testid=article-body],[data-testid*=article-body]").forEach { component ->
            extractTheVergeComponent(component).forEach { block -> if (block !in this) add(block) }
        }
    }

    private fun extractTheVergeComponent(component: Element): List<ArticleBlock> {
        val result = mutableListOf<ArticleBlock>()
        component.select("p,h2,h3,h4,blockquote,ul,ol,figure").forEach { element ->
            when (element.tagName()) {
                "p" -> element.text().trim().takeIf { it.length >= 1 }?.let { result.add(ArticleBlock.Paragraph(it, sanitizeInlineHtml(element))) }
                "h2","h3","h4" -> element.text().trim().takeIf(String::isNotBlank)?.let { result.add(ArticleBlock.Heading(it, element.tagName().drop(1).toInt())) }
                "blockquote" -> element.text().trim().takeIf(String::isNotBlank)?.let { result.add(ArticleBlock.Quote(it)) }
                "ul","ol" -> {
                    val items = element.children().filter { it.tagName() == "li" }.map { it.text().trim() }.filter(String::isNotBlank)
                    if (items.isNotEmpty()) result.add(ArticleBlock.ListBlock(items, element.tagName() == "ol"))
                }
                "figure" -> element.selectFirst("img")?.let { image -> if (isTheVergeContentFigure(element)) addImage(result, image, element.selectFirst("figcaption")?.text()) }
            }
        }
        return result.distinct()
    }

    private fun isTheVergeContentFigure(figure: Element): Boolean {
        val classes = buildString { append(figure.className()).append(' ').append(figure.id()).append(' '); figure.parents().take(4).forEach { append(it.className()).append(' ').append(it.id()).append(' ') } }.lowercase()
        val noise = listOf("related", "recommend", "newsletter", "promo", "advert", "ad-", "social", "share", "author", "avatar", "logo", "header", "footer", "sidebar", "commerce", "product-card")
        return noise.none(classes::contains)
    }

    private fun removeNoise(document: org.jsoup.nodes.Document) {
        document.select("script:not([type=application/ld+json]),style,noscript,iframe,canvas,svg,form,nav,footer,header,aside,[role=navigation],[role=banner],[role=contentinfo],.ad,.ads,.advert,.advertisement,.social,.share,.comments,.comment,.related,.recommendations,.recommended,.newsletter,.cookie,.cookies,.popup,.modal,.paywall,.login,.subscription").remove()
        document.select("[hidden],[aria-hidden=true]").remove()
    }

    private fun buildContentCandidates(document: org.jsoup.nodes.Document, theVerge: Boolean, g1: Boolean): List<Element> {
        val selectors = mutableListOf("article","main","[role=main]","[itemprop=articleBody]",".article-body",".article-content",".article__body",".article__content",".story-body",".story-content",".post-content",".entry-content",".content-body",".materia-conteudo",".materia-corpo",".article__text",".article-text",".content",".main-content",".single-content",".post-body",".story-body-content",".c-entry-content",".article-body__content","[class*=ArticleBody]","[class*=articleBody]","[data-testid*=article-content]")
        if (g1) selectors += listOf(".materia-conteudo__texto",".materia-corpo__texto",".article-body",".article-content",".mc-article-body",".mc-article-body-content","[data-testid*=article-body]","[data-testid*=article-content]","[class*=article-body]","[class*=article-content]","[class*=materia-conteudo]")
        val result = mutableListOf<Element>()
        selectors.forEach { selector -> document.select(selector).forEach { if (it !in result) result.add(it) } }
        if (theVerge) document.select(".duet--article--article-body-component,.duet--article--article-body").forEach { if (it !in result) result.add(it) }
        document.select("div,section").asSequence().filter { it.select("p").size >= 2 && it.text().length >= 120 }.sortedByDescending(::score).forEach { if (it !in result) result.add(it) }
        return result
    }

    private fun score(element: Element): Int = element.text().length + element.select("p").size * 260 + element.select("h2,h3,h4").size * 80 + element.select("img").size * 25 - element.select("a").text().length / 3

    private fun extractBlocks(root: Element, minParagraphLength: Int, ge: Boolean): List<ArticleBlock> {
        val cleanRoot = root.clone(); removeNoiseFromRoot(cleanRoot); val result = mutableListOf<ArticleBlock>()
        cleanRoot.select("p,h2,h3,h4,blockquote,ul,ol,figure,img").forEach { element ->
            when (element.tagName()) {
                "p" -> element.text().trim().takeIf { it.length >= minParagraphLength }?.let { result.add(ArticleBlock.Paragraph(it, sanitizeInlineHtml(element))) }
                "h2","h3","h4" -> element.text().trim().takeIf(String::isNotBlank)?.let { result.add(ArticleBlock.Heading(it, element.tagName().drop(1).toInt())) }
                "blockquote" -> element.text().trim().takeIf(String::isNotBlank)?.let { result.add(ArticleBlock.Quote(it)) }
                "ul","ol" -> { val items = element.children().filter { it.tagName() == "li" }.map { it.text().trim() }.filter(String::isNotBlank); if (items.isNotEmpty()) result.add(ArticleBlock.ListBlock(items, element.tagName() == "ol")) }
                "figure" -> element.selectFirst("img")?.let { image -> if (!isNoiseImage(image, ge)) addImage(result, image, element.selectFirst("figcaption")?.text()) }
                "img" -> if (element.parent()?.tagName() != "figure" && !isNoiseImage(element, ge)) addImage(result, element, null)
            }
        }
        return result.distinct()
    }

    private fun removeNoiseFromRoot(root: Element) {
        root.select("script,style,noscript,iframe,canvas,svg,form,nav,footer,header,aside,[role=navigation],[role=banner],[role=contentinfo],.ad,.ads,.advert,.advertisement,.social,.share,.comments,.comment,.related,.recommendations,.recommended,.newsletter,.cookie,.cookies,.popup,.modal,.paywall,.login,.subscription").remove()
        root.select("a[href*='facebook.com'],a[href*='instagram.com'],a[href*='youtube.com'],a[href*='whatsapp'],a[href*='twitter.com'],a[href*='x.com'],a[href*='threads.net'],a[href*='tiktok.com'],a[href*='news.google.com'],a[href*='maps.google.com']").remove()
    }

    private fun extractJsonLdArticleBody(document: org.jsoup.nodes.Document, ge: Boolean): List<ArticleBlock> {
        val result = mutableListOf<ArticleBlock>()
        document.select("script[type=application/ld+json]").forEach { script ->
            val raw = script.data(); val match = Regex("""[\"']articleBody[\"']\s*:\s*[\"']((?:\\.|[^\"'])*)[\"']""", RegexOption.DOT_MATCHES_ALL).find(raw) ?: return@forEach
            val body = match.groupValues[1].replace("\\n", "\n").replace("\\r", "\r").replace("\\\"", "\"").replace("\\/", "/")
            body.split(Regex("\\n{2,}")).map { it.replace(Regex("\\s+"), " ").trim() }.filter { it.length >= 3 }.forEach { result.add(ArticleBlock.Paragraph(it)) }
        }
        return result
    }

    private fun removeDuplicateContent(blocks: List<ArticleBlock>): List<ArticleBlock> {
        val seen = mutableListOf<String>(); val result = mutableListOf<ArticleBlock>()
        fun unique(value: String): Boolean { val key = duplicateKey(value); if (key.isBlank()) return false; if (seen.any { isNearDuplicate(key, it) }) return false; seen += key; return true }
        blocks.forEach { block -> when (block) {
            is ArticleBlock.ListBlock -> { val items = block.items.filter(::unique); if (items.isNotEmpty()) result.add(block.copy(items = items)) }
            is ArticleBlock.Paragraph -> if (unique(block.text.text)) result.add(block)
            is ArticleBlock.Heading -> if (unique(block.text)) result.add(block)
            is ArticleBlock.Quote -> if (unique(block.text)) result.add(block)
            is ArticleBlock.Image -> { val key = "image:${block.url}"; if (seen.add(key)) result.add(block) }
        } }
        return result
    }
    private fun duplicateKey(value: String) = value.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    private fun isNearDuplicate(a: String, b: String): Boolean { if (a == b) return true; val shorter = minOf(a.length, b.length); val longer = maxOf(a.length, b.length); if (shorter < 70) return false; if ((a.contains(b) || b.contains(a)) && shorter.toDouble() / longer >= .72) return true; val at = a.split(" ").filter { it.length >= 3 }.toSet(); val bt = b.split(" ").filter { it.length >= 3 }.toSet(); return at.size >= 8 && bt.size >= 8 && at.intersect(bt).size.toDouble() / minOf(at.size, bt.size) >= .88 }

    private fun extractSubtitle(document: org.jsoup.nodes.Document, title: String): String? = listOf(document.select("meta[name=description]").attr("content"), document.select("meta[property=og:description]").attr("content"), document.select("meta[name=twitter:description]").attr("content")).map { it.replace(Regex("\\s+"), " ").trim() }.firstOrNull { it.isNotBlank() && !sameText(it, title) }
    private fun removeDuplicateLead(blocks: List<ArticleBlock>, title: String, subtitle: String?): List<ArticleBlock> { var removed = false; return blocks.filter { block -> if (block !is ArticleBlock.Paragraph || removed) return@filter true; val text = block.text.text; if (!subtitle.isNullOrBlank() && (sameText(text, subtitle) || isSubtitlePrefix(text, subtitle))) { removed = true; false } else true }.filterNot { it is ArticleBlock.Paragraph && sameText(it.text.text, title) } }
    private fun isSubtitlePrefix(paragraph: String, subtitle: String): Boolean { val p = normalizeText(paragraph); val s = normalizeText(subtitle); return s.length >= 40 && p.length > s.length && p.startsWith(s) && p.substring(s.length).trim().length < 120 }
    private fun sameText(a: String, b: String) = normalizeText(a) == normalizeText(b)
    private fun normalizeText(value: String) = value.lowercase().replace(Regex("\\s+"), " ").trim().removeSuffix(".")

    private fun sanitizeInlineHtml(element: Element): String? { val copy = element.clone(); copy.select("script,style,iframe,svg,img,video,audio,object,embed").remove(); copy.select("*").forEach { node -> val attrs = node.attributes().asList().filter { (it.key == "href" && node.tagName() == "a") || it.key == "title" || it.key == "style" }; node.clearAttributes(); attrs.forEach { if (it.key == "style") sanitizeStyle(it.value).takeIf(String::isNotBlank)?.let { v -> node.attr("style", v) } else node.attr(it.key, it.value) } }; return copy.html().trim().takeIf { it.isNotBlank() && it != copy.text() } }
    private fun sanitizeStyle(style: String) = style.split(';').mapNotNull { declaration -> val p = declaration.split(':', limit = 2); if (p.size != 2) null else { val property = p[0].trim().lowercase(); val value = p[1].trim(); if (property in setOf("color","font-weight") && value.isNotBlank() && value.length <= 80 && !value.contains('{') && !value.contains('}')) "$property:$value" else null } }.joinToString(";")

    private fun isNoiseImage(image: Element, ge: Boolean): Boolean { val attributes = buildString { append(image.className()).append(' ').append(image.id()).append(' ').append(image.attr("alt")).append(' ').append(image.attr("title")).append(' ').append(image.attr("src")).append(' '); image.parents().take(6).forEach { append(it.className()).append(' ').append(it.id()).append(' ').append(it.attr("aria-label")).append(' ') } }.lowercase(); val url = listOf("src", "data-src", "data-lazy-src", "data-original", "data-image", "data-image-url", "data-url", "data-thumb").asSequence().map { image.attr(it) }.firstOrNull { it.isNotBlank() }.orEmpty().lowercase(); val socialNoise = listOf("whatsapp", "facebook", "instagram", "youtube", "twitter", "x.com", "threads", "tiktok", "google-news", "google news", "google-maps", "google maps", "social", "share", "compartilhar", "redes-sociais", "redes sociais"); val genericNoise = listOf("logo", "avatar", "icon", "favicon", "sprite", "placeholder", "tracking", "pixel", "1x1", "author", "profile", "badge"); val footballNoise = listOf("escudo","escudos","club-logo","club_logo","team-logo","team_logo","team-badge","team_badge","crest","club-badge","club_badge","logo-time","logo time","abreviacao","abreviação","brasao","brasão","shield"); return socialNoise.any { it in attributes || it in url } || genericNoise.any { it in attributes || it in url } || (ge && footballNoise.any { it in attributes }) }
    private fun addImage(result: MutableList<ArticleBlock>, image: Element, caption: String?) { val src = firstNonBlank(image.absUrl("src"), image.absUrl("data-src"), image.absUrl("data-lazy-src"), image.absUrl("data-original"), image.absUrl("data-image"), image.absUrl("data-lazy"), image.absUrl("data-flickity-lazyload"), image.absUrl("data-original-src"), image.absUrl("data-image-url"), image.absUrl("data-url"), image.absUrl("data-thumb")) ?: image.attr("srcset").split(',').firstOrNull()?.trim()?.split(Regex("\\s+"))?.firstOrNull(); if (src.isNullOrBlank() || (!src.startsWith("http://") && !src.startsWith("https://"))) return; val cleanCaption = caption?.trim()?.takeIf(String::isNotBlank)?.takeUnless(::isAccessibilityImageText); val cleanAlt = image.attr("alt").trim().takeIf(String::isNotBlank)?.takeUnless(::isAccessibilityImageText); result.add(ArticleBlock.Image(src, cleanCaption, cleanAlt)) }
    private fun isAccessibilityImageText(value: String): Boolean { val normalized = value.trim().lowercase(); return normalized.startsWith("imagem que representa a matéria") || normalized.startsWith("imagem que representa a materia") || normalized.startsWith("imagem que representa a notícia") || normalized.startsWith("imagem que representa a noticia") || normalized.startsWith("imagem representando a matéria") || normalized.startsWith("imagem representando a materia") || normalized.startsWith("imagem representando a notícia") || normalized.startsWith("imagem representando a noticia") || normalized.startsWith("image representing the news") || normalized.startsWith("image representing this news") || normalized == "imagem da notícia" || normalized == "imagem da noticia" }
    private fun extractHeroImage(document: org.jsoup.nodes.Document): String? = firstNonBlank(document.select("meta[property=og:image]").attr("content"), document.select("meta[property=og:image:url]").attr("content"), document.select("meta[name=twitter:image]").attr("content"), document.select("meta[name=twitter:image:src]").attr("content"))?.let { normalizeUrl(it, document.baseUri()) }
    private fun extractAuthor(document: org.jsoup.nodes.Document): String? = firstNonBlank(document.select("meta[name=author]").attr("content"), document.select("meta[property=article:author]").attr("content"), document.select("[rel=author]").first()?.text(), document.select(".author,.byline,.article-author,.article__author,.autor,.materia-cabecalho__autor").first()?.text())
    private fun extractPublishedAt(document: org.jsoup.nodes.Document): Instant? { val values = listOf(document.select("meta[property=article:published_time]").attr("content"), document.select("meta[property=datePublished]").attr("content"), document.select("meta[name=date]").attr("content"), document.select("meta[itemprop=datePublished]").attr("content"), document.select("time[datetime]").first()?.attr("datetime")); values.asSequence().mapNotNull(::parseDate).firstOrNull()?.let { return it }; return document.select("script[type=application/ld+json]").asSequence().flatMap { DATE_PUBLISHED_REGEX.findAll(it.data()).asSequence() }.mapNotNull { parseDate(it.groupValues[1]) }.firstOrNull() }
    private fun parseDate(value: String?): Instant? = value?.trim()?.takeIf(String::isNotBlank)?.let { runCatching { Instant.parse(it) }.getOrNull() ?: runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() ?: runCatching { ZonedDateTime.parse(it).toInstant() }.getOrNull() ?: runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }.getOrNull() }
    private fun normalizeUrl(value: String, base: String): String { val v = value.trim(); if (v.startsWith("//")) return "https:$v"; return runCatching { URI(base).resolve(v).toString() }.getOrElse { v } }
    private fun textOf(block: ArticleBlock): String = when (block) { is ArticleBlock.Paragraph -> block.text.text; is ArticleBlock.Heading -> block.text; is ArticleBlock.Quote -> block.text; is ArticleBlock.ListBlock -> block.items.joinToString(" "); is ArticleBlock.Image -> block.caption.orEmpty() }
    private fun firstNonBlank(vararg values: String?) = values.asSequence().mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }.firstOrNull()

    private companion object { const val MIN_CONTENT_LENGTH = 120; const val MIN_FALLBACK_LENGTH = 60; const val USER_AGENT = "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/140.0 Mobile Safari/537.36 NewsRSS/0.4"; val DATE_PUBLISHED_REGEX = Regex("\\\"datePublished\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"") }
}
