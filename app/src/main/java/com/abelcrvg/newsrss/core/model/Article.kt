package com.abelcrvg.newsrss.core.model

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import java.time.Instant

/**
 * Normalized representation of an article, independent of its original source.
 * Paragraphs retain a small sanitized inline-HTML representation so the reader
 * can preserve emphasis and links without embedding a WebView.
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
            annotatedParagraph(text, inlineHtml),
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

private fun annotatedParagraph(text: String, inlineHtml: String?): AnnotatedString {
    if (inlineHtml.isNullOrBlank()) return AnnotatedString(text)
    return runCatching {
        val source = inlineHtml
        buildAnnotatedString {
            var cursor = 0
            val tagRegex = Regex("<(/?)(strong|b|em|i|a)(?:\\s+[^>]*)?>", RegexOption.IGNORE_CASE)
            val stack = ArrayDeque<Pair<String, Int>>()
            tagRegex.findAll(source).forEach { match ->
                append(stripInlineTags(source.substring(cursor, match.range.first)))
                val closing = match.groupValues[1] == "/"
                val tag = match.groupValues[2].lowercase()
                if (!closing) {
                    stack.addLast(tag to length)
                    cursor = match.range.last + 1
                } else {
                    val open = stack.indexOfLast { it.first == tag }
                    if (open >= 0) {
                        val (_, start) = stack.removeAt(open)
                        if (start < length) {
                            val style = when (tag) {
                                "strong", "b" -> SpanStyle(fontWeight = FontWeight.Bold)
                                "em", "i" -> SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                                "a" -> SpanStyle(textDecoration = TextDecoration.Underline)
                                else -> SpanStyle()
                            }
                            addStyle(style, start, length)
                        }
                    }
                    cursor = match.range.last + 1
                }
            }
            append(stripInlineTags(source.substring(cursor)))
        }.let { result ->
            if (result.text.isBlank()) AnnotatedString(text) else result
        }
    }.getOrElse { AnnotatedString(text) }
}

private fun stripInlineTags(value: String): String =
    value.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace(Regex("\\s+"), " ")
        .trim()
