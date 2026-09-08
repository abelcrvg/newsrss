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

/**
 * English -> Brazilian Portuguese translation performed entirely on-device.
 * ML Kit is wrapped by a small football-editorial layer that protects entities,
 * translates related text together and rejects obvious machine-translation garbage.
 */
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
                item.copy(
                    title = translated.getOrNull(0) ?: item.title,
                    summary = item.summary?.let { translated.getOrNull(1) ?: it }
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
            val header = buildList {
                add(article.title)
                article.subtitle?.takeIf { it.isNotBlank() }?.let(::add)
            }
            val translatedHeader = translateContext(header)
            article.copy(
                title = translatedHeader.getOrNull(0) ?: article.title,
                subtitle = article.subtitle?.let { translatedHeader.getOrNull(1) ?: it },
                author = article.author,
                blocks = translateBodyBlocks(article.blocks)
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
                val value = translated.getOrNull(position) ?: part.text
                result[part.index] = when (val block = result[part.index]) {
                    is ArticleBlock.Paragraph -> block.copy(
                        text = AnnotatedString(value),
                        inlineHtml = null
                    )
                    is ArticleBlock.Heading -> block.copy(text = value)
                    is ArticleBlock.Quote -> block.copy(text = value)
                    else -> block
                }
            }
        }

        blocks.forEachIndexed { index, block ->
            when (block) {
                is ArticleBlock.Image -> {
                    val caption = block.caption?.let { translateSingle(it) }
                    val alt = block.altText?.let { translateSingle(it) }
                    result[index] = block.copy(caption = caption, altText = alt)
                }
                is ArticleBlock.ListBlock -> {
                    val translatedItems = ArrayList<String>(block.items.size)
                    for (item in block.items) {
                        translatedItems += translateSingle(item)
                    }
                    result[index] = block.copy(items = translatedItems)
                }
                else -> Unit
            }
        }
        return result
    }

    private suspend fun translateContext(parts: List<String>): List<String> {
        if (parts.isEmpty()) return emptyList()
        val protected = parts.map { protectTerms(it) }

        if (protected.size == 1) {
            return listOf(translateProtected(protected[0], parts[0]))
        }

        val payload = protected.mapIndexed { index, part ->
            "$MARKER$index$MARKER_END\n${part.text}"
        }.joinToString("\n\n")

        val contextualResult = runCatching { translateText(payload) }.getOrNull()
        val parsed = contextualResult?.let { parseContextResult(it, parts.size) }

        val translated = if (parsed != null && parsed.all { isAcceptableTranslation(parts[it.first], it.second) }) {
            parsed.map { it.second }
        } else {
            val fallback = ArrayList<String>(protected.size)
            for (index in protected.indices) {
                fallback += translateProtected(protected[index], parts[index])
            }
            fallback
        }

        return translated.mapIndexed { index, value ->
            val restored = restoreTerms(value, protected[index].replacements)
            val cleaned = normalizePortuguese(restored)
            if (isAcceptableTranslation(parts[index], cleaned)) cleaned else parts[index]
        }
    }

    private suspend fun translateProtected(part: ProtectedText, original: String): String {
        val translated = translateText(part.text)
        val restored = restoreTerms(translated, part.replacements)
        val cleaned = normalizePortuguese(restored)
        return if (isAcceptableTranslation(original, cleaned)) cleaned else original
    }

    private fun parseContextResult(text: String, expected: Int): List<Pair<Int, String>>? {
        val regex = Regex(
            "(?s)${Regex.escape(MARKER)}(\\d+)${Regex.escape(MARKER_END)}\\s*\\n(.*?)(?=\\n\\n${Regex.escape(MARKER)}\\d+${Regex.escape(MARKER_END)}\\s*\\n|$)"
        )
        val parsed = regex.findAll(text).mapNotNull { match ->
            val index = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            index to match.groupValues[2].trim()
        }.toList()
        if (parsed.size != expected || parsed.map { it.first }.toSet() != (0 until expected).toSet()) return null
        return parsed.sortedBy { it.first }
    }

    private fun protectTerms(text: String): ProtectedText {
        var result = text
        val replacements = linkedMapOf<String, String>()
        val terms = (PROTECTED_PHRASES + TEAM_NAMES + COMPETITIONS + PROPER_NAMES)
            .distinct()
            .sortedByDescending { it.length }

        terms.forEach { term ->
            val regex = Regex(
                "(?<![\\p{L}\\p{N}])${Regex.escape(term)}(?![\\p{L}\\p{N}])",
                RegexOption.IGNORE_CASE
            )
            result = regex.replace(result) { match ->
                val token = "ZXNEWS${replacements.size}Q"
                replacements[token] = match.value
                token
            }
        }
        return ProtectedText(result, replacements)
    }

    private fun restoreTerms(text: String, replacements: Map<String, String>): String {
        var result = text
        replacements.forEach { (token, original) ->
            result = result.replace(token, original, ignoreCase = true)
        }
        return result
    }

    private fun normalizePortuguese(text: String): String {
        var result = text.trim()
        val replacements = listOf(
            Regex("\\bvira\\s+(\\d{2})\\s+anos\\b", RegexOption.IGNORE_CASE) to "completa $1 anos",
            Regex("\\bvirou\\s+(\\d{2})\\s+anos\\b", RegexOption.IGNORE_CASE) to "completou $1 anos",
            Regex("\\bfez\\s+(\\d{2})\\s+anos\\b", RegexOption.IGNORE_CASE) to "completou $1 anos",
            Regex("\\bjogou\\s+todo\\s+o\\s+jogo\\b", RegexOption.IGNORE_CASE) to "jogou a partida inteira",
            Regex("\\bjogou\\s+os\\s+90\\s+minutos\\s+completos\\b", RegexOption.IGNORE_CASE) to "jogou os 90 minutos",
            Regex("\\b90\\s+minutos\\s+completos\\b", RegexOption.IGNORE_CASE) to "90 minutos",
            Regex("\\bperna\\s+de\\s+distância\\b", RegexOption.IGNORE_CASE) to "vantagem",
            Regex("\\bcomo\\s+uma\\s+decoração\\b", RegexOption.IGNORE_CASE) to "na vitória",
            Regex("\\bjogou\\s+seu\\s+último\\s+jogo\\b", RegexOption.IGNORE_CASE) to "disputou sua última partida",
            Regex("\\bseu\\s+último\\s+jogo\\b", RegexOption.IGNORE_CASE) to "sua última partida",
            Regex("\\bdurou\\s+a\\s+distância\\b", RegexOption.IGNORE_CASE) to "também jogou",
            Regex("\\btreinador[- ]chefe\\b", RegexOption.IGNORE_CASE) to "técnico",
            Regex("\\bhead\\s+coach\\b", RegexOption.IGNORE_CASE) to "técnico",
            Regex("\\bmanager\\b", RegexOption.IGNORE_CASE) to "técnico",
            Regex("\\bgerente\\b", RegexOption.IGNORE_CASE) to "técnico",
            Regex("\\bline[- ]up\\b", RegexOption.IGNORE_CASE) to "escalação",
            Regex("\\bstarting\\s+XI\\b", RegexOption.IGNORE_CASE) to "time titular",
            Regex("\\bstarting\\s+lineup\\b", RegexOption.IGNORE_CASE) to "time titular",
            Regex("\\bclean\\s+sheet\\b", RegexOption.IGNORE_CASE) to "sem sofrer gols",
            Regex("\\bown\\s+goal\\b", RegexOption.IGNORE_CASE) to "gol contra",
            Regex("\\bpenalty\\s+shootout\\b", RegexOption.IGNORE_CASE) to "disputa de pênaltis",
            Regex("\\btitle\\s+race\\b", RegexOption.IGNORE_CASE) to "disputa pelo título",
            Regex("\\brelegation\\s+battle\\b", RegexOption.IGNORE_CASE) to "luta contra o rebaixamento",
            Regex("\\bmatchday\\b", RegexOption.IGNORE_CASE) to "rodada",
            Regex("\\bfixture[s]?\\b", RegexOption.IGNORE_CASE) to "partida",
            Regex("\\bfree\\s+agent\\b", RegexOption.IGNORE_CASE) to "jogador livre",
            Regex("\\bloan\\s+(?:deal|move)\\b", RegexOption.IGNORE_CASE) to "empréstimo",
            Regex("\\bknockout\\s+stage\\b", RegexOption.IGNORE_CASE) to "fase eliminatória",
            Regex("\\bgroup\\s+stage\\b", RegexOption.IGNORE_CASE) to "fase de grupos",
            Regex("\\bquarter[- ]final[s]?\\b", RegexOption.IGNORE_CASE) to "quartas de final",
            Regex("\\bsemi[- ]final[s]?\\b", RegexOption.IGNORE_CASE) to "semifinal",
            Regex("\\btop\\s+four\\b", RegexOption.IGNORE_CASE) to "G-4",
            Regex("\\bwill\\s+face\\b", RegexOption.IGNORE_CASE) to "vai enfrentar",
            Regex("\\bhas\\s+joined\\b", RegexOption.IGNORE_CASE) to "assinou com",
            Regex("\\bset\\s+to\\s+join\\b", RegexOption.IGNORE_CASE) to "perto de acertar com",
            Regex("\\bset\\s+to\\s+leave\\b", RegexOption.IGNORE_CASE) to "perto de deixar",
            Regex("\\breportedly\\b", RegexOption.IGNORE_CASE) to "segundo relatos",
            Regex("\\bin\\s+a\\s+statement\\b", RegexOption.IGNORE_CASE) to "em comunicado",
            Regex("\\baccording\\s+to\\s+reports\\b", RegexOption.IGNORE_CASE) to "segundo relatos",
            Regex("\\bhas\\s+been\\s+ruled\\s+out\\b", RegexOption.IGNORE_CASE) to "está fora",
            Regex("\\bruled\\s+out\\b", RegexOption.IGNORE_CASE) to "fora",
            Regex("\\bback\\s+in\\s+training\\b", RegexOption.IGNORE_CASE) to "de volta aos treinos",
            Regex("\\btraining\\s+session\\b", RegexOption.IGNORE_CASE) to "treino",
            Regex("\\bmedical\\s+(?:tests|examination)\\b", RegexOption.IGNORE_CASE) to "exames médicos",
            Regex("\\bcontract\\s+extension\\b", RegexOption.IGNORE_CASE) to "renovação de contrato"
        )
        replacements.forEach { (pattern, replacement) -> result = pattern.replace(result, replacement) }
        return result.replace(Regex("\\s+([,.;:!?])"), "$1").replace(Regex(" {2,}"), " ").trim()
    }

    private fun isAcceptableTranslation(original: String, translated: String): Boolean {
        if (original.isBlank()) return true
        if (translated.isBlank()) return false
        if (translated.contains("ZXNEWS", ignoreCase = true) || translated.contains("ZZNEWSBLOCK", ignoreCase = true)) return false
        val suspicious = listOf("perna de distância", "oitavo dia de", "como uma decoração", "jogou todo o jogo", "durou a distância")
        if (suspicious.any { translated.contains(it, ignoreCase = true) }) return false
        val sourceWords = original.trim().split(Regex("\\s+")).size
        val targetWords = translated.trim().split(Regex("\\s+")).size
        if (sourceWords >= 12 && targetWords < (sourceWords * 0.28).toInt()) return false
        if (sourceWords >= 20 && targetWords > sourceWords * 2.8) return false
        return true
    }

    private suspend fun translateSingle(text: String): String {
        if (text.isBlank() || text.trim().length < 3) return text
        return try {
            val protected = protectTerms(text)
            translateProtected(protected, text)
        } catch (_: Exception) {
            text
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
    private data class ProtectedText(val text: String, val replacements: Map<String, String>)

    private companion object {
        const val MARKER = "ZZNEWSBLOCK"
        const val MARKER_END = "ENDNEWS"
        const val MAX_CONTEXT_CHARS = 1800
        const val MARKER_OVERHEAD = 30

        val PROTECTED_PHRASES = setOf(
            "full 90 minutes", "full 90", "played the full game", "played the full 90",
            "came off the bench", "starting XI", "starting lineup", "clean sheet",
            "title race", "relegation battle", "transfer window", "transfer market",
            "loan deal", "loan move", "free agent", "head coach", "matchday",
            "knockout stage", "group stage", "quarter-final", "semi-final", "top four"
        )

        val COMPETITIONS = setOf(
            "Premier League", "Champions League", "UEFA Champions League", "Europa League",
            "Europa Conference League", "FA Cup", "Carabao Cup", "Club World Cup", "World Cup",
            "Copa Libertadores", "Copa Sudamericana", "Brasileirão", "Brazilian Serie A",
            "Serie A", "Serie B", "La Liga", "Bundesliga", "Ligue 1", "MLS", "FIFA", "UEFA", "CONMEBOL"
        )

        val PROPER_NAMES = setOf(
            "Sky Sports", "ESPN", "TNT Sports", "The Athletic", "Manchester", "Liverpool", "London",
            "New York", "Los Angeles", "United Kingdom", "United States", "Brazil", "England", "Scotland",
            "Wales", "Northern Ireland", "Moutinho", "Dynamo Minsk", "Dinamo Minsk", "Austria Vienna",
            "Alverca", "Braga", "Vitoria Guimaraes", "Vitória Guimarães"
        )

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
            "Brazil", "England", "France", "Germany", "Spain", "Italy", "Portugal", "Argentina", "Uruguay",
            "Colombia", "Netherlands", "Belgium", "Croatia", "Japan", "South Korea", "Mexico", "USA"
        )
    }
}
