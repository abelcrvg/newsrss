package com.abelcrvg.newsrss.data.source

import android.content.Context
import com.abelcrvg.newsrss.core.model.Article
import com.abelcrvg.newsrss.core.model.ArticleBlock
import com.abelcrvg.newsrss.core.model.ExtractionMetadata
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * Persists fully extracted (and possibly translated) articles so previously opened
 * news can be read again offline and translated only once.
 */
class ArticleCacheStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("newsrss_articles", Context.MODE_PRIVATE)

    fun load(url: String): Article? = runCatching {
        val raw = prefs.getString(key(url), null) ?: return null
        articleFromJson(JSONObject(raw))
    }.getOrNull()

    fun save(article: Article) {
        runCatching {
            val index = JSONArray(prefs.getString(KEY_INDEX, "[]"))
            val urls = linkedSetOf<String>()
            for (i in 0 until index.length()) runCatching { urls.add(index.getString(i)) }
            urls.remove(article.url)
            urls.add(article.url)
            val evicted = mutableListOf<String>()
            while (urls.size > MAX_ARTICLES) {
                val oldest = urls.first()
                urls.remove(oldest)
                evicted.add(oldest)
            }
            val next = JSONArray()
            urls.forEach { next.put(it) }
            prefs.edit()
                .putString(key(article.url), articleToJson(article).toString())
                .putString(KEY_INDEX, next.toString())
                .apply { evicted.forEach { remove(key(it)) } }
                .apply()
        }
    }

    private fun key(url: String): String = "article:$url"

    private fun articleToJson(article: Article) = JSONObject().apply {
        put("id", article.id)
        put("sourceId", article.sourceId)
        put("url", article.url)
        put("title", article.title)
        put("subtitle", article.subtitle ?: JSONObject.NULL)
        put("author", article.author ?: JSONObject.NULL)
        put("publishedAt", article.publishedAt?.toString() ?: JSONObject.NULL)
        put("heroImageUrl", article.heroImageUrl ?: JSONObject.NULL)
        put("summary", JSONArray(article.summary))
        put(
            "extraction", JSONObject()
                .put("confidence", article.extraction.confidence)
                .put("extractor", article.extraction.extractor)
                .put("wordCount", article.extraction.wordCount)
                .put("imageCount", article.extraction.imageCount)
                .put("warnings", JSONArray(article.extraction.warnings))
        )
        val blocks = JSONArray()
        article.blocks.forEach { block ->
            blocks.put(
                when (block) {
                    is ArticleBlock.Paragraph -> JSONObject().put("type", "p")
                        .put("text", block.text.text)
                        .put("html", block.inlineHtml ?: JSONObject.NULL)
                    is ArticleBlock.Heading -> JSONObject().put("type", "h")
                        .put("text", block.text).put("level", block.level)
                    is ArticleBlock.Image -> JSONObject().put("type", "img")
                        .put("url", block.url)
                        .put("caption", block.caption ?: JSONObject.NULL)
                        .put("alt", block.altText ?: JSONObject.NULL)
                    is ArticleBlock.Quote -> JSONObject().put("type", "quote")
                        .put("text", block.text)
                        .put("author", block.author ?: JSONObject.NULL)
                    is ArticleBlock.ListBlock -> JSONObject().put("type", "list")
                        .put("ordered", block.ordered)
                        .put("items", JSONArray(block.items))
                }
            )
        }
        put("blocks", blocks)
    }

    private fun articleFromJson(json: JSONObject): Article {
        val blocks = mutableListOf<ArticleBlock>()
        val array = json.optJSONArray("blocks") ?: JSONArray()
        for (i in 0 until array.length()) {
            val block = array.optJSONObject(i) ?: continue
            when (block.optString("type")) {
                "p" -> blocks.add(
                    ArticleBlock.Paragraph(
                        block.optString("text"),
                        block.optString("html").takeIf { it.isNotBlank() && it != "null" }
                    )
                )
                "h" -> blocks.add(ArticleBlock.Heading(block.optString("text"), block.optInt("level", 2)))
                "img" -> blocks.add(
                    ArticleBlock.Image(
                        block.optString("url"),
                        block.optString("caption").takeIf(String::isNotBlank),
                        block.optString("alt").takeIf(String::isNotBlank)
                    )
                )
                "quote" -> blocks.add(
                    ArticleBlock.Quote(
                        block.optString("text"),
                        block.optString("author").takeIf(String::isNotBlank)
                    )
                )
                "list" -> {
                    val items = mutableListOf<String>()
                    val values = block.optJSONArray("items") ?: JSONArray()
                    for (j in 0 until values.length()) values.optString(j).takeIf(String::isNotBlank)?.let(items::add)
                    blocks.add(ArticleBlock.ListBlock(items, block.optBoolean("ordered")))
                }
            }
        }
        val summary = mutableListOf<String>()
        val summaryArray = json.optJSONArray("summary") ?: JSONArray()
        for (i in 0 until summaryArray.length()) summaryArray.optString(i).takeIf(String::isNotBlank)?.let(summary::add)
        val meta = json.optJSONObject("extraction") ?: JSONObject()
        val warnings = mutableListOf<String>()
        val warningArray = meta.optJSONArray("warnings") ?: JSONArray()
        for (i in 0 until warningArray.length()) warningArray.optString(i).takeIf(String::isNotBlank)?.let(warnings::add)
        val extraction = ExtractionMetadata(
            confidence = meta.optInt("confidence"),
            extractor = meta.optString("extractor").takeIf(String::isNotBlank) ?: "unknown",
            wordCount = meta.optInt("wordCount"),
            imageCount = meta.optInt("imageCount"),
            warnings = warnings
        )
        return Article(
            id = json.getString("id"),
            sourceId = json.getString("sourceId"),
            url = json.getString("url"),
            title = json.getString("title"),
            subtitle = json.optString("subtitle").takeIf(String::isNotBlank),
            author = json.optString("author").takeIf(String::isNotBlank),
            publishedAt = json.optString("publishedAt").takeIf(String::isNotBlank)?.let { runCatching { Instant.parse(it) }.getOrNull() },
            heroImageUrl = json.optString("heroImageUrl").takeIf(String::isNotBlank),
            blocks = blocks,
            summary = summary,
            extraction = extraction
        )
    }

    private companion object {
        const val KEY_INDEX = "index"
        const val MAX_ARTICLES = 120
    }
}
