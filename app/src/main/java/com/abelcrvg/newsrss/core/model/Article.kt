package com.abelcrvg.newsrss.core.model

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import java.time.Instant

/** Normalized article model. Paragraphs may retain sanitized inline HTML for reader formatting. */
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
            annotatedParagraph(text, inlineHtml), inlineHtml
        )
    }
    data class Heading(val text: String, val level: Int = 2) : ArticleBlock
    data class Image(val url: String, val caption: String? = null, val altText: String? = null) : ArticleBlock
    data class Quote(val text: String, val author: String? = null) : ArticleBlock
    data class ListBlock(val items: List<String>, val ordered: Boolean = false) : ArticleBlock
}

private fun annotatedParagraph(text: String, inlineHtml: String?): AnnotatedString {
    if (inlineHtml.isNullOrBlank()) return AnnotatedString(text)
    return runCatching {
        buildAnnotatedString {
            var cursor = 0
            val tagRegex = Regex("<(/?)(strong|b|em|i|a)(?:\\s+[^>]*)?>", RegexOption.IGNORE_CASE)
            val stack = ArrayDeque<Pair<String, Int>>()
            tagRegex.findAll(inlineHtml).forEach { match ->
                append(stripInlineTags(inlineHtml.substring(cursor, match.range.first)))
                val closing = match.groupValues[1] == "/"
                val tag = match.groupValues[2].lowercase()
                if (!closing) {
                    stack.addLast(tag to length)
                } else {
                    val open = stack.indexOfLast { it.first == tag }
                    if (open >= 0) {
                        val (_, start) = stack.removeAt(open)
                        if (start < length) {
                            addStyle(when (tag) {
                                "strong", "b" -> SpanStyle(fontWeight = FontWeight.Bold)
                                "em", "i" -> SpanStyle(fontStyle = FontStyle.Italic)
                                "a" -> SpanStyle(textDecoration = TextDecoration.Underline)
                                else -> SpanStyle()
                            }, start, length)
                        }
                    }
                }
                cursor = match.range.last + 1
            }
            append(stripInlineTags(inlineHtml.substring(cursor)))
        }.let { if (it.text.isBlank()) AnnotatedString(text) else it }
    }.getOrElse { AnnotatedString(text) }
}

private fun stripInlineTags(value: String): String =
    value.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
