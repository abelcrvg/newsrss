package com.abelcrvg.newsrss.data.source

import android.content.Context
import com.abelcrvg.newsrss.core.feed.FeedItem
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** Persistent feed cache shared by the UI and the background worker. */
class FeedCacheStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): List<FeedItem> = runCatching {
        val array = JSONArray(prefs.getString(KEY_ITEMS, "[]"))
        buildList {
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                add(
                    FeedItem(
                        id = o.getString("id"),
                        sourceId = o.getString("sourceId"),
                        title = o.getString("title"),
                        url = o.getString("url"),
                        summary = o.optString("summary").takeIf(String::isNotBlank),
                        publishedAt = o.optString("publishedAt").takeIf(String::isNotBlank)?.let(Instant::parse),
                        imageUrl = o.optString("imageUrl").takeIf(String::isNotBlank)
                    )
                )
            }
        }.sortedWith(feedOrder())
    }.getOrDefault(emptyList())

    fun merge(items: List<FeedItem>) {
        if (items.isEmpty()) return
        val merged = LinkedHashMap<String, FeedItem>()
        load().forEach { merged[it.url] = it }
        items.forEach { merged[it.url] = it }
        val ordered = merged.values.sortedWith(feedOrder())
        val array = JSONArray()
        ordered.forEach { item ->
            array.put(JSONObject().apply {
                put("id", item.id)
                put("sourceId", item.sourceId)
                put("title", item.title)
                put("url", item.url)
                put("summary", item.summary ?: "")
                put("publishedAt", item.publishedAt?.toString() ?: "")
                put("imageUrl", item.imageUrl ?: "")
            })
        }
        prefs.edit().putString(KEY_ITEMS, array.toString()).putLong(KEY_UPDATED_AT, System.currentTimeMillis()).apply()
    }

    fun lastUpdatedAt(): Long = prefs.getLong(KEY_UPDATED_AT, 0L)

    private fun feedOrder(): Comparator<FeedItem> =
        compareByDescending<FeedItem> { it.publishedAt ?: Instant.EPOCH }
            .thenByDescending { it.id }

    private companion object {
        const val PREFS = "newsrss_feed_cache"
        const val KEY_ITEMS = "items"
        const val KEY_UPDATED_AT = "updated_at"
    }
}
