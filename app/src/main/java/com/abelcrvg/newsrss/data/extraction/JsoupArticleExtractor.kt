package com.abelcrvg.newsrss.data.extraction

import com.abelcrvg.newsrss.core.extraction.ArticleExtractor
import com.abelcrvg.newsrss.core.model.Article
import com.abelcrvg.newsrss.core.model.ArticleBlock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI

/** Generic reader-mode extractor for publicly available article HTML. */
class JsoupArticleExtractor(
    private val timeoutMillis: Int = 15_000
) : ArticleExtractor {

    override suspend fun extract(url: String): Result<Article> = withContext(Dispatchers.IO) {
        runCatching {
            require(url.startsWith("http://") || url.startsWith("https://"))

            val document = Jsoup.connect(url)
                .userAgent(USER_AGENT)
                .timeout(timeoutMillis)
                .followRedirects(true)
                .get()

            removeNoise(document)

            val title = firstNonBlank(
                document.select("meta[property=og:title]").attr("content"),
                document.select("h1").first()?.text(),
                document.title()
            ) ?: error("Article title not found")

            val root = findContentRoot(document) ?: error("Article content not found")
            val blocks = extractBlocks(root)
            val textLength = blocks.joinToString(" ") { textOf(it) }.length
            require(textLength >= MIN_CONTENT_LENGTH) { "Extracted content is too short" }

            Article(
                id = url.hashCode().toUInt().toString(16),
                sourceId = URI(url).host.orEmpty(),
                url = url,
                title = title,
                subtitle = firstNonBlank(
                    document.select("meta[name=description]").attr("content"),
                    document.select("meta[property=og:description]").attr("content")
                ),
                author = extractAuthor(document),
                heroImageUrl = document.select("meta[property=og:image]").attr("content")
                    .takeIf { it.isNotBlank() },
                blocks = blocks
            )
        }
    }

    private fun removeNoise(document: org.jsoup.nodes.Document) {
        document.select(
            "script,style,noscript,iframe,canvas,svg,form,nav,footer,header,aside," +
                "[role=navigation],[role=banner],[role=contentinfo]," +
                ".ad,.ads,.advert,.advertisement,.social,.share,.comments,.comment," +
                ".related,.recommendations,.recommended,.newsletter,.cookie,.cookies," +
                ".popup,.modal"
        ).remove()
        document.select("[hidden],[aria-hidden=true]").remove()
    }

    private fun findContentRoot(document: org.jsoup.nodes.Document): Element? {
        document.select("article").maxByOrNull(::score)?.let { return it }
        document.select(
            "main,[role=main],.article-body,.article-content,.article__body," +
                ".story-body,.story-content,.post-content,.entry-content,.content-body"
        ).maxByOrNull(::score)?.let { return it }
        return document.body().select("div,section").maxByOrNull(::score)
    }

    private fun score(element: Element): Int {
        val text = element.text().length
        val paragraphs = element.select("p").size
        val headings = element.select("h2,h3").size
        val links = element.select("a").text().length
        return text + paragraphs * 220 + headings * 80 - links / 3
    }

    private fun extractBlocks(root: Element): List<ArticleBlock> {
        val result = mutableListOf<ArticleBlock>()
        root.select("p,h2,h3,h4,blockquote,ul,ol,figure,img").forEach { element ->
            when (element.tagName()) {
                "p" -> element.text().trim().takeIf { it.length >= 20 }?.let {
                    result += ArticleBlock.Paragraph(it)
                }
                "h2", "h3", "h4" -> element.text().trim().takeIf { it.isNotBlank() }?.let {
                    result += ArticleBlock.Heading(it, element.tagName().drop(1).toInt())
                }
                "blockquote" -> element.text().trim().takeIf { it.isNotBlank() }?.let {
                    result += ArticleBlock.Quote(it)
                }
                "ul", "ol" -> {
                    val items = element.select(":scope > li").map { it.text().trim() }
                        .filter { it.isNotBlank() }
                    if (items.isNotEmpty()) result += ArticleBlock.ListBlock(items, element.tagName() == "ol")
                }
                "figure" -> {
                    val image = element.selectFirst("img")
                    if (image != null) addImage(result, image, element.selectFirst("figcaption")?.text())
                }
                "img" -> if (element.parent()?.tagName() != "figure") addImage(result, element, null)
            }
        }
        return result
    }

    private fun addImage(result: MutableList<ArticleBlock>, image: Element, caption: String?) {
        val src = firstNonBlank(image.absUrl("src"), image.absUrl("data-src"), image.absUrl("data-lazy-src"))
            ?: return
        if (!src.startsWith("http://") && !src.startsWith("https://")) return
        result += ArticleBlock.Image(
            url = src,
            caption = caption?.trim()?.takeIf { it.isNotBlank() },
            altText = image.attr("alt").trim().takeIf { it.isNotBlank() }
        )
    }

    private fun extractAuthor(document: org.jsoup.nodes.Document): String? = firstNonBlank(
        document.select("meta[name=author]").attr("content"),
        document.select("[rel=author]").first()?.text(),
        document.select(".author,.byline,.article-author,.article__author").first()?.text()
    )

    private fun textOf(block: ArticleBlock): String = when (block) {
        is ArticleBlock.Paragraph -> block.text
        is ArticleBlock.Heading -> block.text
        is ArticleBlock.Quote -> block.text
        is ArticleBlock.ListBlock -> block.items.joinToString(" ")
        is ArticleBlock.Image -> block.caption.orEmpty()
    }

    private fun firstNonBlank(vararg values: String?): String? = values.asSequence()
        .mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }
        .firstOrNull()

    private companion object {
        const val MIN_CONTENT_LENGTH = 200
        const val USER_AGENT = "NewsRSS/0.1 (Android; open-source reader)"
    }
}
