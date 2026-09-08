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
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** English -> Brazilian Portuguese translation for English sources, entirely on-device. */
class OnDeviceTranslator(_context: Context) {
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
                    title = translated.getOrNull(0) ?: item.title,
                    summary = originalSummary?.let { original -> translated.getOrNull(1) ?: original }
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
                title = translatedHeader.getOrNull(0) ?: article.title,
                subtitle = originalSubtitle?.let { original -> translatedHeader.getOrNull(1) ?: original },
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
                val translatedText = translated.getOrNull(position) ?: part.text
                result[part.index] = when (val block = result[part.index]) {
                    is ArticleBlock.Paragraph -> block.copy(text = AnnotatedString(translatedText), inlineHtml = null)
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
                is ArticleBlock.ListBlock -> result[index] = block.copy(items = block.items.map { translateText(it) })
                else -> Unit
            }
        }
        return result
    }

    /**
     * ML Kit is a general machine translator and can translate proper football club
     * names literally (for example, Manchester United -> "Homem de Manchester").
     * Protect known club/team names with private placeholders before translation and
     * restore them afterwards. The original spelling/capitalization is preserved.
     */
    private suspend fun translateContext(parts: List<String>): List<String> {
        if (parts.isEmpty()) return emptyList()
        val protected = parts.map { protectTeamNames(it) }
        val translated = if (protected.size == 1) {
            listOf(translateText(protected[0].text))
        } else {
            val payload = protected.mapIndexed { index, part -> "$MARKER$index]\n${part.text}" }.joinToString("\n\n")
            val translatedPayload = runCatching { translateText(payload) }.getOrElse {
                return parts.map { translateText(it) }
            }
            val regex = Regex("(?s)${Regex.escape(MARKER)}(\\d+)]\\s*\\n(.*?)(?=\\n\\n${Regex.escape(MARKER)}\\d+]\\s*\\n|$)")
            val parsed = regex.findAll(translatedPayload).associate { it.groupValues[1].toInt() to it.groupValues[2].trim() }
            if (parsed.size == parts.size && parsed.keys == parts.indices.toSet()) {
                parts.indices.map { parsed[it].orEmpty() }
            } else {
                parts.map { translateText(it) }
            }
        }
        return translated.mapIndexed { index, value -> restoreTeamNames(value, protected[index].replacements) }
    }

    private fun protectTeamNames(text: String): ProtectedText {
        var result = text
        val replacements = linkedMapOf<String, String>()
        TEAM_NAMES.sortedByDescending { it.length }.forEachIndexed { index, team ->
            val token = "NEWSRSS_TEAM_${index}_X"
            val regex = Regex("(?<![\\p{L}\\p{N}])${Regex.escape(team)}(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
            if (regex.containsMatchIn(result)) {
                result = regex.replace(result) { match ->
                    replacements[token] = match.value
                    token
                }
            }
        }
        return ProtectedText(result, replacements)
    }

    private fun restoreTeamNames(text: String, replacements: Map<String, String>): String {
        var result = text
        replacements.forEach { (token, original) ->
            result = result.replace(token, original, ignoreCase = true)
        }
        return result
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
    private data class ProtectedText(val text: String, val replacements: Map<String, String>)

    private companion object {
        const val MARKER = "###NEWSRSS_BLOCK_"
        const val MAX_CONTEXT_CHARS = 3000
        const val MARKER_OVERHEAD = 32

        // Common football clubs and national teams. These are deliberately kept in
        // English/original form because they are names, not words to be translated.
        val TEAM_NAMES = setOf(
            "Manchester United", "Manchester City", "Liverpool", "Arsenal", "Chelsea", "Tottenham Hotspur",
            "Tottenham", "Newcastle United", "Aston Villa", "West Ham United", "Crystal Palace", "Everton",
            "Nottingham Forest", "Brighton", "Fulham", "Wolverhampton Wanderers", "Wolverhampton", "Brentford",
            "Bournemouth", "Leicester City", "Leeds United", "Sunderland", "Real Madrid", "Barcelona",
            "Atletico Madrid", "Atlético Madrid", "Sevilla", "Valencia", "Villarreal", "Real Sociedad",
            "Athletic Club", "Girona", "Real Betis", "Celta Vigo", "Bayern Munich", "Bayern München",
            "Borussia Dortmund", "Bayer Leverkusen", "RB Leipzig", "Eintracht Frankfurt", "VfB Stuttgart",
            "Inter Milan", "Inter", "AC Milan", "Juventus", "Napoli", "Roma", "Lazio", "Atalanta",
            "Fiorentina", "PSG", "Paris Saint-Germain", "Olympique Marseille", "Lyon", "Monaco",
            "Ajax", "PSV Eindhoven", "Feyenoord", "Benfica", "Porto", "Sporting CP", "Galatasaray",
            "Fenerbahce", "Fenerbahçe", "Celtic", "Rangers", "Club Brugge", "Anderlecht", "Shakhtar Donetsk",
            "Red Bull Salzburg", "Inter Miami", "LA Galaxy", "New York City FC", "Atlanta United", "Al Nassr",
            "Al Hilal", "Al Ahli", "Al Ittihad", "Flamengo", "Fluminense", "Vasco da Gama", "Botafogo",
            "Palmeiras", "Corinthians", "Santos", "São Paulo", "Cruzeiro", "Grêmio", "Internacional",
            "Atlético-MG", "Athletico-PR", "Bahia", "Fortaleza", "Ceará", "Sport Recife", "Bragantino",
            "Brasil", "Brazil", "England", "France", "Germany", "Spain", "Italy", "Portugal", "Argentina",
            "Uruguay", "Colombia", "Netherlands", "Belgium", "Croatia", "Japan", "South Korea", "Mexico",
            "United States", "USA"
        )
    }
}
