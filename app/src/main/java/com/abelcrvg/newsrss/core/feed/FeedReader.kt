package com.abelcrvg.newsrss.core.feed

import com.abelcrvg.newsrss.core.model.FeedSource

/**
 * Reads RSS/Atom sources and exposes normalized feed entries.
 * The feed layer discovers articles; the extraction layer obtains the full article.
 */
interface FeedReader {
    suspend fun discoverFeed(source: FeedSource): Result<FeedSource>
}
