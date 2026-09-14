package com.abelcrvg.newsrss.core.feed

import java.time.Instant

/** A normalized article discovered from an RSS or Atom feed. */
data class FeedItem(
    val id: String,
    val sourceId: String,
    val title: String,
    val url: String,
    val summary: String? = null,
    val publishedAt: Instant? = null,
    val imageUrl: String? = null
)
