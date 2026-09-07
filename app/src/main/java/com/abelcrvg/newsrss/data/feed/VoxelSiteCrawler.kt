package com.abelcrvg.newsrss.data.feed

import com.abelcrvg.newsrss.core.feed.FeedItem
import com.abelcrvg.newsrss.core.model.FeedSource

class VoxelSiteCrawler {
    private val crawler = DirectSiteCrawler(DirectSiteCrawler.Config(
        sourceId = "voxel",
        homeUrl = "https://www.tecmundo.com.br/voxel/",
        allowedHosts = setOf("www.tecmundo.com.br", "tecmundo.com.br"),
        selectors = "article a[href], a[data-testid*=article][href], a[href]",
        articlePath = { path ->
            path.startsWith("/voxel/") && path.split('/').count { it.isNotBlank() } >= 2 &&
                !path.matches(Regex(".*/(autor|autoridade|tags?|busca|search|videos?|galeria|lista|especial|colunistas?)(/|$).*")) &&
                !path.matches(Regex(".*\\.(jpg|jpeg|png|gif|webp|svg)$"))
        }
    ))

    suspend fun crawl(source: FeedSource): Result<List<FeedItem>> = crawler.crawl()
}
