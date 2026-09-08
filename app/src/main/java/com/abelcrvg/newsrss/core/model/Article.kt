package com.abelcrvg.newsrss.core.model

import androidx.compose.ui.text.AnnotatedString
import java.time.Instant

/**
 * Normalized representation of an article, independent of its original source.
 *
 * The extractor preserves editorial structure and, for paragraphs, a small amount
 * of inline HTML so emphasis such as bold text and links can survive reader mode.
 */
data class Article(
    val id: String,
    val sourceId: String,
    val url: String,
    val title: String,
    val subtitle: String? = null,
    val author: String? = null,
    val publishedAt: Instant? = null,
    val heroImageUrl: String? = null,
    val blocks: List<ArticleBlock> = emptyList()
)

sealed interface ArticleBlock {
    data class Paragraph(
        val text: AnnotatedString,
        val inlineHtml: String? = null
    ) : ArticleBlock {
        constructor(text: String, inlineHtml: String? = null) : this(
            AnnotatedString(text),
            inlineHtml
        )
    }
    data class Heading(val text: String, val level: Int = 2) : ArticleBlock
    data class Image(
        val url: String,
        val caption: String? = null,
        val altText: String? = null
    ) : ArticleBlock
    data class Quote(val text: String, val author: String? = null) : ArticleBlock
    data class ListBlock(val items: List<String>, val ordered: Boolean = false) : ArticleBlock
}
