package com.abelcrvg.newsrss.data.source

import android.content.Context
import com.abelcrvg.newsrss.core.model.FeedSource
import com.abelcrvg.newsrss.core.model.NewsCategory
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/** Persists the user's source list locally and repairs/infers categories for known domains. */
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
                    val storedCategory = runCatching { NewsCategory.valueOf(item.optString("category")) }.getOrDefault(NewsCategory.NEWS)
                    add(
                        FeedSource(
                            id = item.getString("id"),
                            name = item.getString("name"),
                            siteUrl = siteUrl,
                            feedUrl = item.optString("feedUrl").takeIf { it.isNotBlank() },
                            category = inferCategory(siteUrl, storedCategory),
                            enabled = item.optBoolean("enabled", true)
                        )
                    )
                }
            }

            // Keep user settings, but automatically add newly bundled default sources.
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
                    put("category", inferCategory(source.siteUrl, source.category).name)
                    put("enabled", source.enabled)
                }
            )
        }
        prefs.edit().putString(KEY_SOURCES, array.toString()).apply()
    }

    private fun inferCategory(siteUrl: String, current: NewsCategory): NewsCategory {
        val uri = runCatching { URI(siteUrl) }.getOrNull() ?: return current
        val host = uri.host.orEmpty().removePrefix("www.").lowercase()
        val path = uri.path.orEmpty().lowercase()
        return when {
            host == "skysports.com" || host.endsWith(".skysports.com") -> NewsCategory.FOOTBALL
            path.contains("/football") || path.contains("/soccer") -> NewsCategory.FOOTBALL
            path.contains("/games") || path.contains("/gaming") -> NewsCategory.GAMES
            path.contains("/tech") || path.contains("/technology") -> NewsCategory.TECHNOLOGY
            path.contains("/science") -> NewsCategory.SCIENCE
            path.contains("/economy") || path.contains("/business") || path.contains("/finance") -> NewsCategory.ECONOMY
            path.contains("/movies") || path.contains("/cinema") || path.contains("/tv") -> NewsCategory.MOVIES
            else -> current
        }
    }

    private companion object {
        const val PREFS = "newsrss_sources"
        const val KEY_SOURCES = "sources"
    }
}
