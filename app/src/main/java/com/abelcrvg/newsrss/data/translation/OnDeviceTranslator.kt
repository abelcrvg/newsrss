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

/** English -> Brazilian Portuguese translation for English sources, entirely on-device. */
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
                val originalSummary = item.summary
                item.copy(
                    title = translated.getOrElse(0) { item.title },
                    summary = originalSummary?.let { original ->
                        translated.getOrElse(1) { original }
                    }
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
            val headerParts = buildList {
                add(article.title)
                article.subtitle?.takeIf { it.isNotBlank() }?.let(::add)
            }
            val translatedHeader = translateContext(headerParts)
            val translatedBlocks = translateBodyBlocks(article.blocks)
            val originalSubtitle = article.subtitle
            article.copy(
                title = translatedHeader.getOrElse(0) { article.title },
                subtitle = originalSubtitle?.let { original ->
                    translatedHeader.getOrElse(1) { original }
                },
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
            window.forEachIndexed { position, part ->
                val translatedText = translated.getOrElse(position) { part.text }
                result[part.index] = when (val block = result[part.index]) {
                    is ArticleBlock.Paragraph -> block.copy(
                        text = AnnotatedString(translatedText),
                        inlineHtml = block.inlineHtml
                    )
                    is ArticleBlock.Heading -> block.copy(text = translatedText)
                    is ArticleBlock.Quote -> block.copy(text = translatedText)
                    else -> block
                }
            }
        }

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
