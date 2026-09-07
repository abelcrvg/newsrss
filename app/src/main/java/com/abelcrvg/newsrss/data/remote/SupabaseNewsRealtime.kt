package com.abelcrvg.newsrss.data.remote

import com.abelcrvg.newsrss.core.feed.FeedItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.TimeUnit

class SupabaseNewsRealtime(
    private val onItem: (FeedItem) -> Unit
) {
    companion object {
        private const val REALTIME_URL = "wss://xwuwuzvrekmwronnrmnv.supabase.co/realtime/v1/websocket"
        private const val API_KEY = "sb_publishable_05X6a5XrSNQ923Y1gNwr7Q_YJJDdopF"
    }

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val scope = CoroutineScope(Dispatchers.IO)
    private var socket: WebSocket? = null
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    private var stopped = false
    private var ref = 0

    fun start() {
        stopped = false
        connect()
    }

    fun stop() {
        stopped = true
        heartbeatJob?.cancel()
        reconnectJob?.cancel()
        socket?.close(1000, "closed")
        socket = null
    }

    private fun connect() {
        if (stopped) return
        val request = Request.Builder()
            .url("$REALTIME_URL?apikey=$API_KEY&vsn=1.0.0")
            .build()
        socket = client.newWebSocket(request, listener)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            socket = webSocket
            val joinRef = nextRef()
            val join = JSONObject()
                .put("topic", "realtime:public:news_items")
                .put("event", "phx_join")
                .put("ref", joinRef)
                .put("payload", JSONObject().apply {
                    put("config", JSONObject().apply {
                        put("broadcast", JSONObject().put("ack", false).put("self", false))
                        put("presence", JSONObject().put("key", ""))
                        put("postgres_changes", org.json.JSONArray().put(
                            JSONObject().put("event", "*").put("schema", "public").put("table", "news_items")
                        ))
                    })
                })
            webSocket.send(join.toString())
            heartbeatJob?.cancel()
            heartbeatJob = scope.launch {
                while (isActive && !stopped) {
                    delay(25_000)
                    socket?.send(JSONObject().put("topic", "phoenix").put("event", "heartbeat").put("payload", JSONObject()).put("ref", nextRef()).toString())
                }
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            runCatching {
                val root = JSONObject(text)
                if (root.optString("event") != "postgres_changes") return
                val record = root.optJSONObject("payload")?.optJSONObject("data")?.optJSONObject("record") ?: return
                val item = FeedItem(
                    id = record.optString("id"),
                    sourceId = record.optString("source_id"),
                    title = record.optString("title"),
                    url = record.optString("url"),
                    summary = record.optString("summary").takeIf { it.isNotBlank() },
                    publishedAt = record.optString("published_at").takeIf { it.isNotBlank() }?.let { Instant.parse(it) },
                    imageUrl = record.optString("image_url").takeIf { it.isNotBlank() }
                )
                if (item.id.isNotBlank() && item.title.isNotBlank() && item.url.isNotBlank()) onItem(item)
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (stopped || reconnectJob?.isActive == true) return
        heartbeatJob?.cancel()
        reconnectJob = scope.launch {
            delay(5_000)
            if (!stopped) connect()
        }
    }

    private fun nextRef(): String = (++ref).toString()
}
