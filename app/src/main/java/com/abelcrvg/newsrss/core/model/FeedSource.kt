package com.abelcrvg.newsrss.core.model

import java.net.URI

/** A user-configured news source. Topic is inferred automatically when the generic News category is used. */
data class FeedSource(
    val id: String,
    val name: String,
    val siteUrl: String,
    val feedUrl: String? = null,
    var category: NewsCategory = NewsCategory.NEWS,
    val enabled: Boolean = true
) {
    init {
        if (category == NewsCategory.NEWS) {
            category = inferSourceCategory(siteUrl)
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
