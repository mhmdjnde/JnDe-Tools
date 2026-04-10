package com.example.ytsaverbyjnde

import java.net.URI
import java.net.URLDecoder

fun normalizeFacebookVideoUrl(rawUrl: String): String {
    val trimmed = rawUrl.trim()
    val uri = runCatching { URI(trimmed) }.getOrNull()
        ?: throw IllegalArgumentException("That doesn't look like a valid Facebook link.")

    val host = uri.host?.lowercase().orEmpty().removePrefix("www.")
    val queryParameters = parseFacebookQueryParameters(uri.rawQuery)

    if (host == "l.facebook.com" || host == "lm.facebook.com") {
        val decodedTarget = queryParameters["u"]?.let { URLDecoder.decode(it, Charsets.UTF_8.name()) }
            ?: throw IllegalArgumentException("Paste a full Facebook video link from the Share button.")
        return normalizeFacebookVideoUrl(decodedTarget)
    }

    val pathSegments = uri.path.orEmpty().split('/').filter { it.isNotBlank() }

    return when {
        host == "fb.watch" -> {
            val code = pathSegments.firstOrNull()
                ?: throw IllegalArgumentException("Paste a full Facebook video link from the Share button.")
            "https://fb.watch/$code/"
        }
        host == "facebook.com" || host.endsWith(".facebook.com") -> {
            when (pathSegments.firstOrNull()) {
                "watch" -> {
                    val videoId = queryParameters["v"]
                        ?: throw IllegalArgumentException("Paste a full Facebook watch link from the Share button.")
                    "https://www.facebook.com/watch/?v=$videoId"
                }
                "video.php" -> {
                    val videoId = queryParameters["v"]
                        ?: throw IllegalArgumentException("Paste a full Facebook video link from the Share button.")
                    "https://www.facebook.com/video.php?v=$videoId"
                }
                "story.php" -> {
                    val storyId = queryParameters["story_fbid"]
                    val ownerId = queryParameters["id"]
                    if (storyId.isNullOrBlank() || ownerId.isNullOrBlank()) {
                        throw IllegalArgumentException("Paste a full Facebook story link from the Share button.")
                    }
                    "https://www.facebook.com/story.php?story_fbid=$storyId&id=$ownerId"
                }
                else -> {
                    val normalizedPath = pathSegments.joinToString("/")
                    if (!looksLikeFacebookVideoPath(pathSegments)) {
                        throw IllegalArgumentException("Paste a Facebook video, reel, watch, or post link.")
                    }
                    "https://www.facebook.com/$normalizedPath/"
                }
            }
        }
        else -> throw IllegalArgumentException("Paste a full Facebook video link from the Share button.")
    }
}

private fun looksLikeFacebookVideoPath(pathSegments: List<String>): Boolean {
    if (pathSegments.isEmpty()) return false
    val first = pathSegments.first()
    return first == "reel" ||
        first == "watchparty" ||
        first == "share" ||
        pathSegments.contains("videos") ||
        pathSegments.contains("posts") ||
        pathSegments.contains("permalink") ||
        pathSegments.contains("permalink.php") ||
        pathSegments.contains("groups")
}

private fun parseFacebookQueryParameters(query: String?): Map<String, String> {
    if (query.isNullOrBlank()) return emptyMap()
    return query.split('&')
        .mapNotNull { part ->
            val pieces = part.split('=', limit = 2)
            val key = pieces.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            key to pieces.getOrElse(1) { "" }
        }
        .toMap()
}
