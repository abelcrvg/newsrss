package com.abelcrvg.newsrss.data.extraction

import com.abelcrvg.newsrss.core.extraction.ArticleExtractor
import com.abelcrvg.newsrss.core.model.Article
import com.abelcrvg.newsrss.core.model.ArticleBlock
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class JsoupArticleExtractor : ArticleExtractor {
    override suspend fun extract(url: String): Result<Article> = runCatching {
        val document = Jsoup.connect(url)
            .userAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/128 Mobile Safari/537.36")
            .referrer("https://www.google.com/")
            .timeout(20_000)
            .get()
        document.select("script,style,noscript,iframe,nav,footer,header,aside,form,.advertisement,.ad,.ads,.social-share,.related-content,.newsletter,.comments").remove()
        val title = document.select("meta[property=og:title]").attr("content").ifBlank { document.select("h1").firstOrNull()?.text().orEmpty() }.ifBlank { document.title() }.trim()
        val subtitle = document.select("meta[property=og:description]").attr("content").trim().ifBlank { document.select("[class*=subtitle],[class*=subheadline],.dek,.standfirst").firstOrNull()?.text()?.trim().orEmpty() }.ifBlank { null }
        val author = document.select("meta[name=author]").attr("content").trim().ifBlank { document.select("[rel=author],[class*=author],[class*=byline]").firstOrNull()?.text()?.trim().orEmpty() }.ifBlank { null }
        val publishedAt = parseDate(document.select("meta[property=article:published_time],meta[name=date],meta[itemprop=datePublished]").firstOrNull()?.attr("content") ?: document.select("time[datetime]").firstOrNull()?.attr("datetime"))
        val body = findBestBody(document, url)
        val blocks = extractBlocks(body)
        if (blocks.none { it is ArticleBlock.Paragraph || it is ArticleBlock.Heading }) throw IllegalStateException("Não foi possível encontrar o conteúdo da notícia.")
        Article(id = url, sourceId = sourceIdFromUrl(url), url = url, title = title, subtitle = subtitle, author = author, publishedAt = publishedAt, heroImageUrl = extractHeroImage(document), blocks = blocks)
    }

    private fun findBestBody(document: org.jsoup.nodes.Document, url: String): Element {
        val candidates = mutableListOf<Element>()
        if (url.contains("theverge.com", true)) document.select("[data-testid='article-body'],[data-testid='article-content'],.duet--article--article-body-component,.duet--article--article-body,.duet--article--article-body-component > div").forEach(candidates::add)
        document.select("[itemprop=articleBody],article,main,[role=main],[data-testid=article-body],[data-testid*=article-body],[data-testid*=article-content],[class*=ArticleBody],[class*=articleBody],.article-body,.article-content,.article__body,.article__content,.story-body,.story-content,.post-content,.entry-content,.content-body,.c-entry-content").forEach(candidates::add)
        val jsonBody = document.select("script[type=application/ld+json]").mapNotNull { it.data().takeIf { data -> data.contains("articleBody", true) } }.firstOrNull()
        val scored = candidates.distinctBy { it.outerHtml() }.map { it to bodyScore(it) }.sortedByDescending { it.second }
        if (scored.isNotEmpty() && scored.first().second >= 80) return scored.first().first
        if (jsonBody != null) {
            val text = Regex("\\\"articleBody\\\"\\s*:\\s*\\\"(.*?)\\\"", RegexOption.DOT_MATCHES_ALL).find(jsonBody)?.groupValues?.getOrNull(1)
            if (!text.isNullOrBlank()) return Jsoup.parse("<article><p>${org.jsoup.parser.Parser.unescapeEntities(text, false)}</p></article>").selectFirst("article")!!
        }
        return document.body()
    }

    private fun bodyScore(element: Element): Int = element.text().length + element.select("p").count { it.text().trim().length >= 40 } * 160 + element.select("h2,h3").size * 80

    private fun extractBlocks(body: Element): List<ArticleBlock> {
        val result = mutableListOf<ArticleBlock>()
        body.select("script,style,noscript,iframe,svg,nav,footer,header,aside,form,.advertisement,.ad,.ads,.social-share,.related-content,.newsletter,.comments,img,video,audio,figure:has(img)").remove()
        body.select("h1,h2,h3,h4,h5,h6,p,blockquote,li").forEach { element ->
            val text = sanitizeInlineHtml(element) ?: return@forEach
            when (element.tagName()) {
                "h1", "h2", "h3", "h4", "h5", "h6" -> result += ArticleBlock.Heading(text, element.tagName().drop(1).toInt())
                "blockquote" -> result += ArticleBlock.Quote(text, null)
                "li" -> result += ArticleBlock.ListBlock(listOf(text), false)
                else -> if (text.length >= 40) result += ArticleBlock.Paragraph(text)
            }
        }
        return dedupeBlocks(result)
    }

    private fun dedupeBlocks(blocks: List<ArticleBlock>): List<ArticleBlock> {
        val seen = HashSet<String>()
        return blocks.filter { block ->
            val key = when (block) {
                is ArticleBlock.Paragraph -> block.text.text
                is ArticleBlock.Heading -> block.text
                is ArticleBlock.Quote -> block.text
                is ArticleBlock.ListBlock -> block.items.joinToString("|")
                is ArticleBlock.Image -> block.url
            }.trim().lowercase()
            seen.add(key)
        }
    }

    private fun sanitizeInlineHtml(element: Element): String? {
        val copy = element.clone()
        copy.select("script,style,iframe,svg,img,video,audio,object,embed").remove()
        copy.select("*").forEach { node -> node.removeAttr("class"); node.removeAttr("id"); node.removeAttr("style"); node.removeAttr("onclick"); node.removeAttr("onload") }
        return copy.text().replace(Regex("\\s+"), " ").trim().takeIf { it.length >= 2 }
    }

    private fun extractHeroImage(document: org.jsoup.nodes.Document): String? {
        val meta = document.select("meta[property=og:image],meta[name=twitter:image]").firstOrNull()?.attr("content")?.trim()
        if (!meta.isNullOrBlank()) return meta
        return document.select("img").firstOrNull { isLargeUsefulImage(it) }?.let { largestSrcSet(it) ?: it.absUrl("src") }?.takeIf { it.isNotBlank() }
    }

    private fun largestSrcSet(image: Element): String? {
        val srcset = image.attr("srcset").ifBlank { image.attr("data-srcset") }
        if (srcset.isBlank()) return null
        return srcset.split(',').mapNotNull { candidate ->
            val parts = candidate.trim().split(Regex("\\s+")); val width = parts.lastOrNull()?.removeSuffix("w")?.toIntOrNull() ?: 0; val url = parts.firstOrNull().orEmpty()
            if (url.isNotBlank() && width > 0) width to url else null
        }.maxByOrNull { it.first }?.second
    }

    private fun isLargeUsefulImage(image: Element): Boolean {
        val attrs = (image.attr("alt") + " " + image.attr("class") + " " + image.attr("id") + " " + image.attr("src") + " " + image.attr("data-src")).lowercase()
        val blocked = listOf("avatar", "author", "profile", "headshot", "portrait", "logo", "icon", "sprite", "favicon", "tracking", "pixel", "qr-code", "social", "share", "newsletter", "related", "recommend", "thumbnail", "reporter", "journalist", "byline")
        if (blocked.any(attrs::contains)) return false
        val width = listOf(image.attr("width"), image.attr("data-width"), image.attr("data-image-width")).mapNotNull(String::toIntOrNull).maxOrNull()
        val height = listOf(image.attr("height"), image.attr("data-height"), image.attr("data-image-height")).mapNotNull(String::toIntOrNull).maxOrNull()
        if (width != null && height != null) return width >= MIN_IMAGE_WIDTH && height >= MIN_IMAGE_HEIGHT
        if (width != null && width < MIN_IMAGE_WIDTH) return false
        if (height != null && height < MIN_IMAGE_HEIGHT) return false
        val srcsetWidths = listOf(image.attr("srcset"), image.attr("data-srcset")).flatMap { set -> set.split(',').mapNotNull { Regex("(\\d{3,5})w").find(it)?.groupValues?.get(1)?.toIntOrNull() } }
        if (srcsetWidths.maxOrNull()?.let { it < MIN_IMAGE_WIDTH } == true) return false
        val ratio = if (width != null && height != null && height > 0) width.toFloat() / height else null
        if (ratio != null && (ratio < 0.55f || ratio > 2.6f) && width != null && width < 900) return false
        return true
    }

    private fun parseDate(value: String?): Instant? = value?.trim()?.takeIf { it.isNotBlank() }?.let { raw ->
        runCatching { Instant.parse(raw) }.getOrNull() ?: runCatching { java.time.OffsetDateTime.parse(raw).toInstant() }.getOrNull() ?: runCatching { java.time.LocalDateTime.parse(raw, DateTimeFormatter.ISO_LOCAL_DATE_TIME).toInstant(ZoneOffset.UTC) }.getOrNull()
    }

    private fun sourceIdFromUrl(url: String): String = runCatching { java.net.URI(url).host.orEmpty().removePrefix("www.").substringBefore('.') }.getOrDefault("")

    private companion object { const val MIN_IMAGE_WIDTH = 640; const val MIN_IMAGE_HEIGHT = 360 }
}
