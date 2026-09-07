package com.abelcrvg.newsrss.data.background

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.abelcrvg.newsrss.core.source.SourceRegistry
import com.abelcrvg.newsrss.data.feed.SmartFeedReader
import com.abelcrvg.newsrss.data.source.FeedCacheStore
import com.abelcrvg.newsrss.data.source.SourceStore

class NewsRefreshWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val sourceStore = SourceStore(applicationContext)
        val cacheStore = FeedCacheStore(applicationContext)
        val sources = sourceStore.load(SourceRegistry.defaultSources).filter { it.enabled }
        if (sources.isEmpty()) return Result.success()

        var successCount = 0
        val reader = SmartFeedReader()
        for (source in sources) {
            val result = reader.read(source)
            if (result.isSuccess) {
                val freshItems = result.getOrElse { emptyList() }.map { it.copy(sourceId = source.id) }
                cacheStore.merge(freshItems)
                successCount++
            }
        }

        return when {
            successCount == sources.size -> Result.success()
            successCount > 0 -> Result.success()
            runAttemptCount < 2 -> Result.retry()
            else -> Result.failure()
        }
    }
}
