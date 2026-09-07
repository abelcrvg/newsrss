package com.abelcrvg.newsrss

import android.app.Application
import com.abelcrvg.newsrss.data.background.NewsRefreshScheduler

class NewsRssApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        appContext = this
        NewsRefreshScheduler.schedule(this)
    }

    companion object {
        lateinit var appContext: NewsRssApplication
            private set
    }
}
