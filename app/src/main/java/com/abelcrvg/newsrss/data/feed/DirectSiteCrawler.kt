package com.abelcrvg.newsrss.data.feed

import com.abelcrvg.newsrss.core.feed.FeedItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.time.Instant

/** Source-specific direct HTML crawler engine. It reads card metadata only; article pages are fetched when opened. */
class DirectSiteCrawler(private val config: Config) {
    data class Config(
        val sourceId: String,
        val homeUrl: String,
        val allowedHosts: Set<String>,
        val articlePath: (String) -> Boolean,
        val selectors: String,
        val excludedText: Set<String> = emptySet()
    )

    suspend fun crawl(): Result<List<FeedItem>> = withContext(Dispatchers.IO) {
        runCatching {
            val document = fetch(config.homeUrl)
            val found = linkedMapOf<String, FeedItem>()
            document.select(config.selectors).forEach { link ->
                val url = link.absUrl("href").trim()
                if (!isArticle(url)) return@forEach
                val context = link.closest("article, li, [class*=card], [class*=item], [class*=story], [class*=tile], [class*=post]") ?: link
                val title = titleOf(link, context) ?: return@forEach
                if (config.excludedText.any { title.equals(it, ignoreCase = true) }) return@forEach
                val item = FeedItem(
                    stableId(config.sourceId, url),
                    config.sourceId,
                    title,
                    url,
                    summaryOf(context, title),
                    dateOf(link, context),
                    imageOf(context, config.homeUrl)
                )
                val old = found[url]
                if (old == null || (old.imageUrl == null && item.imageUrl != null)) found[url] = item
            }
            found.values
                .distinctBy { it.url }
                .sortedWith(compareByDescending<FeedItem> { it.publishedAt ?: Instant.EPOCH }.thenBy { it.title.lowercase() })
        }
    }

    private fun fetch(url: String) = Jsoup.connect(url)
        .userAgent(USER_AGENT)
        .referrer("https://www.google.com/")
        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        .header("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.7,en;q=0.5")
        .timeout(TIMEOUT)
        .followRedirects(true)
        .get()

    private fun isArticle(url: String): Boolean = runCatching {
        val uri = URI(url)
        val host = uri.host?.lowercase()?.removePrefix("www.") ?: return@runCatching false
        val allowed = config.allowedHosts.map { it.lowercase().removePrefix("www.") }.toSet()
        host in allowed && config.articlePath(uri.path.orEmpty().lowercase())
    }.getOrDefault(false)

    private fun titleOf(link: Element, context: Element): String? = sequenceOf(
        context.selectFirst("h1,h2,h3,h4,h5,h6")?.text(),
        link.text(),
        link.attr("aria-label"),
        link.attr("title"),
        link.selectFirst("img")?.attr("alt")
    ).mapNotNull(::clean).firstOrNull { it.length in MIN_TITLE..MAX_TITLE }

    private fun summaryOf(context: Element, title: String): String? = context.select("p,[class*=summary],[class*=subtitle],[class*=description],[class*=resumo],[class*=subtitulo]")
        .mapNotNull { clean(it.text()) }
        .firstOrNull { it.length >= 30 && !it.equals(title, true) }

    private fun imageOf(context: Element, base: String): String? {
        val candidates = context.select("img,source,noscript")
            .asSequence()
            .filter(::usableImageElement)
            .flatMap { node ->
                sequenceOf(
                    node.attr("src"), node.attr("data-src"), node.attr("data-lazy-src"),
                    node.attr("data-original"), node.attr("data-image"), node.attr("data-image-url"),
                    node.attr("data-lazy"), node.attr("data-url"), node.attr("srcset"),
                    node.attr("data-srcset"), node.attr("data-original-srcset"), node.attr("data-lazy-srcset"),
                    node.attr("content")
                ) + sequenceOf(node.attr("style")).flatMap(::extractCssImages)
            }
            .flatMap(::expandImageCandidates)
            .mapNotNull { absolute(it, base) }
            .filter(::usableImage)
            .toList()
        return candidates.firstOrNull()
    }

    private fun usableImageElement(node: Element): Boolean {
        val attributes = buildString {
            append(node.className()).append(' ').append(node.id()).append(' ')
            append(node.attr("alt")).append(' ').append(node.attr("title")).append(' ')
            append(node.attr("src")).append(' ').append(node.attr("data-src")).append(' ')
            node.parents().take(4).forEach { append(it.className()).append(' ').append(it.id()).append(' ') }
        }.lowercase()
        val noise = listOf(
            "avatar", "author", "profile", "user-photo", "user_photo", "headshot", "portrait",
            "logo", "icon", "favicon", "sprite", "social", "share", "whatsapp", "twitter", "facebook",
            "linkedin", "telegram", "instagram", "youtube", "tiktok", "qr-code", "qrcode", "tracking"
        )
        if (noise.any(attributes::contains)) return false
        return hasMinimumImageSize(node)
    }

