package com.abelcrvg.newsrss.data.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI

/** Downloads public HTML documents without exposing them through a NewsRSS server. */
class ArticleHttpClient(
    private val userAgent: String = "NewsRSS/0.1 (Android; reader mode)"
) {
    suspend fun get(url: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val connection = URI(url).toURL().openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "GET"
                connection.instanceFollowRedirects = true
                connection.connectTimeout = 15_000
                connection.readTimeout = 20_000
                connection.setRequestProperty("User-Agent", userAgent)
                connection.setRequestProperty("Accept", "text/html,application/xhtml+xml")
                connection.connect()

                val status = connection.responseCode
                if (status !in 200..299) {
                    error("HTTP $status")
                }

                connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } finally {
                connection.disconnect()
            }
        }
    }
}
