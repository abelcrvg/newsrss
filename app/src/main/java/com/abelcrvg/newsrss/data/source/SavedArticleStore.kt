package com.abelcrvg.newsrss.data.source

import android.content.Context

/** Persists article URLs saved by the user for later reading. */
class SavedArticleStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): Set<String> = prefs.getStringSet(KEY_SAVED, emptySet())?.toSet() ?: emptySet()

    fun setSaved(url: String, saved: Boolean) {
        val updated = load().toMutableSet()
        if (saved) updated.add(url) else updated.remove(url)
        prefs.edit().putStringSet(KEY_SAVED, updated).apply()
    }

    private companion object {
        const val PREFS = "newsrss_saved_articles"
        const val KEY_SAVED = "saved_urls"
    }
}
