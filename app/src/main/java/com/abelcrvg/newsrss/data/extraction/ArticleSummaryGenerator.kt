package com.abelcrvg.newsrss.data.extraction

import com.abelcrvg.newsrss.core.model.Article
import com.abelcrvg.newsrss.core.model.ArticleBlock

/**
 * Creates a short extractive summary without a network call or an AI credential.
 * It deliberately prefers complete sentences from the article instead of inventing facts.
 */
object ArticleSummaryGenerator {
    fun generate(article: Article, maxBullets: Int = 4): List<String> {
        if (article.summary.isNotEmpty()) return article.summary.take(maxBullets)
        val candidates = article.blocks
            .filterIsInstance<ArticleBlock.Paragraph>()
            .map { it.text.text.trim() }
            .filter { it.length >= 70 }
            .distinctBy { normalize(it) }

        if (candidates.isEmpty()) return emptyList()
        val selected = candidates
            .mapIndexed { index, text -> index to text }
            .sortedByDescending { (index, text) -> score(index, text) }
            .take(maxBullets)
            .sortedBy { it.first }
            .map { shorten(it.second) }
        return selected.distinct().take(maxBullets)
    }

    private fun score(index: Int, text: String): Int {
        val lengthScore = when {
            text.length in 120..420 -> 30
            text.length in 80..550 -> 20
            else -> 10
        }
        val positionScore = (24 - index.coerceAtMost(24))
        val signalScore = listOf(
            "segundo", "de acordo", "anunciou", "informou", "disse", "afirmou", "governo", "empresa",
            "according", "said", "announced", "reported", "company", "government"
        ).count { text.contains(it, ignoreCase = true) } * 5
        return lengthScore + positionScore + signalScore
    }

    private fun shorten(text: String): String {
        val clean = text.replace(Regex("\\s+"), " ").trim()
        if (clean.length <= 280) return clean
        val boundary = clean.substring(0, 280).lastIndexOfAny(charArrayOf('.', '!', '?', ';', ':'))
        return if (boundary >= 120) clean.substring(0, boundary + 1) else clean.substring(0, 277).trimEnd() + "…"
    }

    private fun normalize(text: String): String = text.lowercase().replace(Regex("\\W+"), " ").trim()
}
