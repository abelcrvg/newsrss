package com.abelcrvg.newsrss.data.source

import android.content.Context
import com.abelcrvg.newsrss.core.model.FeedSource
import com.abelcrvg.newsrss.core.model.NewsCategory
import org.json.JSONArray
import org.json.JSONObject

/** Persists the user's source list locally so categories and enabled states survive restarts. */
class SourceStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(defaults: List<FeedSource>): List<FeedSource> {
        val raw = prefs.getString(KEY_SOURCES, null) ?: return defaults
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    add(
                        FeedSource(
                            id = item.getString("id"),
                            name = item.getString("name"),
                            siteUrl = item.getString("siteUrl"),
                            feedUrl = item.optString("feedUrl").takeIf { it.isNotBlank() },
                            category = runCatching { NewsCategory.valueOf(item.optString("category")) }.getOrDefault(NewsCategory.NEWS),
                            enabled = item.optBoolean("enabled", true)
                        )
                    )
                }
            }
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
