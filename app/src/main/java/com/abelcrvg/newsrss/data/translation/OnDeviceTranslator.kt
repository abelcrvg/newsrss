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
 * English -> Brazilian Portuguese translation for English sources, entirely on-device.
 *
 * ML Kit is useful as the translation engine, but it is intentionally wrapped here by
 * a small PT-BR editorial layer: glossary terms are protected before translation and
 * common machine-translation constructions are normalized afterwards.
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
     * Translates several blocks together so ML Kit gets more context, while keeping
     * each block independently addressable. The glossary is applied on both sides of
     * the model and the fallback path uses the protected text as well.
     */
    private suspend fun translateContext(parts: List<String>): List<String> {
        if (parts.isEmpty()) return emptyList()
        val protected = parts.map { protectTerms(it) }
        val translated: List<String> = if (protected.size == 1) {
            listOf(translateText(protected[0].text))
        } else {
            val payload = protected.mapIndexed { index, part ->
                "$MARKER$index]\n${part.text}"
            }.joinToString("\n\n")

            val translatedPayload = runCatching { translateText(payload) }.getOrNull()
            if (translatedPayload == null) {
                protected.map { translateText(it.text) }
            } else {
                val regex = Regex(
                    "(?s)${Regex.escape(MARKER)}(\\d+)]\\s*\\n(.*?)(?=\\n\\n${Regex.escape(MARKER)}\\d+]\\s*\\n|$)"
                )
                val parsed = regex.findAll(translatedPayload)
                    .associate { it.groupValues[1].toInt() to it.groupValues[2].trim() }
                if (parsed.size == parts.size && parsed.keys == parts.indices.toSet()) {
                    parts.indices.map { parsed[it].orEmpty() }
                } else {
                    protected.map { translateText(it.text) }
                }
            }
        }

        return translated.mapIndexed { index, value ->
            restoreTerms(normalizePortuguese(value), protected[index].replacements)
        }
    }

    /** Protect names and high-value journalistic vocabulary from literal translation. */
    private fun protectTerms(text: String): ProtectedText {
        var result = text
        val replacements = linkedMapOf<String, String>()
        val terms = (TEAM_NAMES + FOOTBALL_TERMS + COMMON_PROPER_NAMES)
            .sortedByDescending { it.length }

        terms.forEachIndexed { index, term ->
            val token = "NEWSRSS_TERM_${index}_X"
            val regex = Regex(
                "(?<![\\p{L}\\p{N}])${Regex.escape(term)}(?![\\p{L}\\p{N}])",
                RegexOption.IGNORE_CASE
            )
            if (regex.containsMatchIn(result)) {
                result = regex.replace(result) { match ->
                    replacements[token] = match.value
                    token
                }
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

    /**
     * Small PT-BR editorial cleanup. These are deliberately conservative: the goal is
     * to fix recurring machine-translation artifacts, not to rewrite arbitrary prose.
     */
    private fun normalizePortuguese(text: String): String {
        var result = text.trim()
        val replacements = listOf(
            Regex("\\bjanela de transferências\\b", RegexOption.IGNORE_CASE) to "janela de transferências",
            Regex("\\bmercado de transferências\\b", RegexOption.IGNORE_CASE) to "mercado da bola",
            Regex("\\bpartida de futebol\\b", RegexOption.IGNORE_CASE) to "partida",
            Regex("\\bjogo de futebol\\b", RegexOption.IGNORE_CASE) to "jogo",
            Regex("\\btreinador-chefe\\b", RegexOption.IGNORE_CASE) to "técnico",
            Regex("\\bgerente\\s+(?:do|de)\\s+futebol\\b", RegexOption.IGNORE_CASE) to "técnico",
            Regex("\\bgerente\\b", RegexOption.IGNORE_CASE) to "técnico",
            Regex("\\bfixture\\b", RegexOption.IGNORE_CASE) to "calendário de jogos",
            Regex("\\bfixtures\\b", RegexOption.IGNORE_CASE) to "calendário de jogos",
            Regex("\\bmanager\\b", RegexOption.IGNORE_CASE) to "técnico",
            Regex("\\bhead coach\\b", RegexOption.IGNORE_CASE) to "técnico",
            Regex("\\btransfer window\\b", RegexOption.IGNORE_CASE) to "janela de transferências",
            Regex("\\btransfer market\\b", RegexOption.IGNORE_CASE) to "mercado de transferências",
            Regex("\\bfree agent\\b", RegexOption.IGNORE_CASE) to "jogador livre",
            Regex("\\bloan deal\\b", RegexOption.IGNORE_CASE) to "empréstimo",
            Regex("\\bloan move\\b", RegexOption.IGNORE_CASE) to "empréstimo",
            Regex("\\bstarting XI\\b", RegexOption.IGNORE_CASE) to "time titular",
            Regex("\\bstarting lineup\\b", RegexOption.IGNORE_CASE) to "time titular",
            Regex("\\blineup\\b", RegexOption.IGNORE_CASE) to "escalação",
            Regex("\\bknockout stage\\b", RegexOption.IGNORE_CASE) to "fase eliminatória",
            Regex("\\bgroup stage\\b", RegexOption.IGNORE_CASE) to "fase de grupos",
            Regex("\\bquarter-final\\b", RegexOption.IGNORE_CASE) to "quartas de final",
            Regex("\\bsemi-final\\b", RegexOption.IGNORE_CASE) to "semifinal",
            Regex("\\bfinals\\b", RegexOption.IGNORE_CASE) to "finais",
            Regex("\\btop four\\b", RegexOption.IGNORE_CASE) to "G-4",
            Regex("\\btitle race\\b", RegexOption.IGNORE_CASE) to "disputa pelo título",
            Regex("\\brelegation battle\\b", RegexOption.IGNORE_CASE) to "luta contra o rebaixamento",
            Regex("\\bmatchday\\b", RegexOption.IGNORE_CASE) to "rodada",
            Regex("\\bkick-off\\b", RegexOption.IGNORE_CASE) to "início da partida",
            Regex("\\bkickoff\\b", RegexOption.IGNORE_CASE) to "início da partida",
            Regex("\\bfull-time\\b", RegexOption.IGNORE_CASE) to "fim de jogo",
            Regex("\\bhalf-time\\b", RegexOption.IGNORE_CASE) to "intervalo",
            Regex("\\bclean sheet\\b", RegexOption.IGNORE_CASE) to "sem sofrer gols",
            Regex("\\bown goal\\b", RegexOption.IGNORE_CASE) to "gol contra",
            Regex("\\bpenalty shootout\\b", RegexOption.IGNORE_CASE) to "disputa de pênaltis",
            Regex("\\bassist\\b", RegexOption.IGNORE_CASE) to "assistência",
            Regex("\\bgoalkeeper\\b", RegexOption.IGNORE_CASE) to "goleiro",
            Regex("\\bdefender\\b", RegexOption.IGNORE_CASE) to "defensor",
            Regex("\\bmidfielder\\b", RegexOption.IGNORE_CASE) to "meia",
            Regex("\\bstriker\\b", RegexOption.IGNORE_CASE) to "atacante",
            Regex("\\bwinger\\b", RegexOption.IGNORE_CASE) to "ponta",
            Regex("\\bclub\\b", RegexOption.IGNORE_CASE) to "clube",
            Regex("\\bfootball club\\b", RegexOption.IGNORE_CASE) to "clube",
            Regex("\\bwill face\\b", RegexOption.IGNORE_CASE) to "vai enfrentar",
            Regex("\\bfaced\\b", RegexOption.IGNORE_CASE) to "enfrentou",
            Regex("\\bhas joined\\b", RegexOption.IGNORE_CASE) to "assinou com",
            Regex("\\bjoined\\b", RegexOption.IGNORE_CASE) to "assinou com",
            Regex("\\bset to join\\b", RegexOption.IGNORE_CASE) to "perto de acertar com",
            Regex("\\bset to leave\\b", RegexOption.IGNORE_CASE) to "perto de deixar",
            Regex("\\breportedly\\b", RegexOption.IGNORE_CASE) to "segundo relatos",
            Regex("\\breports claim\\b", RegexOption.IGNORE_CASE) to "segundo relatos",
            Regex("\\breports suggest\\b", RegexOption.IGNORE_CASE) to "segundo relatos",
            Regex("\\bin a statement\\b", RegexOption.IGNORE_CASE) to "em comunicado",
            Regex("\\baccording to reports\\b", RegexOption.IGNORE_CASE) to "segundo relatos",
            Regex("\\bhas been ruled out\\b", RegexOption.IGNORE_CASE) to "está fora",
            Regex("\\bruled out\\b", RegexOption.IGNORE_CASE) to "fora",
            Regex("\\bwill miss the match\\b", RegexOption.IGNORE_CASE) to "vai desfalcar o time",
            Regex("\\bmiss the game\\b", RegexOption.IGNORE_CASE) to "desfalcará o time",
            Regex("\\bback in training\\b", RegexOption.IGNORE_CASE) to "de volta aos treinos",
            Regex("\\btraining session\\b", RegexOption.IGNORE_CASE) to "treino",
            Regex("\\bmedical tests\\b", RegexOption.IGNORE_CASE) to "exames médicos",
            Regex("\\bmedical examination\\b", RegexOption.IGNORE_CASE) to "exames médicos",
            Regex("\\bdeal agreed\\b", RegexOption.IGNORE_CASE) to "acordo fechado",
            Regex("\\bpersonal terms\\b", RegexOption.IGNORE_CASE) to "termos pessoais",
            Regex("\\bcontract extension\\b", RegexOption.IGNORE_CASE) to "renovação de contrato",
            Regex("\\bnew contract\\b", RegexOption.IGNORE_CASE) to "novo contrato"
        )
        replacements.forEach { (pattern, replacement) ->
            result = pattern.replace(result, replacement)
        }

        result = result
            .replace(Regex("\\s+([,.;:!?])"), "$1")
            .replace(Regex(" {2,}"), " ")
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

        val FOOTBALL_TERMS = setOf(
            "Premier League", "Champions League", "Europa League", "Europa Conference League",
            "FA Cup", "Carabao Cup", "Club World Cup", "World Cup", "World Cup 2026",
            "Copa Libertadores", "Copa Sudamericana", "Brasileirão", "Brazilian Serie A",
            "Serie A", "Serie B", "La Liga", "Bundesliga", "Serie A", "Ligue 1",
            "MLS", "FIFA", "UEFA", "CONMEBOL", "VAR", "Ballon d'Or", "Golden Boot",
            "PFA", "FIFPro", "transfer window", "transfer market"
        )

        val COMMON_PROPER_NAMES = setOf(
            "Sky Sports", "ESPN", "TNT Sports", "The Athletic", "Manchester", "Liverpool", "London",
            "New York", "Los Angeles", "United Kingdom", "United States", "Brazil", "England", "Scotland",
            "Wales", "Northern Ireland", "European Union"
        )

        // Common football clubs and national teams. Names are preserved exactly as found in the source.
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
