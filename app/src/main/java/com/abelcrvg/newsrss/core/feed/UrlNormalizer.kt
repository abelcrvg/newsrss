package com.abelcrvg.newsrss.core.feed

import java.net.URI

object UrlNormalizer {
    private val trackingParameters = setOf(
        "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content", "utm_id",
        "gclid", "fbclid", "mc_cid", "mc_eid", "ref", "ref_src", "source"
    )

    fun normalize(url: String): String {
        val raw = url.trim()
        if (raw.isBlank()) return raw
        return runCatching {
            val uri = URI(raw)
            val query = uri.rawQuery
                ?.split('&')
                ?.mapNotNull { part ->
                    val key = part.substringBefore('=').lowercase()
                    if (key in trackingParameters || key.isBlank()) null else part
                }
                ?.joinToString("&")
                ?.takeIf { it.isNotBlank() }
            URI(uri.scheme?.lowercase(), uri.userInfo, uri.host?.lowercase(), uri.port, uri.path?.replace(Regex("/{2,}"), "/")?.removeSuffix("/"), query, null).toString()
        }.getOrDefault(raw.removeSuffix("/"))
    }
}
