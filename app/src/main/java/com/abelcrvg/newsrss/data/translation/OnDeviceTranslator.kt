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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import org.jsoup.Jsoup
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** English -> Brazilian Portuguese translation for The Verge, entirely on-device. */
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
            coroutineScope {
                items.map { item ->
                    async {
                        item.copy(
                            title = translateText(translator, item.title),
                            summary = item.summary?.let { translateText(translator, it) }
                        )
                    }
                }.awaitAll()
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
            coroutineScope {
                val title = async { translateText(translator, article.title) }
                val subtitle = article.subtitle?.let { async { translateText(translator, it) } }
                val author = article.author?.let { async { translateText(translator, it) } }
                val translatedBlocks = article.blocks.map { block ->
                    async {
                        when (block) {
                            is ArticleBlock.Paragraph -> block.copy(
                                text = AnnotatedString(translateText(translator, block.text.text)),
                                inlineHtml = block.inlineHtml?.let { translateInlineHtml(translator, it) }
                            )
                            is ArticleBlock.Heading -> block.copy(text = translateText(translator, block.text))
                            is ArticleBlock.Quote -> block.copy(
                                text = translateText(translator, block.text),
                                author = block.author?.let { translateText(translator, it) }
                            )
                            is ArticleBlock.ListBlock -> block.copy(
                                items = block.items.map { translateText(translator, it) }
                            )
                            is ArticleBlock.Image -> block.copy(
                                caption = block.caption?.let { translateText(translator, it) },
                                altText = block.altText?.let { translateText(translator, it) }
                            )
                        }
                    }
                }.awaitAll()

                article.copy(
                    title = title.await(),
                    subtitle = subtitle?.await(),
                    author = author?.await(),
                    blocks = translatedBlocks
                )
            }
        } catch (_: Exception) {
            article
        } finally {
            translator.close()
        }
    }

    private suspend fun ensureModel() {
        await<Unit> { continuation ->
            translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
                .addOnSuccessListener { continuation.resume(Unit) }
                .addOnFailureListener { continuation.resumeWithException(it) }
        }
    }

    private suspend fun translateInlineHtml(translator: Translator, html: String): String {
        val body = Jsoup.parseBodyFragment(html).body()
        translateTextNodes(translator, body)
        return body.html()
    }

    private suspend fun translateTextNodes(translator: Translator, node: Node) {
        node.childNodes().forEach { child ->
            when (child) {
                is TextNode -> child.text(translateText(translator, child.text()))
                else -> translateTextNodes(translator, child)
            }
        }
    }

    private suspend fun translateText(translator: Translator, text: String): String {
        val clean = text.trim()
        if (clean.isBlank() || clean.length < 3) return text
        return try {
            await<String> { continuation ->
                translator.translate(clean)
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
}
