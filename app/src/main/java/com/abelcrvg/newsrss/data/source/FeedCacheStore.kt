package com.abelcrvg.newsrss.data.source

import android.content.Context
import com.abelcrvg.newsrss.core.feed.FeedItem
import com.abelcrvg.newsrss.core.feed.NewsClusterer
import com.abelcrvg.newsrss.core.media.ImageQuality
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** Persistent feed cache with batched merge/save to reduce JSON churn. */
class FeedCacheStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): List<FeedItem> = runCatching {
        val array = JSONArray(prefs.getString(KEY_ITEMS, "[]"))
        val cutoff = Instant.now().minusSeconds(MAX_AGE_DAYS * 24L * 60L * 60L)
        buildList {
            for (i in 0 until array.length()) {
                runCatching {
                    val o = array.getJSONObject(i)
                    val publishedAt = o.optString("publishedAt").takeIf(String::isNotBlank)?.let { runCatching { Instant.parse(it) }.getOrNull() }
                    if (publishedAt != null && publishedAt < cutoff) return@runCatching
                    add(FeedItem(
                        id = o.getString("id"), sourceId = o.getString("sourceId"),
                        title = o.getString("title"), url = o.getString("url"),
                        summary = o.optString("summary").takeIf(String::isNotBlank),
                        publishedAt = publishedAt,
                        imageUrl = ImageQuality.sanitize(o.optString("imageUrl"))
                    ))
                }
            }
        }.distinctBy { NewsClusterer.canonicalUrl(it.url) }.sortedWith(feedOrder()).take(MAX_ITEMS)
    }.getOrDefault(emptyList())

    fun merge(items: List<FeedItem>) {
        if (items.isEmpty()) return
        save(mergeLists(load(), items))
    }

    fun mergeLists(current: List<FeedItem>, incoming: List<FeedItem>): List<FeedItem> {
        if (incoming.isEmpty()) return current.sortedWith(feedOrder()).take(MAX_ITEMS)
        val merged = LinkedHashMap<String, FeedItem>(current.size + incoming.size)
        current.forEach { merged[NewsClusterer.canonicalUrl(it.url)] = it }
        incoming.forEach { fresh ->
            val sanitized = fresh.copy(imageUrl = ImageQuality.sanitize(fresh.imageUrl))
            val key = NewsClusterer.canonicalUrl(sanitized.url)
            val previous = merged[key]
            merged[key] = if (previous == null) sanitized else previous.copy(
                id = sanitized.id,
                sourceId = sanitized.sourceId,
                title = sanitized.title.ifBlank { previous.title },
                url = sanitized.url,
                summary = sanitized.summary?.takeIf { it.isNotBlank() } ?: previous.summary,
                publishedAt = sanitized.publishedAt ?: previous.publishedAt,
                imageUrl = sanitized.imageUrl ?: previous.imageUrl
            )
        }
        return merged.values.sortedWith(feedOrder()).take(MAX_ITEMS)
    }

    fun save(items: List<FeedItem>) {
        val array = JSONArray()
        items.sortedWith(feedOrder()).take(MAX_ITEMS).forEach { item ->
            array.put(JSONObject().apply {
                put("id", item.id); put("sourceId", item.sourceId); put("title", item.title)
                put("url", item.url); put("summary", item.summary ?: "")
                put("publishedAt", item.publishedAt?.toString() ?: "")
                put("imageUrl", item.imageUrl ?: "")
            })
        }
        prefs.edit().putString(KEY_ITEMS, array.toString()).putLong(KEY_UPDATED_AT, System.currentTimeMillis()).apply()
    }

    fun lastUpdatedAt(): Long = prefs.getLong(KEY_UPDATED_AT, 0L)

    private fun feedOrder(): Comparator<FeedItem> = compareByDescending<FeedItem> { it.publishedAt ?: Instant.EPOCH }.thenByDescending { it.id }

    private companion object {
        const val PREFS = "newsrss_feed_cache"
        const val KEY_ITEMS = "items"
        const val KEY_UPDATED_AT = "updated_at"
        const val MAX_AGE_DAYS = 45L
        const val MAX_ITEMS = 1200
    }
}
