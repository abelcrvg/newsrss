package com.abelcrvg.newsrss.data.source

import android.content.Context
import com.abelcrvg.newsrss.core.model.FeedSource
import com.abelcrvg.newsrss.core.model.NewsCategory
import com.abelcrvg.newsrss.core.model.SourceAnalyzer
import com.abelcrvg.newsrss.core.model.SourceLanguage
import org.json.JSONArray
import org.json.JSONObject

/** Persists source settings while upgrading older entries with automatic category/language detection. */
class SourceStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(defaults: List<FeedSource>): List<FeedSource> {
        val raw = prefs.getString(KEY_SOURCES, null) ?: return defaults
        return runCatching {
            val array = JSONArray(raw)
            val stored = buildList {
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    val siteUrl = item.getString("siteUrl")
                    val inferred = SourceAnalyzer.infer(siteUrl)
                    val storedCategory = runCatching { NewsCategory.valueOf(item.optString("category")) }.getOrDefault(NewsCategory.NEWS)
                    val storedLanguage = runCatching { SourceLanguage.valueOf(item.optString("language")) }.getOrDefault(SourceLanguage.AUTO)
                    val category = if (storedCategory == NewsCategory.NEWS) inferred.category else storedCategory
                    val language = if (storedLanguage == SourceLanguage.AUTO) inferred.language else storedLanguage
                    add(
                        FeedSource(
                            id = item.getString("id"),
                            name = item.getString("name"),
                            siteUrl = siteUrl,
                            feedUrl = item.optString("feedUrl").takeIf { it.isNotBlank() },
                            category = category,
                            language = language,
                            enabled = item.optBoolean("enabled", true)
                        )
                    )
                }
            }
            val storedIds = stored.map { it.id }.toSet()
            stored + defaults.filterNot { it.id in storedIds }
        }.getOrDefault(defaults)
    }

    fun save(sources: List<FeedSource>) {
        val array = JSONArray()
        sources.forEach { source ->
            array.put(
                JSONObject().apply {
                    put("id", source.id)
                    put("name", source.name)
                    put("siteUrl", source.siteUrl)
                    put("feedUrl", source.feedUrl ?: "")
                    put("category", source.category.name)
                    put("language", source.language.name)
                    put("enabled", source.enabled)
                }
            )
        }
        prefs.edit().putString(KEY_SOURCES, array.toString()).apply()
    }

    private companion object {
        const val PREFS = "newsrss_sources"
        const val KEY_SOURCES = "sources"
    }
}
