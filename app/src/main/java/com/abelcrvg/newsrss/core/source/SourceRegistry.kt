package com.abelcrvg.newsrss.core.source

import com.abelcrvg.newsrss.core.model.FeedSource

/**
 * Central registry for the sources enabled in the app.
 * A source can be added with only its homepage when it exposes an RSS/Atom link.
 */
object SourceRegistry {
    val defaultSources: List<FeedSource> = listOf(
        FeedSource("g1", "G1", "https://g1.globo.com"),
        FeedSource("uol", "UOL", "https://www.uol.com.br")
    )
}
