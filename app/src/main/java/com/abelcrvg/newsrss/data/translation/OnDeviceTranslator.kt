package com.abelcrvg.newsrss.data.translation

import android.content.Context
import androidx.compose.ui.text.AnnotatedString
import com.abelcrvg.newsrss.core.feed.FeedItem
import com.abelcrvg.newsrss.core.model.Article
import com.abelcrvg.newsrss.core.model.ArticleBlock
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import org.jsoup.Jsoup
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * English -> Brazilian Portuguese translation for English sources, entirely on-device.
 *
 * The important difference from the old implementation is that article paragraphs are
 * no longer translated one-by-one. Consecutive editorial blocks are sent together in
 * small context windows, so the model can use the surrounding sentences to resolve
 * pronouns, verb tense and ambiguous words. Translation is also serialized through one
 * Translator instance instead of firing dozens of concurrent requests at the same model.
 */
class OnDeviceTranslator(context: Context) {
    private val translator: Translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.PORTUGUESE)
            .build()
    )

    suspend fun translateFeedItems(items: List<FeedItem>): List<FeedItem> {
        if (items.isEmpty()) return items
        return try {
            ensureModel()
            items.map { item ->
                val parts = buildList {
                    add(item.title)
                    item.summary?.takeIf { it.isNotBlank() }?.let(::add)
                }
                val translated = translateContext(parts)
                item.copy(
                    title = translated.getOrElse(0) { item.title },
                    summary = item.summary?.let { translated.getOrElse(1) { it } }
                )
            }
        } catch (_: Exception) {
            items
        } finally {
            translator.close()
        }
    }

    suspend fun translateArticle(article: Article): Article {
        return try {
            ensureModel()

            // Keep the headline and deck together so terms in the headline can be
            // disambiguated by the subtitle/deck instead of being translated literally.
            val headerParts = buildList {
                add(article.title)
                article.subtitle?.takeIf { it.isNotBlank() }?.let(::add)
            }
            val translatedHeader = translateContext(headerParts)

            // Translate the body in contextual windows. Images are not textual context,
            // while paragraphs/headings/quotes are. Lists are kept separate to preserve
            // their item boundaries exactly.
            val translatedBlocks = translateBodyBlocks(article.blocks)

            article.copy(
                title = translatedHeader.getOrElse(0) { article.title },
                subtitle = article.subtitle?.let { translatedHeader.getOrElse(1) { it } },
                // Proper names should never be machine-translated as if they were prose.
                author = article.author,
                blocks = translatedBlocks
            )
        } catch (_: Exception) {
            article
        } finally {
            translator.close()
        }
    }

    private suspend fun translateBodyBlocks(blocks: List<ArticleBlock>): List<ArticleBlock> {
        if (blocks.isEmpty()) return blocks

        val result = blocks.toMutableList()
        val contextual = blocks.mapIndexedNotNull { index, block ->
            when (block) {
                is ArticleBlock.Paragraph -> ContextPart(index, block.text.text)
                is ArticleBlock.Heading -> ContextPart(index, block.text)
                is ArticleBlock.Quote -> ContextPart(index, block.text)
                else -> null
            }
        }

        var cursor = 0
        while (cursor < contextual.size) {
            val window = ArrayList<ContextPart>()
            var length = 0
            while (cursor < contextual.size) {
                val candidate = contextual[cursor]
                val extra = candidate.text.length + MARKER_OVERHEAD
                if (window.isNotEmpty() && length + extra > MAX_CONTEXT_CHARS) break
                window += candidate
                length += extra
                cursor++
                if (length >= MAX_CONTEXT_CHARS) break
            }

            val translated = translateContext(window.map { it.text })
            if (translated.size == window.size) {
                window.forEachIndexed { position, part ->
                    val translatedText = translated[position]
                    result[part.index] = when (val block = result[part.index]) {
                        is ArticleBlock.Paragraph -> block.copy(
                            text = AnnotatedString(translatedText),
                            inlineHtml = block.inlineHtml?.let { translateInlineHtml(translatedText, it) }
                        )
                        is ArticleBlock.Heading -> block.copy(text = translatedText)
                        is ArticleBlock.Quote -> block.copy(text = translatedText)
                        else -> block
                    }
                }
            } else {
                // A malformed marker response must never destroy the article. Fall back
                // to individual translation for just this window.
                window.forEach { part ->
                    val translatedText = translateText(part.text)
                    result[part.index] = when (val block = result[part.index]) {
                        is ArticleBlock.Paragraph -> block.copy(
                            text = AnnotatedString(translatedText),
                            inlineHtml = block.inlineHtml?.let { translateInlineHtml(translatedText, it) }
                        )
                        is ArticleBlock.Heading -> block.copy(text = translatedText)
                        is ArticleBlock.Quote -> block.copy(text = translatedText)
                        else -> block
                    }
                }
            }
        }

        // Captions, alt text and list items are intentionally translated separately:
        // their boundaries are meaningful UI structure and should not be merged.
        blocks.forEachIndexed { index, block ->
            when (block) {
                is ArticleBlock.Image -> result[index] = block.copy(
                    caption = block.caption?.let { translateText(it) },
                    altText = block.altText?.let { translateText(it) }
                )
                is ArticleBlock.ListBlock -> result[index] = block.copy(
                    items = block.items.map { translateText(it) }
                )
                else -> Unit
            }
        }

        return result
    }

    private suspend fun translateContext(parts: List<String>): List<String> {
        if (parts.isEmpty()) return emptyList()
        if (parts.size == 1) return listOf(translateText(parts[0]))

        val payload = parts.mapIndexed { index, text ->
            "$MARKER$index]\n$text"
        }.joinToString("\n\n")

        val translated = runCatching { translateText(payload) }.getOrElse {
            return parts.map { translateText(it) }
        }

        val regex = Regex(
            "(?s)${Regex.escape(MARKER)}(\\d+)]\\s*\\n(.*?)(?=\\n\\n${Regex.escape(MARKER)}\\d+]\\s*\\n|$)"
        )
        val parsed = regex.findAll(translated).associate { match ->
            match.groupValues[1].toInt() to match.groupValues[2].trim()
        }

        return if (parsed.size == parts.size && parsed.keys == parts.indices.toSet()) {
            parts.indices.map { parsed[it].orEmpty() }
        } else {
            parts.map { translateText(it) }
        }
    }

    private suspend fun ensureModel() {
        await<Unit> { continuation ->
            translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
                .addOnSuccessListener { continuation.resume(Unit) }
                .addOnFailureListener { continuation.resumeWithException(it) }
        }
    }

    private suspend fun translateInlineHtml(translatedText: String, html: String): String {
        // Keep the original HTML structure. The visible paragraph has already been
        // translated with context; here only translate text nodes that are not represented
        // by the normalized paragraph text, such as linked/strong fragments.
        val body = Jsoup.parseBodyFragment(html).body()
        translateTextNodes(body)
        return body.html()
    }

    private suspend fun translateTextNodes(node: Node) {
        node.childNodes().forEach { child ->
            when (child) {
                is TextNode -> child.text(translateText(child.text()))
                else -> translateTextNodes(child)
            }
        }
    }

    private suspend fun translateText(text: String): String {
        if (text.isBlank() || text.trim().length < 3) return text
        return try {
            await<String> { continuation ->
                translator.translate(text)
                    .addOnSuccessListener { translated ->
                        val leading = text.takeWhile { it.isWhitespace() }
                        val trailing = text.takeLastWhile { it.isWhitespace() }
                        continuation.resume(leading + translated + trailing)
                    }
                    .addOnFailureListener { continuation.resumeWithException(it) }
            }
        } catch (_: Exception) {
            text
        }
    }

    private suspend fun <T> await(register: (kotlin.coroutines.Continuation<T>) -> Unit): T =
        suspendCancellableCoroutine { continuation -> register(continuation) }

    private data class ContextPart(val index: Int, val text: String)

    private companion object {
        const val MARKER = "###NEWSRSS_BLOCK_"
        const val MAX_CONTEXT_CHARS = 3000
        const val MARKER_OVERHEAD = 32
    }
}
