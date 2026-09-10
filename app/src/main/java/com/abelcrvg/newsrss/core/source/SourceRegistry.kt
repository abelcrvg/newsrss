package com.abelcrvg.newsrss.core.source

import com.abelcrvg.newsrss.core.model.FeedSource
import com.abelcrvg.newsrss.core.model.NewsCategory
import com.abelcrvg.newsrss.core.model.SourceLanguage

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
        FeedSource("sky-sports", "Sky Sports", "https://www.skysports.com/football/news", category = NewsCategory.FOOTBALL, language = SourceLanguage.ENGLISH),
        FeedSource("espn-brasil", "ESPN Brasil", "https://www.espn.com.br/futebol", category = NewsCategory.FOOTBALL),
        FeedSource("tnt-sports", "TNT Sports", "https://tntsports.com.br", category = NewsCategory.FOOTBALL),
        FeedSource("lance", "Lance!", "https://www.lance.com.br", category = NewsCategory.FOOTBALL),
        FeedSource("trivela", "Trivela", "https://trivela.com.br", category = NewsCategory.FOOTBALL),
        FeedSource("tecmundo", "TecMundo", "https://www.tecmundo.com.br", category = NewsCategory.TECHNOLOGY),
        FeedSource("canaltech", "Canaltech", "https://canaltech.com.br", feedUrl = "https://canaltech.com.br/rss/", category = NewsCategory.TECHNOLOGY),
        FeedSource("tecnoblog", "Tecnoblog", "https://tecnoblog.net", feedUrl = "https://tecnoblog.net/feed/", category = NewsCategory.TECHNOLOGY),
        FeedSource("olhar-digital", "Olhar Digital", "https://olhardigital.com.br", feedUrl = "https://olhardigital.com.br/rss", category = NewsCategory.TECHNOLOGY),
        FeedSource("voxel", "Voxel", "https://www.tecmundo.com.br/voxel", category = NewsCategory.GAMES),
        FeedSource("ign-brasil", "IGN Brasil", "https://br.ign.com", category = NewsCategory.GAMES),
        FeedSource("adrenaline", "Adrenaline", "https://www.adrenaline.com.br", category = NewsCategory.GAMES),
        FeedSource("the-enemy", "The Enemy", "https://www.theenemy.com.br", category = NewsCategory.GAMES),
        FeedSource("the-verge", "The Verge", "https://www.theverge.com", category = NewsCategory.ENGLISH, language = SourceLanguage.ENGLISH),
        FeedSource("reuters", "Reuters", "https://www.reuters.com", category = NewsCategory.ENGLISH, language = SourceLanguage.ENGLISH),
        FeedSource("bbc-news", "BBC News", "https://www.bbc.com/news", feedUrl = "https://feeds.bbci.co.uk/news/rss.xml", category = NewsCategory.ENGLISH, language = SourceLanguage.ENGLISH),
        FeedSource("guardian", "The Guardian", "https://www.theguardian.com", feedUrl = "https://www.theguardian.com/world/rss", category = NewsCategory.ENGLISH, language = SourceLanguage.ENGLISH),
        FeedSource("ap-news", "AP News", "https://apnews.com", category = NewsCategory.ENGLISH, language = SourceLanguage.ENGLISH),
        FeedSource("cnn-brasil", "CNN Brasil", "https://www.cnnbrasil.com.br", feedUrl = "https://www.cnnbrasil.com.br/feed/", category = NewsCategory.NEWS),
        FeedSource("infomoney", "InfoMoney", "https://www.infomoney.com.br", feedUrl = "https://www.infomoney.com.br/feed/", category = NewsCategory.ECONOMY),
        FeedSource("superinteressante", "Superinteressante", "https://super.abril.com.br/tudo-sobre/superinteressante/", category = NewsCategory.ENTERTAINMENT),
        FeedSource("super-oraculo", "Oráculo", "https://super.abril.com.br/coluna/oraculo/", category = NewsCategory.ENTERTAINMENT)
    )
}
