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
 *
 * ML Kit remains the translation engine, but the input/output is treated as an
 * editorial pipeline: entities and football expressions are protected, text is
 * translated in small contextual groups, recurring literal constructions are
 * normalized, and suspicious output is rejected instead of being shown as if it
 * were a good translation.
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
                    result[index] = block.copy(items = block.items.map { translateSingle(it) })
                }
                else -> Unit
            }
        }
        return result
    }

    /** Translate several related pieces together without losing their boundaries. */
    private suspend fun translateContext(parts: List<String>): List<String> {
        if (parts.isEmpty()) return emptyList()

        val protected = parts.map(::protectTerms)
        val translated = if (protected.size == 1) {
            listOf(translateProtected(protected[0]))
        } else {
            val payload = protected.mapIndexed { index, part ->
                "$MARKER$index$MARKER_END\n${part.text}"
            }.joinToString("\n\n")

            val contextualResult = runCatching { translateText(payload) }.getOrNull()
            val parsed = contextualResult?.let { parseContextResult(it, parts.size) }

            if (parsed != null && parsed.all { isAcceptableTranslation(parts[it.first], it.second) }) {
                parsed.map { it.second }
            } else {
                // Important: keep the protected text on fallback. The old implementation
                // could fall back to the raw English text and lose entity protection.
                protected.map(::translateProtected)
            }
        }

        return translated.mapIndexed { index, value ->
            val restored = restoreTerms(value, protected[index].replacements)
            val cleaned = normalizePortuguese(restored)
            if (isAcceptableTranslation(parts[index], cleaned)) cleaned else parts[index]
        }
    }

    private suspend fun translateProtected(part: ProtectedText): String {
        val translated = translateText(part.text)
        return restoreTerms(normalizePortuguese(translated), part.replacements)
    }

    private fun parseContextResult(text: String, expected: Int): List<Pair<Int, String>>? {
        val regex = Regex(
            "(?s)${Regex.escape(MARKER)}(\\d+)${Regex.escape(MARKER_END)}\\s*\\n(.*?)(?=\\n\\n${Regex.escape(MARKER)}\\d+${Regex.escape(MARKER_END)}\\s*\\n|$)"
        )
        val parsed = regex.findAll(text)
            .mapNotNull { match ->
                val index = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                index to match.groupValues[2].trim()
            }
            .toList()

        if (parsed.size != expected || parsed.map { it.first }.toSet() != (0 until expected).toSet()) {
            return null
        }
        return parsed.sortedBy { it.first }
    }

    /** Protect names, competitions and football collocations before ML Kit sees them. */
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
            if (!regex.containsMatchIn(result)) return@forEach

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
        // If ML Kit inserted spaces around a placeholder, remove only those spaces.
        replacements.keys.forEach { token ->
            result = result.replace(Regex("\\s+$token\\s+", RegexOption.IGNORE_CASE), token)
        }
        return result
    }

    /**
     * Converts recurring literal English constructions into natural Brazilian
     * football journalism. This runs after entity restoration so names remain intact.
     */
    private fun normalizePortuguese(text: String): String {
        var result = text.trim()

        val replacements = listOf(
            Regex("\\b(\u00e0s|no|em)\\s+([0-9]{1,2})º\\s+dia\\s+de\\s+([a-z\u00e1-\u00fa]+)\\b", RegexOption.IGNORE_CASE) to "$1 $2 de $3",
            Regex("\\b(?:no|em)\\s+([0-9]{1,2})º\\s+dia\\s+de\\s+", RegexOption.IGNORE_CASE) to "em $1 de ",
            Regex("\\bvira\\s+(\\d{2})\\s+anos\\b", RegexOption.IGNORE_CASE) to "completa $1 anos",
            Regex("\\bvirou\\s+(\\d{2})\\s+anos\\b", RegexOption.IGNORE_CASE) to "completou $1 anos",
            Regex("\\bfez\\s+(\\d{2})\\s+anos\\b", RegexOption.IGNORE_CASE) to "completou $1 anos",
            Regex("\\bjogou\\s+todo\\s+o\\s+jogo\\b", RegexOption.IGNORE_CASE) to "jogou a partida inteira",
            Regex("\\bjogou\\s+todo\\s+o\\s+jogo\\s+\\b", RegexOption.IGNORE_CASE) to "jogou a partida inteira",
            Regex("\\bjogou\\s+os\\s+90\\s+minutos\\s+completos\\b", RegexOption.IGNORE_CASE) to "jogou os 90 minutos",
            Regex("\\b90\\s+minutos\\s+completos\\b", RegexOption.IGNORE_CASE) to "90 minutos",
            Regex("\\bperna\\s+de\\s+dist\\u00e2ncia\\b", RegexOption.IGNORE_CASE) to "vantagem",
            Regex("\\bcomo\\s+uma\\s+decora\\u00e7\\u00e3o\\b", RegexOption.IGNORE_CASE) to "na vit\u00f3ria",
            Regex("\\bdecurso\\s+de\\s+uma\\s+vit\u00f3ria\\b", RegexOption.IGNORE_CASE) to "durante a vit\u00f3ria",
            Regex("\\bpartida\\s+como\\s+uma\\s+decora\\u00e7\\u00e3o\\b", RegexOption.IGNORE_CASE) to "partida na vit\u00f3ria",
            Regex("\\bde\\s+trinta\\s+para\\s+", RegexOption.IGNORE_CASE) to "por 3 a 0 sobre ",
            Regex("\\buma\\s+vit\u00f3ria\\s+de\\s+trinta\\s+para\\s+", RegexOption.IGNORE_CASE) to "uma vit\u00f3ria por 3 a 0 sobre ",
            Regex("\\bjogou\\s+seu\\s+\u00faltimo\\s+jogo\\b", RegexOption.IGNORE_CASE) to "disputou sua \u00faltima partida",
            Regex("\\bseu\\s+\u00faltimo\\s+jogo\\b", RegexOption.IGNORE_CASE) to "sua \u00faltima partida",
            Regex("\\bdurou\\s+a\\s+dist\u00e2ncia\\b", RegexOption.IGNORE_CASE) to "tamb\u00e9m jogou",
            Regex("\\bassistiu\\s+ao\\s+jogo\\b", RegexOption.IGNORE_CASE) to "acompanhou a partida",
            Regex("\\bmercado\\s+de\\s+transfer\u00eancias\\b", RegexOption.IGNORE_CASE) to "mercado de transfer\u00eancias",
            Regex("\\bjanela\\s+de\\s+transfer\u00eancias\\b", RegexOption.IGNORE_CASE) to "janela de transfer\u00eancias",
            Regex("\\btreinador[- ]chefe\\b", RegexOption.IGNORE_CASE) to "t\u00e9cnico",
            Regex("\\bhead\\s+coach\\b", RegexOption.IGNORE_CASE) to "t\u00e9cnico",
            Regex("\\bmanager\\b", RegexOption.IGNORE_CASE) to "t\u00e9cnico",
            Regex("\\bgerente\\b", RegexOption.IGNORE_CASE) to "t\u00e9cnico",
            Regex("\\bline[- ]up\\b", RegexOption.IGNORE_CASE) to "escala\u00e7\u00e3o",
            Regex("\\bstarting\\s+XI\\b", RegexOption.IGNORE_CASE) to "time titular",
            Regex("\\bstarting\\s+lineup\\b", RegexOption.IGNORE_CASE) to "time titular",
            Regex("\\bfull[- ]time\\b", RegexOption.IGNORE_CASE) to "fim de jogo",
            Regex("\\bhalf[- ]time\\b", RegexOption.IGNORE_CASE) to "intervalo",
            Regex("\\bclean\\s+sheet\\b", RegexOption.IGNORE_CASE) to "sem sofrer gols",
            Regex("\\bown\\s+goal\\b", RegexOption.IGNORE_CASE) to "gol contra",
            Regex("\\bpenalty\\s+shootout\\b", RegexOption.IGNORE_CASE) to "disputa de p\u00eanaltis",
            Regex("\\btitle\\s+race\\b", RegexOption.IGNORE_CASE) to "disputa pelo t\u00edtulo",
            Regex("\\brelegation\\s+battle\\b", RegexOption.IGNORE_CASE) to "luta contra o rebaixamento",
            Regex("\\bmatchday\\b", RegexOption.IGNORE_CASE) to "rodada",
            Regex("\\bfixture[s]?\\b", RegexOption.IGNORE_CASE) to "partida",
            Regex("\\bfree\\s+agent\\b", RegexOption.IGNORE_CASE) to "jogador livre",
            Regex("\\bloan\\s+(?:deal|move)\\b", RegexOption.IGNORE_CASE) to "empr\u00e9stimo",
            Regex("\\bknockout\\s+stage\\b", RegexOption.IGNORE_CASE) to "fase eliminat\u00f3ria",
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
            Regex("\\bhas\\s+been\\s+ruled\\s+out\\b", RegexOption.IGNORE_CASE) to "est\u00e1 fora",
            Regex("\\bruled\\s+out\\b", RegexOption.IGNORE_CASE) to "fora",
            Regex("\\bwill\\s+miss\\s+the\\s+match\\b", RegexOption.IGNORE_CASE) to "vai desfalcar o time",
            Regex("\\bback\\s+in\\s+training\\b", RegexOption.IGNORE_CASE) to "de volta aos treinos",
            Regex("\\btraining\\s+session\\b", RegexOption.IGNORE_CASE) to "treino",
            Regex("\\bmedical\\s+(?:tests|examination)\\b", RegexOption.IGNORE_CASE) to "exames m\u00e9dicos",
            Regex("\\bdeal\\s+agreed\\b", RegexOption.IGNORE_CASE) to "acordo fechado",
            Regex("\\bcontract\\s+extension\\b", RegexOption.IGNORE_CASE) to "renova\u00e7\u00e3o de contrato"
        )

        replacements.forEach { (pattern, replacement) ->
            result = pattern.replace(result, replacement)
        }

        // Normalize common ML Kit punctuation/spacing artifacts.
        result = result
            .replace(Regex("\\s+([,.;:!?])"), "$1")
            .replace(Regex("([.!?])([A-Za-z\u00c0-\u00ff])"), "$1 $2")
            .replace(Regex(" {2,}"), " ")
            .replace(Regex("\\s+\\n"), "\\n")
            .replace(Regex("\\n\\s+"), "\\n")
            .trim()

        return result
    }

    /** Reject obvious garbage instead of silently presenting a broken translation. */
    private fun isAcceptableTranslation(original: String, translated: String): Boolean {
        if (original.isBlank()) return true
        if (translated.isBlank()) return false
        if (translated.contains("ZXNEWS", ignoreCase = true)) return false
        if (translated.contains("###NEWSRSS", ignoreCase = true)) return false

        val suspicious = listOf(
            "perna de distância",
            "oitavo dia de",
            "décima primeira hora",
            "como uma decoração",
            "jogou todo o jogo",
            "vira ",
            "durou a distância"
        )
        if (suspicious.any { translated.contains(it, ignoreCase = true) }) return false

        val sourceWords = original.trim().split(Regex("\\s+")).size
        val targetWords = translated.trim().split(Regex("\\s+")).size
        if (sourceWords >= 12 && targetWords < (sourceWords * 0.28).toInt()) return false
        if (sourceWords >= 20 && targetWords > sourceWords * 2.8) return false

        // ML Kit occasionally returns the English input almost unchanged. A few
        // preserved proper nouns are expected, but a long identical sentence is not.
        val normalizedSource = original.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), "").trim()
        val normalizedTarget = translated.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), "").trim()
        if (normalizedSource.length >= 60 && normalizedSource == normalizedTarget) return false

        return true
    }

    private suspend fun translateSingle(text: String): String {
        if (text.isBlank() || text.trim().length < 3) return text
        return try {
            val protected = protectTerms(text)
            val translated = translateProtected(protected)
            if (isAcceptableTranslation(text, translated)) translated else text
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
            "knockout stage", "group stage", "quarter-final", "semi-final", "top four",
            "Premier League", "Champions League", "Europa League", "Europa Conference League",
            "FA Cup", "Carabao Cup", "Club World Cup", "World Cup", "World Cup 2026",
            "Copa Libertadores", "Copa Sudamericana", "Brasileirão", "Brazilian Serie A",
            "La Liga", "Bundesliga", "Ligue 1", "MLS", "Ballon d'Or", "Golden Boot"
        )

        val COMPETITIONS = setOf(
            "Premier League", "Champions League", "UEFA Champions League", "Europa League",
            "Europa Conference League", "FA Cup", "Carabao Cup", "Club World Cup", "World Cup",
            "Copa Libertadores", "Copa Sudamericana", "Brasileirão", "Brazilian Serie A",
            "Serie A", "Serie B", "La Liga", "Bundesliga", "Ligue 1", "MLS", "FIFA", "UEFA",
            "CONMEBOL", "PFA", "FIFPro"
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
