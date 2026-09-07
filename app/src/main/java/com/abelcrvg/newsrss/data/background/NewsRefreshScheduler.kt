package com.abelcrvg.newsrss.data.background

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object NewsRefreshScheduler {
    private const val PERIODIC_NAME = "newsrss-periodic-refresh"
    private const val STARTUP_NAME = "newsrss-startup-refresh"

    fun schedule(context: Context) {
        val appContext = context.applicationContext
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val periodic = PeriodicWorkRequestBuilder<NewsRefreshWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(appContext).enqueueUniquePeriodicWork(
            PERIODIC_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            periodic
        )
    }

    fun refreshNow(context: Context) {
        val appContext = context.applicationContext
        val request = OneTimeWorkRequestBuilder<NewsRefreshWorker>().build()
        WorkManager.getInstance(appContext).enqueueUniqueWork(
            STARTUP_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
    }
}
