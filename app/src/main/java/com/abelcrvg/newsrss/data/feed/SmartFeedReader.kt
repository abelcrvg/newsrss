package com.abelcrvg.newsrss.data.feed

import com.abelcrvg.newsrss.core.feed.FeedItem
import com.abelcrvg.newsrss.core.feed.FeedReader
import com.abelcrvg.newsrss.core.model.FeedSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI

/** Reads news directly from each source website instead of relying on RSS/Atom. */
class SmartFeedReader(
    private val homepageCrawler: HomepageNewsCrawler = HomepageNewsCrawler()
) : FeedReader {
    override suspend fun read(source: FeedSource): Result<List<FeedItem>> = withContext(Dispatchers.IO) {
        val crawlResult = homepageCrawler.crawl(source)

        if (source.id == "voxel") {
            return@withContext crawlResult.map { items ->
                items.filter { isVoxelArticle(it.url) }
            }
        }

        crawlResult
    }

    private fun isVoxelArticle(url: String): Boolean {
        val path = runCatching { URI(url).path.orEmpty().lowercase() }.getOrDefault("")
        return path.startsWith("/voxel/") && path.length > "/voxel/".length
    }
}
