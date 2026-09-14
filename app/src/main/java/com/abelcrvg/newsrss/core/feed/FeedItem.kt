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
) {
    init {
        require(url.isNotBlank()) { "Feed item URL cannot be blank" }
    }

    val canonicalUrl: String get() = UrlNormalizer.normalize(url)
}
