package com.abelcrvg.newsrss

import android.app.Application

class NewsRssApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        appContext = this
    }

    companion object {
        lateinit var appContext: NewsRssApplication
            private set
    }
}
