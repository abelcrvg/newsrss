package com.abelcrvg.newsrss.data.extraction

import com.abelcrvg.newsrss.core.extraction.ArticleExtractor
import com.abelcrvg.newsrss.core.model.Article
import com.abelcrvg.newsrss.core.model.ArticleBlock
import com.abelcrvg.newsrss.core.model.ExtractionMetadata
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class JsoupArticleExtractor : ArticleExtractor {
    override suspend fun extract(url: String): Result<Article> = runCatching {
        val document = Jsoup.connect(url)
            .userAgent(USER_AGENT)
            .referrer("https://www.google.com/")
            .timeout(20_000)
            .followRedirects(true)
            .get()
        val profile = ExtractionProfiles.forUrl(url)
        document.select("script,style,noscript,iframe,nav,footer,header,aside,form,.advertisement,.ad,.ads,.social-share,.related-content,.newsletter,.comments").remove()
        val title = document.select("meta[property=og:title]").attr("content").ifBlank { document.select("h1").firstOrNull()?.text().orEmpty() }.ifBlank { document.title() }.trim()
        val subtitle = document.select("meta[property=og:description]").attr("content").trim().ifBlank { document.select(profile.subtitleSelectors).firstOrNull()?.text()?.trim().orEmpty() }.ifBlank { null }
        val author = document.select("meta[name=author]").attr("content").trim().ifBlank { document.select(profile.authorSelectors).firstOrNull()?.text()?.trim().orEmpty() }.ifBlank { null }
        val publishedAt = parseDate(document.select("meta[property=article:published_time],meta[name=date],meta[itemprop=datePublished]").firstOrNull()?.attr("content") ?: document.select("time[datetime]").firstOrNull()?.attr("datetime"))
        val body = findBestBody(document, profile)
        val blocks = extractBlocks(body, url)
        if (blocks.none { it is ArticleBlock.Paragraph || it is ArticleBlock.Heading }) throw IllegalStateException("Não foi possível encontrar o conteúdo da notícia.")
        val hero = extractHeroImage(document, url)
        val paragraphText = blocks.filterIsInstance<ArticleBlock.Paragraph>().joinToString(" ") { it.text.text }
        val wordCount = paragraphText.split(Regex("\\s+")).count { it.isNotBlank() }
        val extractionConfidence = calculateConfidence(title, subtitle, author, publishedAt, hero, blocks, body, profile)
        val warnings = buildList {
            if (author == null) add("author_missing")
            if (publishedAt == null) add("date_missing")
            if (hero == null) add("hero_image_missing")
            if (wordCount < 250) add("short_article")
            if (extractionConfidence < 80) add("low_confidence")
        }
        Article(
            id = url,
            sourceId = sourceIdFromUrl(url),
            url = url,
            title = title,
            subtitle = subtitle,
            author = author,
            publishedAt = publishedAt,
            heroImageUrl = hero,
            blocks = blocks,
            extraction = ExtractionMetadata(extractionConfidence, profile.name, wordCount, blocks.count { it is ArticleBlock.Image }, warnings)
        )
    }

    private fun findBestBody(document: org.jsoup.nodes.Document, profile: ExtractionProfile): Element {
        val candidates = mutableListOf<Element>()
        document.select(profile.bodySelectors).forEach(candidates::add)
        document.select("[itemprop=articleBody],article,main,[role=main],[data-testid=article-body],[data-testid*=article-body],[data-testid*=article-content],[class*=ArticleBody],[class*=articleBody],.article-body,.article-content,.article__body,.article__content,.story-body,.story-content,.post-content,.entry-content,.content-body,.c-entry-content").forEach(candidates::add)
        val jsonBody = document.select("script[type=application/ld+json]").mapNotNull { it.data().takeIf { data -> data.contains("articleBody", true) } }.firstOrNull()
        val scored = candidates.distinctBy { it.outerHtml() }.map { it to bodyScore(it) }.sortedByDescending { it.second }
        if (scored.isNotEmpty() && scored.first().second >= profile.minimumBodyScore) return scored.first().first
        if (jsonBody != null) {
            val text = Regex("\\\"articleBody\\\"\\s*:\\s*\\\"(.*?)\\\"", RegexOption.DOT_MATCHES_ALL).find(jsonBody)?.groupValues?.getOrNull(1)
            if (!text.isNullOrBlank()) return Jsoup.parse("<article><p>${org.jsoup.parser.Parser.unescapeEntities(text, false)}</p></article>").selectFirst("article")!!
        }
        return document.body()
    }

    private fun bodyScore(element: Element): Int = element.text().length + element.select("p").count { it.text().trim().length >= 40 } * 160 + element.select("h2,h3").size * 80

    /** Extract the complete article body without imposing a character or paragraph cap. */
    private fun extractBlocks(body: Element, baseUrl: String): List<ArticleBlock> {
        val result = mutableListOf<ArticleBlock>()
        body.select("script,style,noscript,iframe,svg,nav,footer,header,aside,form,.advertisement,.ad,.ads,.social-share,.related-content,.newsletter,.comments,video,audio,object,embed").remove()
        val elements = body.select("h1,h2,h3,h4,h5,h6,p,blockquote,li,figure,img")
        val emittedImages = HashSet<String>()
        elements.forEach { element ->
            when (element.tagName()) {
                "figure" -> {
                    val image = element.selectFirst("img") ?: return@forEach
                    val imageUrl = imageUrl(image, baseUrl) ?: return@forEach
                    if (!emittedImages.add(imageUrl)) return@forEach
                    result += ArticleBlock.Image(imageUrl, element.selectFirst("figcaption")?.text()?.trim()?.takeIf { it.isNotBlank() }, image.attr("alt").trim().takeIf { it.isNotBlank() })
                }
                "img" -> {
                    if (element.parents().any { it.tagName() == "figure" }) return@forEach
                    val imageUrl = imageUrl(element, baseUrl) ?: return@forEach
                    if (!emittedImages.add(imageUrl)) return@forEach
                    result += ArticleBlock.Image(imageUrl, altText = element.attr("alt").trim().takeIf { it.isNotBlank() })
                }
                "h1", "h2", "h3", "h4", "h5", "h6" -> sanitizeInlineHtml(element)?.let { result += ArticleBlock.Heading(it, element.tagName().drop(1).toInt()) }
                "blockquote" -> sanitizeInlineHtml(element)?.let { result += ArticleBlock.Quote(it, null) }
                "li" -> sanitizeInlineHtml(element)?.let { result += ArticleBlock.ListBlock(listOf(it), false) }
                else -> sanitizeInlineHtml(element)?.takeIf { it.length >= 40 }?.let { result += ArticleBlock.Paragraph(it) }
            }
        }
        return dedupeBlocks(result)
    }

    private fun imageUrl(image: Element, baseUrl: String): String? {
        val srcset = image.attr("srcset").ifBlank { image.attr("data-srcset") }
        val candidate = if (srcset.isNotBlank()) largestSrcSet(srcset) else null
            ?: image.attr("data-src").ifBlank { image.attr("data-lazy-src") }.ifBlank { image.attr("data-original") }.ifBlank { image.attr("src") }
        if (candidate.isBlank()) return null
        return runCatching { URI(baseUrl).resolve(candidate.trim()).toString() }.getOrNull()?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    private fun dedupeBlocks(blocks: List<ArticleBlock>): List<ArticleBlock> {
        val seen = HashSet<String>()
        return blocks.filter { block ->
            val key = when (block) {
                is ArticleBlock.Paragraph -> "p:${block.text.text}"
                is ArticleBlock.Heading -> "h:${block.text}"
                is ArticleBlock.Quote -> "q:${block.text}"
                is ArticleBlock.ListBlock -> "l:${block.items.joinToString("|")}"
                is ArticleBlock.Image -> "i:${block.url}"
            }.trim().lowercase()
            seen.add(key)
        }
    }

    private fun sanitizeInlineHtml(element: Element): String? {
        val copy = element.clone()
        copy.select("script,style,iframe,svg,img,video,audio,object,embed").remove()
        copy.select("*").forEach { node ->
            node.removeAttr("class")
            node.removeAttr("id")
            node.removeAttr("style")
            node.removeAttr("onclick")
            node.removeAttr("onload")
        }
        return copy.text().replace(Regex("\\s+"), " ").trim().takeIf { it.length >= 2 }
    }

    private fun extractHeroImage(document: org.jsoup.nodes.Document, baseUrl: String): String? {
        val meta = document.select("meta[property=og:image],meta[name=twitter:image]").firstOrNull()?.attr("content")?.trim()
        if (!meta.isNullOrBlank()) return runCatching { URI(baseUrl).resolve(meta).toString() }.getOrNull()
        return document.select("img").firstOrNull { isLargeUsefulImage(it) }?.let { imageUrl(it, baseUrl) }
    }

    private fun largestSrcSet(srcset: String): String? = srcset.split(',').mapNotNull { candidate ->
        val parts = candidate.trim().split(Regex("\\s+"))
        val width = parts.lastOrNull()?.removeSuffix("w")?.toIntOrNull() ?: 0
        val url = parts.firstOrNull().orEmpty()
        if (url.isNotBlank() && width > 0) width to url else null
    }.maxByOrNull { it.first }?.second

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
        runCatching { Instant.parse(raw) }.getOrNull()
            ?: runCatching { java.time.OffsetDateTime.parse(raw).toInstant() }.getOrNull()
            ?: runCatching { java.time.ZonedDateTime.parse(raw).toInstant() }.getOrNull()
            ?: runCatching { java.time.ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }.getOrNull()
            ?: runCatching { java.time.LocalDateTime.parse(raw, DateTimeFormatter.ISO_LOCAL_DATE_TIME).toInstant(ZoneOffset.UTC) }.getOrNull()
    }

    private fun calculateConfidence(title: String, subtitle: String?, author: String?, date: Instant?, hero: String?, blocks: List<ArticleBlock>, body: Element, profile: ExtractionProfile): Int {
        var score = 0
        if (title.length >= 12) score += 20
        if (!subtitle.isNullOrBlank()) score += 10
        if (!author.isNullOrBlank()) score += 10
        if (date != null) score += 10
        if (hero != null) score += 10
        if (blocks.count { it is ArticleBlock.Paragraph } >= 3) score += 15
        if (blocks.count { it is ArticleBlock.Paragraph } >= 8) score += 10
        if (body.text().length >= 1500) score += 10
        if (bodyScore(body) >= profile.minimumBodyScore) score += 5
        return score.coerceIn(0, 100)
    }

    private fun sourceIdFromUrl(url: String): String = runCatching { URI(url).host.orEmpty().removePrefix("www.").substringBefore('.') }.getOrDefault("")

    private companion object {
        const val MIN_IMAGE_WIDTH = 640
        const val MIN_IMAGE_HEIGHT = 360
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/128 Mobile Safari/537.36"
    }
}

