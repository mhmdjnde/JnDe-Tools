package com.example.ytsaverbyjnde

import java.net.URI

fun normalizeYoutubeVideoUrl(rawUrl: String): String {
    val trimmed = rawUrl.trim()
    val rawId = trimmed.takeIf { it.matches(Regex("""[a-zA-Z0-9_-]{11}""")) }
    if (rawId != null) return "https://www.youtube.com/watch?v=$rawId"

    val uri = runCatching { URI(trimmed) }.getOrNull()
        ?: throw IllegalArgumentException("That doesn't look like a valid YouTube link.")
    val host = uri.host?.lowercase().orEmpty().removePrefix("www.")
    val pathSegments = uri.path.orEmpty().split('/').filter { it.isNotBlank() }
    val queryParameters = parseQueryParameters(uri.rawQuery)

    val videoId = when {
        host == "youtu.be" -> pathSegments.firstOrNull()
        host == "youtube.com" || host.endsWith(".youtube.com") -> when (pathSegments.firstOrNull()) {
            "watch" -> queryParameters["v"]
            "shorts", "live", "embed", "v" -> pathSegments.getOrNull(1)
            else -> null
        }
        else -> null
    }?.takeIf { it.matches(Regex("""[a-zA-Z0-9_-]{11}""")) }

    return videoId?.let { "https://www.youtube.com/watch?v=$it" }
        ?: throw IllegalArgumentException("Paste a full YouTube video link from the Share button.")
}

private fun parseQueryParameters(query: String?): Map<String, String> {
    if (query.isNullOrBlank()) return emptyMap()
    return query.split('&')
        .mapNotNull { part ->
            val pieces = part.split('=', limit = 2)
            val key = pieces.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            key to pieces.getOrElse(1) { "" }
        }
        .toMap()
}
