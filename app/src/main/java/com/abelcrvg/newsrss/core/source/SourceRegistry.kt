package com.abelcrvg.newsrss.core.source

import com.abelcrvg.newsrss.core.model.FeedSource
import com.abelcrvg.newsrss.core.model.NewsCategory

/**
 * Central registry for the sources enabled in the app.
 * A source can be added with only its homepage when it exposes an RSS/Atom link.
 */
object SourceRegistry {
    val defaultSources: List<FeedSource> = listOf(
        FeedSource("g1", "G1", "https://g1.globo.com", category = NewsCategory.NEWS),
        FeedSource("uol", "UOL", "https://www.uol.com.br", category = NewsCategory.NEWS),
        FeedSource("ge", "ge", "https://ge.globo.com", category = NewsCategory.FOOTBALL),
        FeedSource("tecmundo", "TecMundo", "https://www.tecmundo.com.br", category = NewsCategory.TECHNOLOGY),
        FeedSource("ign-brasil", "IGN Brasil", "https://br.ign.com", category = NewsCategory.GAMES),
        FeedSource("the-verge", "The Verge", "https://www.theverge.com", category = NewsCategory.ENGLISH)
    )
}
