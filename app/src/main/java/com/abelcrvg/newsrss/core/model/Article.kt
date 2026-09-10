package com.abelcrvg.newsrss.core.model

import androidx.compose.ui.graphics.Color
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

private data class InlineFrame(val tag: String, val style: SpanStyle)

private fun annotatedParagraph(text: String, inlineHtml: String?): AnnotatedString {
    if (inlineHtml.isNullOrBlank()) return AnnotatedString(text)
    return runCatching {
        buildAnnotatedString {
            var cursor = 0
            val tagRegex = Regex("<(/?)(strong|b|em|i|u|a|span)(?:\\s+([^>]*))?>", RegexOption.IGNORE_CASE)
            val stack = ArrayDeque<InlineFrame>()

            fun appendSegment(value: String) {
                if (value.isEmpty()) return
                val start = length
                append(stripInlineTags(value))
                if (length == start) return
                stack.forEach { frame -> addStyle(frame.style, start, length) }
            }

            tagRegex.findAll(inlineHtml).forEach { match ->
                appendSegment(inlineHtml.substring(cursor, match.range.first))
                val closing = match.groupValues[1] == "/"
                val tag = match.groupValues[2].lowercase()
                val attributes = match.groupValues.getOrNull(3).orEmpty()
                if (!closing) {
                    val style = when (tag) {
                        "strong", "b" -> SpanStyle(fontWeight = FontWeight.Bold)
                        "em", "i" -> SpanStyle(fontStyle = FontStyle.Italic)
                        "u", "a" -> SpanStyle(textDecoration = TextDecoration.Underline)
                        "span" -> parseInlineStyle(attributes)
                        else -> SpanStyle()
                    }
                    stack.addLast(InlineFrame(tag, style))
                } else {
                    val index = stack.indexOfLast { it.tag == tag }
                    if (index >= 0) stack.removeAt(index)
                }
                cursor = match.range.last + 1
            }
            appendSegment(inlineHtml.substring(cursor))
        }.let { if (it.text.isBlank()) AnnotatedString(text) else it }
    }.getOrElse { AnnotatedString(text) }
}

private fun parseInlineStyle(attributes: String): SpanStyle {
    val rawStyle = Regex("(?:^|\\s)style\\s*=\\s*[\\\"']([^\\\"']*)[\\\"']", RegexOption.IGNORE_CASE)
        .find(attributes)?.groupValues?.getOrNull(1).orEmpty()
    if (rawStyle.isBlank()) return SpanStyle()

    var color: Color? = null
    var weight: FontWeight? = null
    var decoration: TextDecoration? = null

    rawStyle.split(';').forEach { declaration ->
        val parts = declaration.split(':', limit = 2)
        if (parts.size != 2) return@forEach
        val property = parts[0].trim().lowercase()
        val value = parts[1].trim()
        when (property) {
            "color" -> parseCssColor(value)?.let { color = it }
            "font-weight" -> if (value == "bold" || value.toIntOrNull()?.let { it >= 600 } == true) weight = FontWeight.Bold
            "text-decoration" -> if (value.contains("underline", ignoreCase = true)) decoration = TextDecoration.Underline
        }
    }

    return SpanStyle(
        color = color ?: Color.Unspecified,
        fontWeight = weight,
        textDecoration = decoration
    )
}

private fun parseCssColor(value: String): Color? {
    val v = value.trim().lowercase()
    val named = mapOf(
        "red" to Color(0xFFF44336), "darkred" to Color(0xFF8B0000), "crimson" to Color(0xFFDC143C),
        "blue" to Color(0xFF0000FF), "green" to Color(0xFF008000), "black" to Color.Black,
        "white" to Color.White, "gray" to Color.Gray, "grey" to Color.Gray,
        "orange" to Color(0xFFFF9800), "yellow" to Color(0xFFFFFF00), "purple" to Color(0xFF800080)
    )
    named[v]?.let { return it }
    if (v.startsWith("#")) return runCatching { Color(android.graphics.Color.parseColor(v)) }.getOrNull()
    val rgb = Regex("rgb\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\)").find(v)
    return rgb?.let {
        Color(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt())
    }
}

private fun stripInlineTags(value: String): String =
    value.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
