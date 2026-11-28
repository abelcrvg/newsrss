package com.abelcrvg.newsrss.core.feed

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class FeedMergerTest {
    private fun item(url: String, title: String, published: String, summary: String? = null) = FeedItem(
        id = url,
        sourceId = "g1",
        title = title,
        url = url,
        summary = summary,
        publishedAt = Instant.parse(published)
    )

    @Test
    fun `deduplicates by url and sorts newest first`() {
        val old = listOf(
            item("https://a", "A", "2026-09-01T10:00:00Z"),
            item("https://b", "B", "2026-09-02T10:00:00Z")
        )
        val fresh = listOf(
            item("https://a", "A atualizada", "2026-09-03T10:00:00Z"),
            item("https://c", "C", "2026-09-04T10:00:00Z")
        )

        val merged = FeedMerger.merge(old, fresh)

        assertEquals(listOf("https://c", "https://a", "https://b"), merged.map { it.url })
        assertEquals("A atualizada", merged.first { it.url == "https://a" }.title)
    }

    @Test
    fun `incoming blanks do not erase existing metadata`() {
        val old = listOf(
            FeedItem(
                id = "old-id",
                sourceId = "g1",
                title = "Título",
                url = "https://a",
                summary = "Resumo",
                publishedAt = Instant.parse("2026-09-01T10:00:00Z"),
                imageUrl = "https://img.example/a.jpg"
            )
        )
        val fresh = listOf(
            FeedItem(
                id = "new-id",
                sourceId = "uol",
                title = "",
                url = "https://a",
                summary = "",
                publishedAt = null,
                imageUrl = ""
            )
        )

        val merged = FeedMerger.merge(old, fresh).single()

        assertEquals("new-id", merged.id)
        assertEquals("uol", merged.sourceId)
        assertEquals("Título", merged.title)
        assertEquals("Resumo", merged.summary)
        assertEquals(Instant.parse("2026-09-01T10:00:00Z"), merged.publishedAt)
        assertEquals("https://img.example/a.jpg", merged.imageUrl)
    }

    @Test
    fun `empty input keeps current feed`() {
        val current = listOf(item("https://a", "A", "2026-09-01T10:00:00Z"))
        assertEquals(current, FeedMerger.merge(current, emptyList()))
    }

    @Test
    fun `custom key selector deduplicates tracking variants of the same url`() {
        val canonical = { url: String -> url.substringBefore("?") }
        val current = listOf(item("https://a?utm_source=x", "A", "2026-09-01T10:00:00Z"))
        val fresh = listOf(item("https://a?utm_source=y", "A atualizada", "2026-09-02T10:00:00Z"))

        val merged = FeedMerger.merge(current, fresh) { canonical(it.url) }

        assertEquals(1, merged.size)
        assertEquals("A atualizada", merged.single().title)
    }
}
