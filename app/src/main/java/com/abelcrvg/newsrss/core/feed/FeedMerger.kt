package com.abelcrvg.newsrss.core.feed

import java.time.Instant

/** Keeps the newest version of each story and orders the feed by publication time. */
object FeedMerger {
    fun merge(
        current: List<FeedItem>,
        incoming: List<FeedItem>,
        keySelector: (FeedItem) -> String = { it.url }
    ): List<FeedItem> {
        val merged = LinkedHashMap<String, FeedItem>(current.size + incoming.size)
        current.forEach { merged[keySelector(it)] = it }
        incoming.forEach { fresh ->
            val key = keySelector(fresh)
            val old = merged[key]
            merged[key] = if (old == null) fresh else old.copy(
                id = fresh.id,
                sourceId = fresh.sourceId,
                title = fresh.title.ifBlank { old.title },
                summary = fresh.summary?.takeIf { it.isNotBlank() } ?: old.summary,
                publishedAt = fresh.publishedAt ?: old.publishedAt,
                imageUrl = fresh.imageUrl?.takeIf { it.isNotBlank() } ?: old.imageUrl
            )
        }
        return merged.values.sortedWith(
            compareByDescending<FeedItem> { it.publishedAt ?: Instant.EPOCH }
                .thenByDescending { it.id }
        )
    }
}
