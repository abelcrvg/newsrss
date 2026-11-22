package com.abelcrvg.newsrss.core.feed

import java.net.URI
import java.security.MessageDigest
import java.text.Normalizer
import java.time.Duration
import java.time.Instant
import java.util.Locale

data class NewsCluster(
    val id: String,
    val title: String,
    val articles: List<FeedItem>,
    val sourceIds: Set<String>,
    val lastUpdatedAt: Instant,
    val isHot: Boolean
)

object NewsClusterer {
    private const val MAX_CLUSTER_WINDOW_HOURS = 36L
    private const val HOT_WINDOW_MINUTES = 120L
    private const val MAX_INPUT_ITEMS = 220
    private const val MAX_COMPARE = 40

    fun cluster(items: List<FeedItem>): List<NewsCluster> {
        val sorted = items.sortedWith(compareByDescending<FeedItem> { it.publishedAt ?: Instant.EPOCH }.thenByDescending { it.id }).take(MAX_INPUT_ITEMS)
        val prepared = sorted.map { Prepared(it, canonicalUrl(it.url), distinctiveTokens(it.title), distinctiveTokens(it.summary.orEmpty())) }
        val clusters = mutableListOf<MutableCluster>()

        for (item in prepared) {
            val recentStart = (clusters.size - MAX_COMPARE).coerceAtLeast(0)
            val match = clusters.subList(recentStart, clusters.size).firstOrNull { candidate -> belongsTo(candidate, item) }
                ?: clusters.firstOrNull { candidate -> belongsTo(candidate, item) }
            if (match != null) match.items += item else clusters += MutableCluster(mutableListOf(item))
        }

        return clusters.mapNotNull { cluster ->
            val unique = cluster.items.groupBy { it.canonical }.values.mapNotNull { it.maxByOrNull { p -> p.item.publishedAt ?: Instant.EPOCH } }
            val sources = unique.map { it.item.sourceId }.toSet()
            if (unique.size < 2 || sources.size < 2) return@mapNotNull null
            val ordered = unique.sortedWith(compareByDescending<Prepared> { it.item.publishedAt ?: Instant.EPOCH }.thenByDescending { it.item.id }).map { it.item }
            val latest = ordered.firstOrNull() ?: return@mapNotNull null
            NewsCluster(
                id = clusterId(ordered),
                title = latest.title.trim(),
                articles = ordered,
                sourceIds = sources,
                lastUpdatedAt = latest.publishedAt ?: Instant.now(),
                isHot = isHot(ordered)
            )
        }.sortedWith(compareByDescending<NewsCluster> { it.isHot }.thenByDescending { it.lastUpdatedAt })
    }

    fun canonicalUrl(raw: String): String {
        val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return raw.trim().removeSuffix("/")
        val host = uri.host?.removePrefix("www.")?.lowercase(Locale.ROOT) ?: return raw.trim().removeSuffix("/")
        val path = uri.path.orEmpty().trimEnd('/').ifBlank { "/" }
        val queryParts = uri.query.orEmpty().split('&').mapNotNull { part ->
            val key = part.substringBefore('=', part).lowercase(Locale.ROOT)
            if (key.isBlank() || key.startsWith("utm_") || key in TRACKING_QUERY_KEYS) null else part
        }.sorted()
        val query = if (queryParts.isEmpty()) "" else "?${queryParts.joinToString("&")}"
        return "${uri.scheme?.lowercase(Locale.ROOT) ?: "https"}://$host$path$query"
    }

    private fun belongsTo(cluster: MutableCluster, item: Prepared): Boolean {
        if (cluster.items.any { it.canonical == item.canonical }) return true
        val itemTime = item.item.publishedAt
        val nearest = cluster.items.mapNotNull { it.item.publishedAt?.let { time -> Duration.between(time, itemTime ?: time).abs() } }.minOrNull()
        if (nearest != null && nearest.toHours() > MAX_CLUSTER_WINDOW_HOURS) return false
        return cluster.items.any { existing ->
            val titleScore = jaccard(existing.titleTokens, item.titleTokens)
            val shared = existing.titleTokens.intersect(item.titleTokens).size
            val summaryScore = jaccard(existing.summaryTokens, item.summaryTokens)
            (titleScore >= 0.62 && shared >= 2) || (titleScore >= 0.48 && shared >= 3 && summaryScore >= 0.18)
        }
    }

    private fun isHot(items: List<FeedItem>): Boolean {
        val cutoff = Instant.now().minusSeconds(HOT_WINDOW_MINUTES * 60)
        val recent = items.filter { (it.publishedAt ?: Instant.EPOCH) >= cutoff }
        return recent.size >= 3 || (recent.size >= 2 && recent.map { it.sourceId }.distinct().size >= 2)
    }

    private fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val inter = a.intersect(b).size
        if (inter == 0) return 0.0
        return inter.toDouble() / a.union(b).size.toDouble()
    }

    private fun distinctiveTokens(text: String): Set<String> = normalize(text).split(Regex("\\s+")).asSequence()
        .filter { it.length >= 3 && it !in STOP_WORDS }
        .toSet()

    private fun normalize(text: String): String {
        val noDiacritics = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
        return noDiacritics.replace(Regex("https?://\\S+"), " ").replace(Regex("[^a-z0-9]+"), " ").trim()
    }

    private fun clusterId(items: List<FeedItem>): String {
        val seed = items.map { canonicalUrl(it.url) }.sorted().joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray())
        return "cluster-" + digest.take(10).joinToString("") { "%02x".format(it) }
    }

    private data class Prepared(val item: FeedItem, val canonical: String, val titleTokens: Set<String>, val summaryTokens: Set<String>)
    private data class MutableCluster(val items: MutableList<Prepared>)

    private val TRACKING_QUERY_KEYS = setOf("fbclid", "gclid", "mc_cid", "mc_eid", "ref", "referrer", "output", "share")
    private val STOP_WORDS = setOf(
        "a", "ao", "aos", "as", "o", "os", "um", "uma", "uns", "umas", "de", "do", "da", "dos", "das", "em", "no", "na", "nos", "nas",
        "por", "para", "com", "sem", "sob", "sobre", "entre", "ate", "apos", "que", "quem", "como", "quando", "onde", "qual", "quais", "se", "e", "ou", "mas",
        "tambem", "mais", "menos", "ja", "ainda", "muito", "muita", "muitos", "muitas", "pode", "podem", "deve", "devem", "vai", "vao", "foi", "foram", "era", "eram",
        "tem", "têm", "ter", "sao", "são", "ser", "esta", "está", "estao", "estão", "disse", "diz", "segundo", "nesta", "neste", "nessa", "nesse", "isso", "hoje", "ontem", "agora",
        "new", "news", "the", "and", "for", "from", "with", "after", "before", "says", "said", "has", "have", "will", "this", "that"
    )
}
