package com.abelcrvg.newsrss.core.media

/** Shared URL-level filter for tiny, social, profile and decorative images. */
object ImageQuality {
    private const val MIN_WIDTH = 240
    private const val MIN_HEIGHT = 120

    fun sanitize(url: String?): String? = url?.trim()?.takeIf(::isUsable)

    fun isUsable(url: String): Boolean {
        val lower = url.trim().lowercase()
        if (lower.isBlank() || (!lower.startsWith("http://") && !lower.startsWith("https://"))) return false
        val noise = listOf(
            "logo", "avatar", "author", "profile", "headshot", "portrait", "icon", "sprite", "pixel",
            "tracking", "placeholder", "favicon", "1x1", "transparent", "whatsapp", "twitter", "facebook",
            "linkedin", "telegram", "instagram", "youtube", "tiktok", "qrcode", "qr-code"
        )
        if (noise.any(lower::contains)) return false

        Regex("(?:^|[^0-9])([0-9]{2,4})[xX]([0-9]{2,4})(?:[^0-9]|$)").find(lower)?.let { match ->
            val width = match.groupValues[1].toIntOrNull() ?: return false
            val height = match.groupValues[2].toIntOrNull() ?: return false
            if (width < MIN_WIDTH || height < MIN_HEIGHT) return false
        }
        Regex("(?:[?&](?:w|width)=)(\\d{2,4})", RegexOption.IGNORE_CASE).find(lower)?.groupValues?.get(1)?.toIntOrNull()?.let {
            if (it < MIN_WIDTH) return false
        }
        Regex("(?:[?&](?:h|height)=)(\\d{2,4})", RegexOption.IGNORE_CASE).find(lower)?.groupValues?.get(1)?.toIntOrNull()?.let {
            if (it < MIN_HEIGHT) return false
        }
        return true
    }
}
