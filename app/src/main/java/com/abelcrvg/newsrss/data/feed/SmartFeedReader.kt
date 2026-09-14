package com.abelcrvg.newsrss.data.feed

import android.content.Context
import com.abelcrvg.newsrss.NewsRssApplication
import com.abelcrvg.newsrss.core.feed.FeedItem
import com.abelcrvg.newsrss.core.feed.FeedReader
import com.abelcrvg.newsrss.core.model.FeedSource
import com.abelcrvg.newsrss.core.model.SourceLanguage
import com.abelcrvg.newsrss.data.translation.OnDeviceTranslator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** High-level feed reader: strategy selection is isolated in SourceReaderRegistry. */
class SmartFeedReader(
    private val translationContext: Context? = runCatching { NewsRssApplication.appContext }.getOrNull(),
    private val sourceReaders: SourceReaderRegistry = SourceReaderRegistry()
) : FeedReader {
    override suspend fun read(source: FeedSource): Result<List<FeedItem>> = withContext(Dispatchers.IO) {
        val result = sourceReaders.read(source)
        val filtered = if (source.id == "trivela") filterTrivelaBettingContent(result) else result
        translateEnglishItems(source, filtered)
    }

    private suspend fun translateEnglishItems(source: FeedSource, result: Result<List<FeedItem>>): Result<List<FeedItem>> {
        val context = translationContext ?: return result
        if (source.language != SourceLanguage.ENGLISH || result.isFailure) return result
        val items = result.getOrNull().orEmpty()
        val targets = items.filter(::looksEnglish).take(MAX_ENGLISH_ITEMS)
        if (targets.isEmpty()) return result
        return runCatching {
            val translated = OnDeviceTranslator(context.applicationContext).translateFeedItems(targets)
            val byUrl = translated.associateBy { it.url }
            Result.success(items.map { byUrl[it.url] ?: it })
        }.getOrElse { result }
    }

    private fun looksEnglish(item: FeedItem): Boolean {
        val text = "${item.title} ${item.summary.orEmpty()}".lowercase()
        val englishSignals = listOf(" the ", " and ", " of ", " to ", " in ", " for ", " with ", " from ", " has ", " have ", " will ", " on ", " at ", " is ", " are ", " this ", " that ", " after ", " before ", " latest ", " news ", " report ")
        val portugueseSignals = listOf(" o ", " a ", " os ", " as ", " de ", " do ", " da ", " dos ", " das ", " para ", " com ", " que ", " em ", " no ", " na ", " uma ", " um ", " não ", " está ", " sobre ")
        return englishSignals.count(text::contains) >= 2 && englishSignals.count(text::contains) > portugueseSignals.count(text::contains)
    }

    private fun filterTrivelaBettingContent(result: Result<List<FeedItem>>): Result<List<FeedItem>> = result.map { items -> items.filterNot(::isBettingFocusedTrivelaArticle) }

    private fun isBettingFocusedTrivelaArticle(item: FeedItem): Boolean {
        val title = normalize(item.title)
        val summary = normalize(item.summary.orEmpty())
        val strong = listOf("casa de apostas", "casas de apostas", "apostas esportivas", "aposta esportiva", "apostadores", "apostador", "betting", "bookmaker", "odds", "cotacao das apostas", "cotacoes das apostas", "palpites", "prognostico", "prognosticos", "bonus de aposta", "bonus das casas", "melhores casas", "onde apostar", "como apostar", "cassino", "casino", "bet365", "betano", "sportingbet", "superbet", "novibet", "kto", "pixbet", "estrelabet")
        if (strong.any(title::contains)) return true
        val signals = listOf("apostas", "apostar", "apostadores", "odds", "betting", "bookmaker", "palpites", "prognostico", "prognosticos", "casa de apostas", "casas de apostas", "cassino", "casino", "bet365", "betano", "sportingbet", "superbet", "novibet", "kto", "pixbet", "estrelabet", "bonus de aposta")
        return signals.count { summary.contains(it) } >= 2
    }

    private fun normalize(value: String): String = value.lowercase().replace("á", "a").replace("à", "a").replace("ã", "a").replace("â", "a").replace("é", "e").replace("ê", "e").replace("í", "i").replace("ó", "o").replace("ô", "o").replace("õ", "o").replace("ú", "u").replace("ç", "c")
    private companion object { const val MAX_ENGLISH_ITEMS = 20 }
}
