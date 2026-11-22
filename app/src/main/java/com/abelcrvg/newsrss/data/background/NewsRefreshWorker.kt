package com.abelcrvg.newsrss.data.background

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.abelcrvg.newsrss.core.source.SourceRegistry
import com.abelcrvg.newsrss.data.feed.SmartFeedReader
import com.abelcrvg.newsrss.data.remote.SupabaseNewsPublisher
import com.abelcrvg.newsrss.data.source.FeedCacheStore
import com.abelcrvg.newsrss.data.source.SourceStore
import com.abelcrvg.newsrss.core.feed.FeedItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Refreshes sources in parallel with bounded concurrency and per-source intervals. */
class NewsRefreshWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val sourceStore = SourceStore(applicationContext)
        val cacheStore = FeedCacheStore(applicationContext)
        val now = System.currentTimeMillis()
        val sources = sourceStore.load(SourceRegistry.defaultSources)
            .filter { it.enabled }
            .filter { shouldRefresh(it.id, it.refreshIntervalMinutes, now) }
        if (sources.isEmpty()) return@withContext Result.success()

        val reader = SmartFeedReader(translationContext = null)
        val semaphore = Semaphore(4)
        val batches = mutableListOf<List<FeedItem>>()
        var successCount = 0
        var failureCount = 0

        coroutineScope {
            sources.map { source ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        val readResult = withTimeoutOrNull(25_000L) {
                            runCatching { reader.read(source) }.getOrElse { kotlin.Result.failure(it) }
                        }
                        val freshItems = readResult?.getOrNull().orEmpty().map { it.copy(sourceId = source.id) }
                        synchronized(batches) {
                            if (freshItems.isNotEmpty()) batches += freshItems
                            if (readResult != null) {
                                markRefreshed(source.id)
                                successCount++
                            } else {
                                failureCount++
                            }
                        }
                    }
                }
            }.awaitAll()
        }

        val incoming = batches.flatten()
        if (incoming.isNotEmpty()) {
            cacheStore.save(cacheStore.mergeLists(cacheStore.load(), incoming))
            runCatching { SupabaseNewsPublisher.publish(incoming.take(400)) }
        }

        when {
            successCount == 0 && failureCount > 0 && runAttemptCount < 2 -> Result.retry()
            successCount == 0 && failureCount > 0 -> Result.failure()
            else -> Result.success()
        }
    }

    private fun shouldRefresh(sourceId: String, intervalMinutes: Long, now: Long): Boolean {
        val last = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("source:$sourceId", 0L)
        val intervalMs = intervalMinutes.coerceIn(5L, 120L) * 60_000L
        return last == 0L || now - last >= intervalMs
    }

    private fun markRefreshed(sourceId: String) {
        applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong("source:$sourceId", System.currentTimeMillis()).apply()
    }

    private companion object {
        const val PREFS = "newsrss_refresh_state"
    }
}
