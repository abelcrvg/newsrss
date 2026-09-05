package com.abelcrvg.newsrss.data.translation

import android.content.Context
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
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Fast on-device English -> Portuguese translation. The caller only uses this for English sources. */
class OnDeviceTranslator(context: Context) {
    private val appContext = context.applicationContext

    suspend fun translateArticle(article: Article): Article {
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.PORTUGUESE)
            .build()
        val translator = Translation.getClient(options)
        return try {
            // Download/check the model exactly once per article instead of once per text block.
            await<Unit> { continuation ->
                translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
                    .addOnSuccessListener { continuation.resume(Unit) }
                    .addOnFailureListener { continuation.resumeWithException(it) }
            }

            coroutineScope {
                val title = async { translateText(translator, article.title) }
                val subtitle = article.subtitle?.let { async { translateText(translator, it) } }
                val author = article.author?.let { async { translateText(translator, it) } }
                val translatedBlocks = article.blocks.map { block ->
                    async {
                        when (block) {
                            is ArticleBlock.Paragraph -> block.copy(text = translateText(translator, block.text))
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
            // If the model cannot be downloaded/used, keep the original article readable.
            article
        } finally {
            translator.close()
        }
    }

    private suspend fun translateText(translator: Translator, text: String): String {
        val clean = text.trim()
        if (clean.isBlank() || clean.length < 3) return text
        return try {
            await<String> { continuation ->
                translator.translate(clean)
                    .addOnSuccessListener { continuation.resume(it) }
                    .addOnFailureListener { continuation.resumeWithException(it) }
            }
        } catch (_: Exception) {
            text
        }
    }

    private suspend fun <T> await(register: (kotlin.coroutines.Continuation<T>) -> Unit): T =
        suspendCancellableCoroutine { continuation -> register(continuation) }
}
