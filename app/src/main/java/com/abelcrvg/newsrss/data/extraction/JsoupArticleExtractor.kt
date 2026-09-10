package com.abelcrvg.newsrss.data.extraction

import com.abelcrvg.newsrss.core.extraction.ArticleExtractor
import com.abelcrvg.newsrss.core.model.Article
import com.abelcrvg.newsrss.core.model.ArticleBlock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class JsoupArticleExtractor(private val timeoutMillis: Int = 20_000) : ArticleExtractor {
    override suspend fun extract(url: String): Result<Article> = withContext(Dispatchers.IO) {
        runCatching {
            require(url.startsWith("http://") || url.startsWith("https://"))
            val document = Jsoup.connect(url).userAgent(USER_AGENT).timeout(timeoutMillis).followRedirects(true).referrer("https://www.google.com/")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9,pt-BR;q=0.7,pt;q=0.6").get()
            val host = URI(url).host.orEmpty().lowercase().removePrefix("www.")
            removeNoise(document)
            val title = firstNonBlank(document.select("meta[property=og:title]").attr("content"), document.select("meta[name=twitter:title]").attr("content"), document.selectFirst("h1")?.text(), document.title()) ?: error("Article title not found")
            val subtitle = firstNonBlank(document.select("meta[property=og:description]").attr("content"), document.select("meta[name=description]").attr("content"), document.select("meta[name=twitter:description]").attr("content"))?.takeIf { !sameText(it, title) }
            var blocks = contentCandidates(document).map { extractBlocks(it) }.maxByOrNull(::contentScore).orEmpty()
            val jsonLd = extractJsonLdBody(document)
            if (contentScore(jsonLd) > contentScore(blocks)) blocks = jsonLd
            blocks = dedupe(blocks).filterNot { it is ArticleBlock.Paragraph && (sameText(it.text.text, title) || (!subtitle.isNullOrBlank() && sameText(it.text.text, subtitle))) }
            val length = blocks.sumOf { textOf(it).length }
            require(blocks.isNotEmpty() && length >= MIN_CONTENT_LENGTH) { "No readable article content found (extracted $length characters)" }
            Article(url.hashCode().toUInt().toString(16), host, url, title, subtitle, extractAuthor(document), extractPublishedAt(document), extractHeroImage(document), blocks)
        }
    }
    private fun contentCandidates(document: Document): List<Element> {
        val selectors = linkedSetOf("[itemprop=articleBody]","article","main","[role=main]","[data-testid=article-body]","[data-testid*=article-body]","[data-testid*=article-content]","[class*=ArticleBody]","[class*=articleBody]",".duet--article--article-body-component",".duet--article--article-body",".article-body",".article-content",".article__body",".article__content",".story-body",".story-content",".post-content",".entry-content",".content-body",".c-entry-content",".article-body__content",".materia-conteudo",".materia-corpo",".materia-conteudo__texto",".materia-corpo__texto",".mc-article-body",".mc-article-body-content")
        val result = linkedSetOf<Element>()
        selectors.forEach { selector -> runCatching { document.select(selector).forEach(result::add) } }
        document.select("div,section").asSequence().filter { it.select("p").size >= 2 && it.text().length >= 80 }.sortedByDescending { it.text().length + it.select("p").size * 250 }.take(40).forEach(result::add)
        return result.toList()
    }
    private fun extractBlocks(root: Element): List<ArticleBlock> {
        val copy = root.clone(); removeNoiseFromRoot(copy); val result = mutableListOf<ArticleBlock>()
        copy.select("p,h2,h3,h4,h5,blockquote,ul,ol,figure,img").forEach { element -> when (element.tagName()) {
            "p" -> element.text().trim().takeIf { it.length >= 2 }?.let { result += ArticleBlock.Paragraph(it, sanitizeInlineHtml(element)) }
            "h2","h3","h4","h5" -> element.text().trim().takeIf(String::isNotBlank)?.let { result += ArticleBlock.Heading(it, element.tagName().drop(1).toInt().coerceAtMost(4)) }
            "blockquote" -> element.text().trim().takeIf(String::isNotBlank)?.let { result += ArticleBlock.Quote(it) }
            "ul","ol" -> { val values = element.children().filter { it.tagName() == "li" }.map { it.text().replace(Regex("\\s+"), " ").trim() }.filter(String::isNotBlank); if (values.isNotEmpty()) result += ArticleBlock.ListBlock(values, element.tagName() == "ol") }
            "figure" -> element.selectFirst("img")?.let { image -> if (!isNoiseImage(image)) result += ArticleBlock.Image(imageUrl(image), element.selectFirst("figcaption")?.text(), image.attr("alt")) }
            "img" -> if (element.parent()?.tagName() != "figure" && !isNoiseImage(element)) result += ArticleBlock.Image(imageUrl(element), null, element.attr("alt"))
        } }
        return result.filter { it !is ArticleBlock.Image || it.url.isNotBlank() }
    }
    private fun extractJsonLdBody(document: Document): List<ArticleBlock> {
        val result = mutableListOf<ArticleBlock>()
        document.select("script[type=application/ld+json]").forEach { script ->
            val match = Regex("\\\"articleBody\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"])*)\\\"", RegexOption.DOT_MATCHES_ALL).find(script.data()) ?: return@forEach
            val body = match.groupValues[1].replace("\\\\n", "\n").replace("\\\\r", "\r").replace("\\\\\"", "\"").replace("\\\\/", "/")
            body.split(Regex("\\n{2,}")).map { it.replace(Regex("\\s+"), " ").trim() }.filter { it.length >= 2 }.forEach { result += ArticleBlock.Paragraph(it) }
        }
        return result
    }
    private fun removeNoise(document: Document) { document.select("script:not([type=application/ld+json]),style,noscript,iframe,canvas,svg,form,nav,footer,header,aside,[role=navigation],[role=banner],[role=contentinfo],.ad,.ads,.advert,.advertisement,.social,.share,.comments,.comment,.related,.recommendations,.recommended,.newsletter,.cookie,.cookies,.popup,.modal,.login,.subscription").remove(); document.select("[hidden],[aria-hidden=true]").remove() }
    private fun removeNoiseFromRoot(root: Element) { root.select("script,style,noscript,iframe,canvas,svg,form,nav,footer,header,aside,.ad,.ads,.advert,.advertisement,.social,.share,.comments,.comment,.related,.recommendations,.recommended,.newsletter,.cookie,.cookies,.popup,.modal,.login,.subscription").remove(); root.select("a[href*='facebook.com'],a[href*='instagram.com'],a[href*='youtube.com'],a[href*='whatsapp'],a[href*='twitter.com'],a[href*='x.com'],a[href*='threads.net'],a[href*='tiktok.com']").remove() }
    private fun dedupe(blocks: List<ArticleBlock>): List<ArticleBlock> { val seen = linkedSetOf<String>(); return blocks.filter { block -> val key = when (block) { is ArticleBlock.Paragraph -> normalize(block.text.text); is ArticleBlock.Heading -> "h:" + normalize(block.text); is ArticleBlock.Quote -> "q:" + normalize(block.text); is ArticleBlock.ListBlock -> "l:" + block.items.joinToString("|") { normalize(it) }; is ArticleBlock.Image -> "i:" + block.url }; key.isNotBlank() && seen.add(key) } }
    private fun contentScore(blocks: List<ArticleBlock>) = blocks.sumOf { textOf(it).length } + blocks.count { it is ArticleBlock.Paragraph } * 150
    private fun textOf(block: ArticleBlock) = when (block) { is ArticleBlock.Paragraph -> block.text.text; is ArticleBlock.Heading -> block.text; is ArticleBlock.Quote -> block.text; is ArticleBlock.ListBlock -> block.items.joinToString(" "); is ArticleBlock.Image -> block.caption.orEmpty() }
    private fun extractAuthor(document: Document): String? = firstNonBlank(document.select("meta[name=author]").attr("content"), document.select("meta[property=article:author]").attr("content"), document.select("[rel=author]").first()?.text(), document.select("[itemprop=author] [itemprop=name]").first()?.text(), document.select("[itemprop=author]").first()?.text())
    private fun extractPublishedAt(document: Document): Instant? = document.select("meta[property=article:published_time],meta[property=article:published],meta[name=date],meta[itemprop=datePublished],time[datetime]").asSequence().map { it.attr("content").ifBlank { it.attr("datetime") } }.mapNotNull(::parseDate).firstOrNull()
    private fun extractHeroImage(document: Document): String? = firstNonBlank(document.select("meta[property=og:image]").attr("content"), document.select("meta[name=twitter:image]").attr("content"))
    private fun imageUrl(image: Element): String = listOf(image.attr("src"),image.attr("data-src"),image.attr("data-lazy-src"),image.attr("data-original")).firstOrNull(String::isNotBlank).orEmpty().let { runCatching { URI(image.baseUri()).resolve(it).toString() }.getOrDefault(it) }
    private fun isNoiseImage(image: Element): Boolean { val value = "${image.className()} ${image.id()} ${image.attr("alt")} ${image.attr("src")}".lowercase(); return listOf("avatar","author","profile","headshot","portrait","logo","icon","sprite","favicon","tracking","pixel","qr-code","qrcode","social","share","newsletter").any(value::contains) }
    private fun sanitizeInlineHtml(element: Element): String? { val copy = element.clone(); copy.select("script,style,iframe,svg,img,video,audio,object,embed").remove(); copy.select("*").forEach { node -> val attrs = node.attributes().asList().filter { (it.key == "href" && node.tagName() == "a") || it.key == "title" || it.key == "style" }; node.clearAttributes(); attrs.forEach { attr -> if (attr.key == "style") sanitizeStyle(attr.value).takeIf(String::isNotBlank)?.let { node.attr("style", it) } else node.attr(attr.key, attr.value) } }; return copy.html().trim().takeIf { it.isNotBlank() && it != copy.text() } }
    private fun sanitizeStyle(style: String) = style.split(';').mapNotNull { declaration -> val parts = declaration.split(':', limit = 2); if (parts.size != 2) null else { val property = parts[0].trim().lowercase(); val value = parts[1].trim(); if (property in setOf("color","font-weight","text-decoration") && value.isNotBlank() && value.length <= 80 && !value.contains('{') && !value.contains('}')) "$property:$value" else null } }.joinToString(";")
    private fun parseDate(value: String?): Instant? = value?.trim()?.takeIf(String::isNotBlank)?.let { runCatching { Instant.parse(it) }.getOrNull() ?: runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() ?: runCatching { ZonedDateTime.parse(it).toInstant() }.getOrNull() ?: runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }.getOrNull() }
    private fun firstNonBlank(vararg values: String?): String? = values.asSequence().mapNotNull { it?.replace(Regex("\\s+"), " ")?.trim() }.firstOrNull(String::isNotBlank)
    private fun sameText(a: String, b: String) = normalize(a) == normalize(b)
    private fun normalize(value: String) = value.lowercase().replace(Regex("\\s+"), " ").trim().removeSuffix(".")
    companion object { private const val MIN_CONTENT_LENGTH = 80; private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/140.0.0.0 Mobile Safari/537.36 NewsRSS/0.5" }
}
