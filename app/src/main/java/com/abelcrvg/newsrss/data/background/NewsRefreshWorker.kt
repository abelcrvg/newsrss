package com.abelcrvg.newsrss.data.background

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.abelcrvg.newsrss.core.source.SourceRegistry
import com.abelcrvg.newsrss.data.feed.SmartFeedReader
import com.abelcrvg.newsrss.data.remote.SupabaseNewsPublisher
import com.abelcrvg.newsrss.data.source.FeedCacheStore
import com.abelcrvg.newsrss.data.source.SourceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

class NewsRefreshWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val sourceStore = SourceStore(applicationContext)
        val cacheStore = FeedCacheStore(applicationContext)
        val sources = sourceStore.load(SourceRegistry.defaultSources).filter { it.enabled }
        if (sources.isEmpty()) return@withContext Result.success()

        val due = sources.filter(::isDue)
        if (due.isEmpty()) return@withContext Result.success()

        val reader = SmartFeedReader()
        val semaphore = Semaphore(MAX_CONCURRENT_SOURCES)
        val results = coroutineScope {
            due.map { source ->
                async {
                    semaphore.withPermit {
                        source to runCatching { reader.read(source) }
                    }
                }
            }.awaitAll()
        }

        var successCount = 0
        results.forEach { (source, result) ->
            if (result.isSuccess) {
                val freshItems = result.getOrElse { emptyList() }.map { it.copy(sourceId = source.id) }
                cacheStore.merge(freshItems)
                SupabaseNewsPublisher.publish(freshItems)
                markRefreshed(source.id)
                successCount++
            }
        }

        when {
            successCount == due.size -> Result.success()
            successCount > 0 -> Result.success()
            runAttemptCount < 2 -> Result.retry()
            else -> Result.failure()
        }
    }

    private fun isDue(source: com.abelcrvg.newsrss.core.model.FeedSource): Boolean {
        val last = prefs.getLong("source:${source.id}", 0L)
        return last == 0L || System.currentTimeMillis() - last >= source.refreshIntervalMinutes * 60_000L
    }

    private fun markRefreshed(sourceId: String) {
        prefs.edit().putLong("source:$sourceId", System.currentTimeMillis()).apply()
    }

    private companion object {
        const val PREFS = "newsrss_refresh_state"
        const val MAX_CONCURRENT_SOURCES = 4
    }

    private val prefs by lazy { applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
}