data class ExtractionProfile(val name: String, val bodySelectors: String, val subtitleSelectors: String, val authorSelectors: String, val minimumBodyScore: Int = 80)

object ExtractionProfiles {
    private val generic = ExtractionProfile("GenericJsoup", "[itemprop=articleBody],article,main,[role=main]", "[class*=subtitle],[class*=subheadline],.dek,.standfirst", "[rel=author],[class*=author],[class*=byline]")
    private val profiles = mapOf(
        "g1.globo.com" to ExtractionProfile("G1", "article,main,[data-testid*=article-body],[class*=content-text],.content", "[class*=subtitle],[class*=subheadline],.content-headline", "[rel=author],[class*=author],[class*=byline]"),
        "uol.com.br" to ExtractionProfile("UOL", "article,main,[data-testid*=article-body],[class*=article-body]", "[class*=subtitle],[class*=subheadline],.subheadline", "[rel=author],[class*=author],[class*=byline]"),
        "theverge.com" to ExtractionProfile("TheVerge", "[data-testid='article-body'],[data-testid='article-content'],.duet--article--article-body-component,.duet--article--article-body", "[class*=dek],[class*=subtitle]", "[rel=author],[class*=byline]"),
        "bbc.com" to ExtractionProfile("BBC", "article,[data-component=text-block],main", "[data-component=standfirst],.ssrcss-1q0x1qg-StyledSummary", "[rel=author],[class*=byline]"),
        "reuters.com" to ExtractionProfile("Reuters", "article,[data-testid=article-body],main", "[data-testid=Heading],.article-header__sub-title", "[rel=author],[class*=author]")
    )
    fun forUrl(url: String): ExtractionProfile {
        val host = runCatching { URI(url).host.orEmpty().removePrefix("www.").lowercase() }.getOrDefault("")
        return profiles.entries.firstOrNull { host == it.key || host.endsWith(".${it.key}") }?.value ?: generic
    }
}
