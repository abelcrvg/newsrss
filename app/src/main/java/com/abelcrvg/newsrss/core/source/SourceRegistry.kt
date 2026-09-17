package com.abelcrvg.newsrss.core.source

import com.abelcrvg.newsrss.core.model.FeedSource
import com.abelcrvg.newsrss.core.model.NewsCategory
import com.abelcrvg.newsrss.core.model.SourceLanguage

/** Central catalog of enabled sources and their refresh policies. */
object SourceRegistry {
    val defaultSources: List<FeedSource> = listOf(
        FeedSource("g1", "G1", "https://g1.globo.com", category = NewsCategory.NEWS, refreshIntervalMinutes = FeedSource.FAST_REFRESH_MINUTES),
        FeedSource("uol", "UOL", "https://www.uol.com.br", category = NewsCategory.NEWS),
        FeedSource("folha", "Folha", "https://www.folha.uol.com.br", feedUrl = "https://feeds.folha.uol.com.br/emcimadahora/rss09102020.xml", category = NewsCategory.NEWS, refreshIntervalMinutes = FeedSource.FAST_REFRESH_MINUTES),
        FeedSource("agencia-brasil", "Agência Brasil", "https://agenciabrasil.ebc.com.br", feedUrl = "https://agenciabrasil.ebc.com.br/rss/ultimasnoticias/feed.xml", category = NewsCategory.NEWS, refreshIntervalMinutes = FeedSource.FAST_REFRESH_MINUTES),
        FeedSource("poder360", "Poder360", "https://www.poder360.com.br", feedUrl = "https://www.poder360.com.br/feed/", category = NewsCategory.NEWS),
        FeedSource("ge", "ge", "https://ge.globo.com", category = NewsCategory.FOOTBALL, refreshIntervalMinutes = FeedSource.FAST_REFRESH_MINUTES),
        FeedSource("sky-sports", "Sky Sports", "https://www.skysports.com/football/news", category = NewsCategory.FOOTBALL, language = SourceLanguage.ENGLISH),
        FeedSource("espn-brasil", "ESPN Brasil", "https://www.espn.com.br/futebol", category = NewsCategory.FOOTBALL, refreshIntervalMinutes = FeedSource.FAST_REFRESH_MINUTES),
        FeedSource("tnt-sports", "TNT Sports", "https://tntsports.com.br", category = NewsCategory.FOOTBALL),
        FeedSource("lance", "Lance!", "https://www.lance.com.br", category = NewsCategory.FOOTBALL),
        FeedSource("trivela", "Trivela", "https://trivela.com.br", category = NewsCategory.FOOTBALL),
        FeedSource("tecmundo", "TecMundo", "https://www.tecmundo.com.br", category = NewsCategory.TECHNOLOGY),
        FeedSource("canaltech", "Canaltech", "https://canaltech.com.br", feedUrl = "https://canaltech.com.br/rss/", category = NewsCategory.TECHNOLOGY, refreshIntervalMinutes = FeedSource.SLOW_REFRESH_MINUTES),
        FeedSource("tecnoblog", "Tecnoblog", "https://tecnoblog.net", feedUrl = "https://tecnoblog.net/feed/", category = NewsCategory.TECHNOLOGY, refreshIntervalMinutes = FeedSource.SLOW_REFRESH_MINUTES),
        FeedSource("olhar-digital", "Olhar Digital", "https://olhardigital.com.br", feedUrl = "https://olhardigital.com.br/rss", category = NewsCategory.TECHNOLOGY, refreshIntervalMinutes = FeedSource.SLOW_REFRESH_MINUTES),
        FeedSource("macmagazine", "MacMagazine", "https://macmagazine.com.br", feedUrl = "https://macmagazine.com.br/feed/", category = NewsCategory.TECHNOLOGY, refreshIntervalMinutes = FeedSource.SLOW_REFRESH_MINUTES),
        FeedSource("9to5mac", "9to5Mac", "https://9to5mac.com", feedUrl = "https://9to5mac.com/feed/", category = NewsCategory.TECHNOLOGY, language = SourceLanguage.ENGLISH, refreshIntervalMinutes = FeedSource.SLOW_REFRESH_MINUTES),
        FeedSource("ars-technica", "Ars Technica", "https://arstechnica.com", feedUrl = "https://feeds.arstechnica.com/arstechnica/index", category = NewsCategory.TECHNOLOGY, language = SourceLanguage.ENGLISH, refreshIntervalMinutes = FeedSource.SLOW_REFRESH_MINUTES),
        FeedSource("voxel", "Voxel", "https://www.tecmundo.com.br/voxel", category = NewsCategory.GAMES, refreshIntervalMinutes = FeedSource.SLOW_REFRESH_MINUTES),
        FeedSource("ign-brasil", "IGN Brasil", "https://br.ign.com", category = NewsCategory.GAMES, refreshIntervalMinutes = FeedSource.SLOW_REFRESH_MINUTES),
        FeedSource("adrenaline", "Adrenaline", "https://www.adrenaline.com.br", category = NewsCategory.GAMES, refreshIntervalMinutes = FeedSource.SLOW_REFRESH_MINUTES),
        FeedSource("the-enemy", "The Enemy", "https://www.theenemy.com.br", category = NewsCategory.GAMES, refreshIntervalMinutes = FeedSource.SLOW_REFRESH_MINUTES),
        FeedSource("eurogamer-pt", "Eurogamer Portugal", "https://www.eurogamer.pt", feedUrl = "https://www.eurogamer.pt/feed", category = NewsCategory.GAMES, language = SourceLanguage.ENGLISH, refreshIntervalMinutes = FeedSource.SLOW_REFRESH_MINUTES),
        FeedSource("gamespot", "GameSpot", "https://www.gamespot.com", feedUrl = "https://www.gamespot.com/feeds/news/", category = NewsCategory.GAMES, language = SourceLanguage.ENGLISH, refreshIntervalMinutes = FeedSource.SLOW_REFRESH_MINUTES),
        FeedSource("the-verge", "The Verge", "https://www.theverge.com", category = NewsCategory.ENGLISH, language = SourceLanguage.ENGLISH, refreshIntervalMinutes = FeedSource.SLOW_REFRESH_MINUTES),
        FeedSource("reuters", "Reuters", "https://www.reuters.com", category = NewsCategory.ENGLISH, language = SourceLanguage.ENGLISH),
        FeedSource("bbc-news", "BBC News", "https://www.bbc.com/news", feedUrl = "https://feeds.bbci.co.uk/news/rss.xml", category = NewsCategory.ENGLISH, language = SourceLanguage.ENGLISH),
        FeedSource("guardian", "The Guardian", "https://www.theguardian.com", feedUrl = "https://www.theguardian.com/world/rss", category = NewsCategory.ENGLISH, language = SourceLanguage.ENGLISH),
        FeedSource("ap-news", "AP News", "https://apnews.com", category = NewsCategory.ENGLISH, language = SourceLanguage.ENGLISH),
        FeedSource("cnn-brasil", "CNN Brasil", "https://www.cnnbrasil.com.br", feedUrl = "https://www.cnnbrasil.com.br/feed/", category = NewsCategory.NEWS),
        FeedSource("infomoney", "InfoMoney", "https://www.infomoney.com.br", feedUrl = "https://www.infomoney.com.br/feed/", category = NewsCategory.ECONOMY),
        FeedSource("superinteressante", "Superinteressante", "https://super.abril.com.br/tudo-sobre/superinteressante/", category = NewsCategory.ENTERTAINMENT, refreshIntervalMinutes = FeedSource.SLOW_REFRESH_MINUTES),
        FeedSource("super-oraculo", "Oráculo", "https://super.abril.com.br/coluna/oraculo/", category = NewsCategory.ENTERTAINMENT, refreshIntervalMinutes = FeedSource.SLOW_REFRESH_MINUTES)
    )
}
