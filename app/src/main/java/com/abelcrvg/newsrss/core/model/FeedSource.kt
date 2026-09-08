package com.abelcrvg.newsrss.core.model

import java.net.URI

data class FeedSource(
    val id: String,
    val name: String,
    val siteUrl: String,
    val feedUrl: String? = null,
    var category: NewsCategory = NewsCategory.NEWS,
    var language: SourceLanguage = SourceLanguage.AUTO,
    val enabled: Boolean = true
) {
    init {
        val inferred = SourceAnalyzer.infer(siteUrl)
        if (category == NewsCategory.NEWS) category = inferred.category
        if (language == SourceLanguage.AUTO) language = inferred.language
    }
}

data class SourceAnalysis(
    val category: NewsCategory,
    val language: SourceLanguage
)

object SourceAnalyzer {
    fun infer(siteUrl: String): SourceAnalysis {
        val uri = runCatching { URI(siteUrl) }.getOrNull()
            ?: return SourceAnalysis(NewsCategory.NEWS, SourceLanguage.AUTO)
        val host = uri.host.orEmpty().removePrefix("www.").lowercase()
        val path = uri.path.orEmpty().lowercase()
        val text = "$host $path"

        val category = when {
            host == "skysports.com" || host.endsWith(".skysports.com") -> NewsCategory.FOOTBALL
            host == "espn.com.br" || host.endsWith(".espn.com.br") -> NewsCategory.FOOTBALL
            host == "ge.globo.com" || host.endsWith(".ge.globo.com") -> NewsCategory.FOOTBALL
            listOf("football", "futebol", "soccer", "premier-league", "brasileirao").any(text::contains) -> NewsCategory.FOOTBALL
            listOf("games", "gaming", "jogos", "voxel").any(text::contains) -> NewsCategory.GAMES
            listOf("tech", "technology", "tecnologia").any(text::contains) -> NewsCategory.TECHNOLOGY
            listOf("science", "ciencia", "ciência").any(text::contains) -> NewsCategory.SCIENCE
            listOf("economy", "economia", "business", "finance").any(text::contains) -> NewsCategory.ECONOMY
            host == "super.abril.com.br" || host.endsWith(".super.abril.com.br") -> NewsCategory.ENTERTAINMENT
            listOf("entertainment", "entretenimento", "celebridades", "celebrity", "cultura").any(text::contains) -> NewsCategory.ENTERTAINMENT
            listOf("movies", "movie", "cinema", "filmes", "series", "tv").any(text::contains) -> NewsCategory.MOVIES
            listOf("world", "mundo", "international", "internacional").any(text::contains) -> NewsCategory.WORLD
            host == "theverge.com" || host.endsWith(".theverge.com") -> NewsCategory.TECHNOLOGY
            else -> NewsCategory.NEWS
        }

        val language = when {
            host == "skysports.com" || host.endsWith(".skysports.com") -> SourceLanguage.ENGLISH
            host == "theverge.com" || host.endsWith(".theverge.com") -> SourceLanguage.ENGLISH
            host == "espn.com.br" || host.endsWith(".espn.com.br") -> SourceLanguage.PORTUGUESE
            host == "super.abril.com.br" || host.endsWith(".super.abril.com.br") -> SourceLanguage.PORTUGUESE
            host.endsWith(".br") -> SourceLanguage.PORTUGUESE
            else -> SourceLanguage.AUTO
        }
        return SourceAnalysis(category, language)
    }
}

fun inferSourceCategory(siteUrl: String): NewsCategory = SourceAnalyzer.infer(siteUrl).category
