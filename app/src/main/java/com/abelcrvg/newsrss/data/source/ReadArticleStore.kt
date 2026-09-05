package com.abelcrvg.newsrss.data.source

import android.content.Context

/** Persists article URLs that the user has already opened. */
class ReadArticleStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): Set<String> = prefs.getStringSet(KEY_READ, emptySet())?.toSet() ?: emptySet()

    fun markRead(url: String) {
        val updated = load() + url
        prefs.edit().putStringSet(KEY_READ, updated).apply()
    }

    private companion object {
        const val PREFS = "newsrss_read_articles"
        const val KEY_READ = "read_urls"
    }
}
