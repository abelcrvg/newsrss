package com.abelcrvg.newsrss.data.source

import android.content.Context
import com.abelcrvg.newsrss.core.feed.FeedItem
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

class ReadArticleStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun load(): Set<String> = prefs.getStringSet(KEY_READ, emptySet())?.toSet() ?: emptySet()
    fun loadItems(): List<FeedItem> = runCatching {
        val a = JSONArray(prefs.getString(KEY_ITEMS, "[]"))
        buildList { for (i in 0 until a.length()) { val o=a.getJSONObject(i); add(FeedItem(o.getString("id"),o.getString("sourceId"),o.getString("title"),o.getString("url"),o.optString("summary").takeIf{it.isNotBlank()},o.optString("publishedAt").takeIf{it.isNotBlank()}?.let(Instant::parse),o.optString("imageUrl").takeIf{it.isNotBlank()})) } }
    }.getOrDefault(emptyList())
    fun markRead(item: FeedItem) {
        val urls=load()+item.url; val items=(listOf(item)+loadItems()).distinctBy{it.url}.take(300); val a=JSONArray()
        items.forEach{x->a.put(JSONObject().apply{put("id",x.id);put("sourceId",x.sourceId);put("title",x.title);put("url",x.url);put("summary",x.summary?:"");put("publishedAt",x.publishedAt?.toString()?:"");put("imageUrl",x.imageUrl?:"")})}
        prefs.edit().putStringSet(KEY_READ,urls).putString(KEY_ITEMS,a.toString()).apply()
    }
    private companion object { const val PREFS="newsrss_read_articles"; const val KEY_READ="read_urls"; const val KEY_ITEMS="read_items" }
}
