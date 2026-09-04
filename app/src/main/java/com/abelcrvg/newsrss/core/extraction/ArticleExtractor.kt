package com.abelcrvg.newsrss.core.extraction

import com.abelcrvg.newsrss.core.model.Article

/**
 * Converts an article URL into a normalized, reader-friendly article.
 *
 * Implementations must prefer the editorial content and discard navigation,
 * advertising, popups, recommendation widgets and other page chrome.
 */
interface ArticleExtractor {
    suspend fun extract(url: String): Result<Article>
}
