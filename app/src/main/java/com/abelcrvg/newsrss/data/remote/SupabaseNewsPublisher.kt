package com.abelcrvg.newsrss.data.remote

import com.abelcrvg.newsrss.core.feed.FeedItem
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object SupabaseNewsPublisher {
    private const val FUNCTION_URL = "https://xwuwuzvrekmwronnrmnv.supabase.co/functions/v1/news-sync"
    private const val PUBLISHABLE_KEY = "sb_publishable_05X6a5XrSNQ923Y1gNwr7Q_YJJDdopF"

    fun publish(items: List<FeedItem>) {
        if (items.isEmpty()) return
        runCatching {
            val connection = (URL(FUNCTION_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 20_000
                doOutput = true
                setRequestProperty("Authorization", "Bearer $PUBLISHABLE_KEY")
                setRequestProperty("apikey", PUBLISHABLE_KEY)
                setRequestProperty("Content-Type", "application/json")
            }
            val payload = JSONArray().apply {
                items.forEach { item ->
                    put(JSONObject().apply {
                        put("id", item.id)
                        put("sourceId", item.sourceId)
                        put("title", item.title)
                        put("url", item.url)
                        put("summary", item.summary ?: JSONObject.NULL)
                        put("publishedAt", item.publishedAt?.toString() ?: JSONObject.NULL)
                        put("imageUrl", item.imageUrl ?: JSONObject.NULL)
                    })
                }
            }
            connection.outputStream.use { output ->
                output.write(JSONObject().put("items", payload).toString().toByteArray(Charsets.UTF_8))
            }
            connection.inputStream.use { it.readBytes() }
            connection.disconnect()
        }
    }
}
