package com.abelcrvg.newsrss.data.translation

import android.content.Context
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.nl.translate.Translator
import com.abelcrvg.newsrss.core.model.Article
import com.abelcrvg.newsrss.core.model.ArticleBlock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Translates article text locally on the device, with Portuguese as the UI language. */
class OnDeviceTranslator(context: Context) {
    private val appContext = context.applicationContext

    suspend fun translateArticle(article: Article): Article {
        val translatedTitle = translateText(article.title)
        val translatedSubtitle = article.subtitle?.let { translateText(it) }
        val translatedAuthor = article.author?.let { translateText(it) }
        val translatedBlocks = article.blocks.map { block ->
            when (block) {
                is ArticleBlock.Paragraph -> block.copy(text = translateText(block.text))
                is ArticleBlock.Heading -> block.copy(text = translateText(block.text))
                is ArticleBlock.Quote -> block.copy(
                    text = translateText(block.text),
                    author = block.author?.let { translateText(it) }
                )
                is ArticleBlock.ListBlock -> block.copy(items = block.items.map { translateText(it) })
                is ArticleBlock.Image -> block.copy(
                    caption = block.caption?.let { translateText(it) },
                    altText = block.altText?.let { translateText(it) }
                )
            }
        }
        return article.copy(
            title = translatedTitle,
            subtitle = translatedSubtitle,
            author = translatedAuthor,
            blocks = translatedBlocks
        )
    }

    private suspend fun translateText(text: String): String {
        val clean = text.trim()
        if (clean.isBlank() || clean.length < 3) return text

        val languageId = LanguageIdentification.getClient()
        return try {
            val languageTag = await<String> { continuation ->
                languageId.identifyLanguage(clean)
                    .addOnSuccessListener { continuation.resume(it) }
                    .addOnFailureListener { continuation.resumeWithException(it) }
            }

            if (languageTag == "und" || languageTag == "pt" || languageTag == "pt-BR") return text
            val source = TranslateLanguage.fromLanguageTag(languageTag) ?: return text
            if (source == TranslateLanguage.PORTUGUESE) return text

            val options = TranslatorOptions.Builder()
                .setSourceLanguage(source)
                .setTargetLanguage(TranslateLanguage.PORTUGUESE)
                .build()
            val translator = Translation.getClient(options)
            try {
                await<Unit> { continuation ->
                    translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
                        .addOnSuccessListener { continuation.resume(Unit) }
                        .addOnFailureListener { continuation.resumeWithException(it) }
                }
                await<String> { continuation ->
                    translator.translate(clean)
                        .addOnSuccessListener { continuation.resume(it) }
                        .addOnFailureListener { continuation.resumeWithException(it) }
                }
            } finally {
                translator.close()
            }
        } catch (_: Exception) {
            text
        } finally {
            languageId.close()
        }
    }

    private suspend fun <T> await(register: (kotlin.coroutines.Continuation<T>) -> Unit): T =
        suspendCancellableCoroutine { continuation -> register(continuation) }
}
