package com.abelcrvg.newsrss.core.model

/** A user-configured news source. */
data class FeedSource(
    val id: String,
    val name: String,
    val siteUrl: String,
    val feedUrl: String? = null,
    val enabled: Boolean = true
)
