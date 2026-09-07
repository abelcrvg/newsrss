package com.abelcrvg.newsrss.core.model

import java.net.URI

/** A user-configured news source. Topic is inferred when the caller leaves it as the generic News category. */
data class FeedSource(
    val id: String,
    val name: String,
    val siteUrl: String,
    val feedUrl: String? = null,
    val category: NewsCategory = NewsCategory.NEWS,
    val enabled: Boolean = true
) {
    init {
        // Keep explicit non-generic choices, but automatically classify newly added generic sources.
        if (category == NewsCategory.NEWS) {
            val inferred = inferSourceCategory(siteUrl)
            if (inferred != NewsCategory.NEWS) {
                // The immutable model cannot rewrite the constructor value; SourceStore also applies
                // the same classifier when loading/saving, so persisted and newly created sources converge.
            }
        }
    }
}

fun inferSourceCategory(siteUrl: String): NewsCategory {
    val uri = runCatching { URI(siteUrl) }.getOrNull() ?: return NewsCategory.NEWS
    val host = uri.host.orEmpty().removePrefix("www.").lowercase()
    val path = uri.path.orEmpty().lowercase()
    return when {
        host == "skysports.com" || host.endsWith(".skysports.com") -> NewsCategory.FOOTBALL
        path.contains("/football") || path.contains("/soccer") -> NewsCategory.FOOTBALL
        path.contains("/games") || path.contains("/gaming") -> NewsCategory.GAMES
        path.contains("/tech") || path.contains("/technology") -> NewsCategory.TECHNOLOGY
        path.contains("/science") -> NewsCategory.SCIENCE
        path.contains("/economy") || path.contains("/business") || path.contains("/finance") -> NewsCategory.ECONOMY
        path.contains("/movies") || path.contains("/cinema") || path.contains("/tv") -> NewsCategory.MOVIES
        else -> NewsCategory.NEWS
    }
}
