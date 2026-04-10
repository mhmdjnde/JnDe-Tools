package com.example.ytsaverbyjnde

import java.net.URI

fun normalizeInstagramMediaUrl(rawUrl: String): String {
    val trimmed = rawUrl.trim()
    val uri = runCatching { URI(trimmed) }.getOrNull()
        ?: throw IllegalArgumentException("That doesn't look like a valid Instagram link.")

    val host = uri.host?.lowercase().orEmpty().removePrefix("www.")
    val pathSegments = uri.path.orEmpty().split('/').filter { it.isNotBlank() }

    if (host != "instagram.com" && host != "instagr.am") {
        throw IllegalArgumentException("Paste a full Instagram reel or post link from the Share button.")
    }

    if (pathSegments.size < 2) {
        throw IllegalArgumentException("Paste a full Instagram reel or post link from the Share button.")
    }

    val kind = when (pathSegments.first().lowercase()) {
        "p" -> "p"
        "reel", "reels" -> "reel"
        "tv" -> "tv"
        else -> null
    } ?: throw IllegalArgumentException("Paste an Instagram reel or post link, not a profile link.")

    val code = pathSegments.getOrNull(1)?.takeIf { it.isNotBlank() }
        ?: throw IllegalArgumentException("Paste a full Instagram reel or post link from the Share button.")

    return "https://www.instagram.com/$kind/$code/"
}
