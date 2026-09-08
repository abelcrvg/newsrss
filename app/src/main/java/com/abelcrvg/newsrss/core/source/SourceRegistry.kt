package com.abelcrvg.newsrss.core.source

import com.abelcrvg.newsrss.core.model.FeedSource
import com.abelcrvg.newsrss.core.model.NewsCategory

/**
 * Central registry for the sources enabled in the app.
 * A source can be added with only its homepage when it exposes an RSS/Atom link.
 * Source IDs are independent from the domain so different sections of the same
 * site can coexist as separate sources.
 */
object SourceRegistry {
    val defaultSources: List<FeedSource> = listOf(
        FeedSource("g1", "G1", "https://g1.globo.com", category = NewsCategory.NEWS),
        FeedSource("uol", "UOL", "https://www.uol.com.br", category = NewsCategory.NEWS),
        FeedSource("ge", "ge", "https://ge.globo.com", category = NewsCategory.FOOTBALL),
        FeedSource("sky-sports", "Sky Sports", "https://www.skysports.com/football/news", category = NewsCategory.FOOTBALL),
        FeedSource("espn-brasil", "ESPN Brasil", "https://www.espn.com.br/futebol", category = NewsCategory.FOOTBALL),
        FeedSource("tecmundo", "TecMundo", "https://www.tecmundo.com.br", category = NewsCategory.TECHNOLOGY),
        FeedSource("voxel", "Voxel", "https://www.tecmundo.com.br/voxel", category = NewsCategory.GAMES),
        FeedSource("ign-brasil", "IGN Brasil", "https://br.ign.com", category = NewsCategory.GAMES),
        FeedSource("the-verge", "The Verge", "https://www.theverge.com", category = NewsCategory.ENGLISH),
        FeedSource("superinteressante", "Superinteressante", "https://super.abril.com.br/tudo-sobre/superinteressante/", category = NewsCategory.ENTERTAINMENT),
        FeedSource("super-oraculo", "Oráculo", "https://super.abril.com.br/coluna/oraculo/", category = NewsCategory.ENTERTAINMENT)
    )
}