    private fun hasMinimumImageSize(node: Element): Boolean {
        val width = dimension(node.attr("width"))
        val height = dimension(node.attr("height"))
        if (width != null && width < MIN_IMAGE_WIDTH) return false
        if (height != null && height < MIN_IMAGE_HEIGHT) return false

        val style = node.attr("style")
        val styleWidth = Regex("(?:^|;)\\s*width\\s*:\\s*(\\d+)px", RegexOption.IGNORE_CASE).find(style)?.groupValues?.get(1)?.toIntOrNull()
        val styleHeight = Regex("(?:^|;)\\s*height\\s*:\\s*(\\d+)px", RegexOption.IGNORE_CASE).find(style)?.groupValues?.get(1)?.toIntOrNull()
        if (styleWidth != null && styleWidth < MIN_IMAGE_WIDTH) return false
        if (styleHeight != null && styleHeight < MIN_IMAGE_HEIGHT) return false
        return true
    }

    private fun dimension(value: String): Int? = value.trim().removeSuffix("px").toIntOrNull()

    private fun extractCssImages(style: String): Sequence<String> = Regex("url\\(\\s*['\\\"]?([^'\\\")]+)['\\\"]?\\s*\\)", RegexOption.IGNORE_CASE)
        .findAll(style).map { it.groupValues[1] }

    private fun expandImageCandidates(value: String): Sequence<String> {
        val cleanValue = value.trim()
        if (cleanValue.isBlank()) return emptySequence()
        if (cleanValue.contains(",") && (cleanValue.contains(" ") || cleanValue.contains("w") || cleanValue.contains("x"))) {
            return cleanValue.split(',').asSequence()
                .map { it.trim().split(Regex("\\s+")).firstOrNull().orEmpty() }
                .filter(String::isNotBlank)
                .toList()
                .asReversed()
                .asSequence()
        }
        return sequenceOf(cleanValue)
    }

    private fun dateOf(link: Element, context: Element): Instant? =
        (link.select("time[datetime],time[content],[itemprop=datePublished]") + context.select("time[datetime],time[content],[itemprop=datePublished]"))
            .asSequence()
            .flatMap { sequenceOf(it.attr("datetime"), it.attr("content"), it.attr("datePublished")) }
            .mapNotNull(::parseDate)
            .firstOrNull()

    private fun parseDate(value: String?): Instant? = value?.trim()?.takeIf(String::isNotBlank)?.let {
        runCatching { Instant.parse(it) }.getOrNull()
            ?: runCatching { java.time.OffsetDateTime.parse(it).toInstant() }.getOrNull()
            ?: runCatching { java.time.ZonedDateTime.parse(it).toInstant() }.getOrNull()
            ?: runCatching { java.time.ZonedDateTime.parse(it, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }.getOrNull()
            ?: runCatching { java.time.LocalDateTime.parse(it, java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME).atZone(java.time.ZoneId.systemDefault()).toInstant() }.getOrNull()
    }

    private fun clean(value: String?): String? = value?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotBlank() }

    private fun absolute(value: String, base: String): String? = if (value.isBlank()) null else
        runCatching { URI(base).resolve(value.trim()).toString() }.getOrNull()
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }

    private fun usableImage(url: String): Boolean {
        val lower = url.lowercase()
        if (listOf(
                "logo", "avatar", "author", "profile", "headshot", "portrait", "icon", "sprite", "pixel",
                "tracking", "placeholder", "favicon", "1x1", "transparent", "whatsapp", "twitter", "facebook",
                "linkedin", "telegram", "instagram", "youtube", "tiktok", "qrcode", "qr-code"
            ).any(lower::contains)) return false
        val dimensions = Regex("(?:^|[^0-9])([0-9]{2,4})[xX]([0-9]{2,4})(?:[^0-9]|$)").find(lower)
        if (dimensions != null) {
            val width = dimensions.groupValues[1].toIntOrNull() ?: return false
            val height = dimensions.groupValues[2].toIntOrNull() ?: return false
            if (width < MIN_IMAGE_WIDTH || height < MIN_IMAGE_HEIGHT) return false
        }
        val queryWidth = Regex("(?:[?&](?:w|width)=)(\\d{2,4})", RegexOption.IGNORE_CASE).find(lower)?.groupValues?.get(1)?.toIntOrNull()
        val queryHeight = Regex("(?:[?&](?:h|height)=)(\\d{2,4})", RegexOption.IGNORE_CASE).find(lower)?.groupValues?.get(1)?.toIntOrNull()
        if (queryWidth != null && queryWidth < MIN_IMAGE_WIDTH) return false
        if (queryHeight != null && queryHeight < MIN_IMAGE_HEIGHT) return false
        return true
    }

    private fun stableId(source: String, url: String) = (source + url).hashCode().toUInt().toString(16)

    companion object {
        private const val TIMEOUT = 12_000
        private const val MIN_TITLE = 12
        private const val MAX_TITLE = 240
        private const val MIN_IMAGE_WIDTH = 240
        private const val MIN_IMAGE_HEIGHT = 120
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/140.0.0.0 Mobile Safari/537.36 NewsRSS/0.3"
    }
}
