package com.abelcrvg.newsrss.core.model

/** Categories used to organize news sources in the app. */
enum class NewsCategory(val label: String) {
    NEWS("Notícias"),
    FOOTBALL("Futebol"),
    TECHNOLOGY("Tecnologia"),
    GAMES("Jogos")
}

/** A user-configured news source. */
data class FeedSource(
    val id: String,
    val name: String,
    val siteUrl: String,
    val feedUrl: String? = null,
    val category: NewsCategory = NewsCategory.NEWS,
    val enabled: Boolean = true
)
