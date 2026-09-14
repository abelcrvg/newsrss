package com.abelcrvg.newsrss.data.remote

import com.abelcrvg.newsrss.core.feed.FeedItem
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Publishes only through the public ingestion boundary; privileged database access stays server-side. */
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
                setRequestProperty("apikey", PUBLISHABLE_KEY)
                setRequestProperty("Content-Type", "application/json")
            }
            val payload = JSONArray().apply {
                items.take(100).forEach { item ->
                    put(JSONObject().apply {
                        put("id", item.id)
                        put("sourceId", item.sourceId)
                        put("title", item.title)
                        put("url", item.canonicalUrl)
                        put("summary", item.summary ?: JSONObject.NULL)
                        put("publishedAt", item.publishedAt?.toString() ?: JSONObject.NULL)
                        put("imageUrl", item.imageUrl ?: JSONObject.NULL)
                    })
                }
            }
            connection.outputStream.use { output ->
                output.write(JSONObject().put("items", payload).toString().toByteArray(Charsets.UTF_8))
            }
            val status = connection.responseCode
            if (status !in 200..299) return@runCatching
            connection.inputStream.use { it.readBytes() }
            connection.disconnect()
        }
    }
}
