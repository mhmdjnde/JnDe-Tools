package com.example.ytsaverbyjnde

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "YTSaver"

data class ExtractedVideo(val streamUrl: String, val title: String, val quality: String)

/**
 * Extracts a direct MP4 stream URL by calling YouTube's Innertube /player API.
 *
 * YouTube returns LOGIN_REQUIRED or UNPLAYABLE for certain clients even on PUBLIC
 * videos — so we never stop early on those statuses. We try every client and only
 * give up after all of them have been exhausted.
 *
 * Client order (most → least likely to return direct non-ciphered URLs):
 *  1. TVHTML5_SIMPLY_EMBEDDED_PLAYER  — embedded-player context; YouTube MUST
 *     allow unauthenticated access for public videos (used when videos are embedded
 *     on third-party websites). Most reliable for our use case.
 *  2. ANDROID_VR  — Oculus Quest headset; VR pipeline returns direct URLs.
 *  3. IOS         — iOS YouTube app.
 *  4. ANDROID     — Android YouTube app.
 *  5. MWEB        — Mobile web; simple client, different backend path.
 *  6. TVHTML5     — Smart TV; another non-standard pipeline.
 */
suspend fun extractYouTubeVideo(rawUrl: String): ExtractedVideo = withContext(Dispatchers.IO) {
    val videoId = parseVideoId(rawUrl)
        ?: throw IllegalArgumentException(
            "Couldn't find a video ID in this link.\nMake sure you copied a full YouTube URL."
        )

    Log.d(TAG, "Starting extraction for videoId=$videoId")

    val clients = listOf(
        tvEmbeddedClient(videoId),   // #1 — most reliable for public videos
        androidVrClient(videoId),    // #2
        iosClient(videoId),          // #3
        androidClient(videoId),      // #4
        mwebClient(videoId),         // #5
        tvHtml5Client(videoId),      // #6
    )

    for (client in clients) {
        try {
            Log.d(TAG, "Trying ${client.name}…")
            val json   = postToPlayerApi(client.payload, client.headers)
            val result = parsePlayerResponse(json, client.name)
            if (result != null) {
                Log.d(TAG, "[${client.name}] SUCCESS — ${result.title} @ ${result.quality}")
                return@withContext result
            }
            Log.d(TAG, "[${client.name}] No direct URL found, trying next client…")
        } catch (e: BadVideoException) {
            // The video itself is the problem (deleted / invalid) — no point retrying
            throw Exception(e.message)
        } catch (e: Exception) {
            Log.w(TAG, "[${client.name}] ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    // All clients exhausted
    throw Exception(
        "Couldn't get a download link for this video.\n" +
        "Check your internet connection and try again, or try a different video."
    )
}

// ── Exception for hard failures (video doesn't exist) ─────────────────────

private class BadVideoException(message: String) : Exception(message)

// ── Client definitions ─────────────────────────────────────────────────────

private data class InnertubeClient(
    val name:    String,
    val payload: String,
    val headers: Map<String, String>,
)

/**
 * TVHTML5_SIMPLY_EMBEDDED_PLAYER (client ID 85)
 * Simulates a third-party embedded player. YouTube grants unauthenticated access
 * to public videos via this client — it's what powers YouTube embeds on blogs/websites.
 */
private fun tvEmbeddedClient(videoId: String): InnertubeClient {
    val payload = JSONObject().apply {
        put("videoId", videoId)
        put("context", JSONObject().apply {
            put("client", JSONObject().apply {
                put("clientName",    "TVHTML5_SIMPLY_EMBEDDED_PLAYER")
                put("clientVersion", "2.0")
                put("hl", "en")
                put("gl", "US")
                put("utcOffsetMinutes", 0)
            })
            put("thirdParty", JSONObject().apply {
                put("embedUrl", "https://www.youtube.com/")
            })
        })
        put("racyCheckOk",    true)
        put("contentCheckOk", true)
    }.toString()

    return InnertubeClient(
        name    = "TVHTML5_SIMPLY_EMBEDDED_PLAYER",
        payload = payload,
        headers = mapOf(
            "User-Agent"               to "Mozilla/5.0 (SMART-TV; LINUX; Tizen 6.0) AppleWebKit/538.1 (KHTML, like Gecko) Version/6.0 TV Safari/538.1",
            "X-Youtube-Client-Name"    to "85",
            "X-Youtube-Client-Version" to "2.0",
            "Content-Type"             to "application/json; charset=UTF-8",
            "Origin"                   to "https://www.youtube.com",
            "Referer"                  to "https://www.youtube.com/",
            "Accept-Language"          to "en-US,en;q=0.9",
        ),
    )
}

/** ANDROID_VR — Oculus Quest; VR pipeline avoids cipher protection. */
private fun androidVrClient(videoId: String) = InnertubeClient(
    name    = "ANDROID_VR",
    payload = basePayload(videoId, JSONObject().apply {
        put("clientName",        "ANDROID_VR")
        put("clientVersion",     "1.60.19")
        put("deviceMake",        "Oculus")
        put("deviceModel",       "Quest 3")
        put("androidSdkVersion", 32)
        put("osName",            "Android")
        put("osVersion",         "12L")
        put("hl", "en"); put("gl", "US")
    }),
    headers = mapOf(
        "User-Agent"               to "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
        "X-Youtube-Client-Name"    to "28",
        "X-Youtube-Client-Version" to "1.60.19",
        "Content-Type"             to "application/json; charset=UTF-8",
        "Origin"                   to "https://www.youtube.com",
        "Accept-Language"          to "en-US,en;q=0.9",
    ),
)

/** IOS — YouTube iOS app. */
private fun iosClient(videoId: String) = InnertubeClient(
    name    = "IOS",
    payload = basePayload(videoId, JSONObject().apply {
        put("clientName",    "IOS")
        put("clientVersion", "19.09.3")
        put("deviceModel",   "iPhone16,2")
        put("osVersion",     "17.5.1.21F90")
        put("hl", "en"); put("gl", "US")
    }),
    headers = mapOf(
        "User-Agent"               to "com.google.ios.youtube/19.09.3 (iPhone16,2; U; CPU iOS 17_5_1 like Mac OS X)",
        "X-Youtube-Client-Name"    to "5",
        "X-Youtube-Client-Version" to "19.09.3",
        "Content-Type"             to "application/json; charset=UTF-8",
        "Origin"                   to "https://www.youtube.com",
        "Accept-Language"          to "en-US,en;q=0.9",
    ),
)

/** ANDROID — standard YouTube Android app. */
private fun androidClient(videoId: String) = InnertubeClient(
    name    = "ANDROID",
    payload = basePayload(videoId, JSONObject().apply {
        put("clientName",        "ANDROID")
        put("clientVersion",     "19.09.37")
        put("androidSdkVersion", 30)
        put("hl", "en"); put("gl", "US")
    }),
    headers = mapOf(
        "User-Agent"               to "com.google.android.youtube/19.09.37 (Linux; U; Android 10; Pixel 3) gzip",
        "X-Youtube-Client-Name"    to "3",
        "X-Youtube-Client-Version" to "19.09.37",
        "Content-Type"             to "application/json; charset=UTF-8",
        "Origin"                   to "https://www.youtube.com",
        "Accept-Language"          to "en-US,en;q=0.9",
    ),
)

/** MWEB — YouTube mobile website; different server-side pipeline. */
private fun mwebClient(videoId: String) = InnertubeClient(
    name    = "MWEB",
    payload = basePayload(videoId, JSONObject().apply {
        put("clientName",    "MWEB")
        put("clientVersion", "2.20231121.08.00")
        put("hl", "en"); put("gl", "US")
    }),
    headers = mapOf(
        "User-Agent"               to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.6099.230 Mobile Safari/537.36",
        "X-Youtube-Client-Name"    to "2",
        "X-Youtube-Client-Version" to "2.20231121.08.00",
        "Content-Type"             to "application/json; charset=UTF-8",
        "Origin"                   to "https://www.youtube.com",
        "Referer"                  to "https://www.youtube.com/",
        "Accept-Language"          to "en-US,en;q=0.9",
    ),
)

/** TVHTML5 — smart TV client. */
private fun tvHtml5Client(videoId: String) = InnertubeClient(
    name    = "TVHTML5",
    payload = basePayload(videoId, JSONObject().apply {
        put("clientName",    "TVHTML5")
        put("clientVersion", "7.20231121.08.00")
        put("hl", "en"); put("gl", "US")
    }),
    headers = mapOf(
        "User-Agent"               to "Mozilla/5.0 (SMART-TV; LINUX; Tizen 6.0) AppleWebKit/538.1 (KHTML, like Gecko) Version/6.0 TV Safari/538.1",
        "X-Youtube-Client-Name"    to "7",
        "X-Youtube-Client-Version" to "7.20231121.08.00",
        "Content-Type"             to "application/json; charset=UTF-8",
        "Origin"                   to "https://www.youtube.com",
        "Referer"                  to "https://www.youtube.com/",
        "Accept-Language"          to "en-US,en;q=0.9",
    ),
)

private fun basePayload(videoId: String, clientObject: JSONObject): String =
    JSONObject().apply {
        put("videoId", videoId)
        put("context", JSONObject().apply { put("client", clientObject) })
        put("racyCheckOk",    true)
        put("contentCheckOk", true)
    }.toString()

// ── HTTP POST ──────────────────────────────────────────────────────────────

private fun postToPlayerApi(payload: String, headers: Map<String, String>): JSONObject {
    val conn = (URL("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
        .openConnection() as HttpURLConnection).apply {
        requestMethod           = "POST"
        connectTimeout          = 20_000
        readTimeout             = 20_000
        instanceFollowRedirects = true
        headers.forEach { (k, v) -> setRequestProperty(k, v) }
        doOutput = true
        outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
    }

    val code = conn.responseCode
    val body = try {
        (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
    } catch (e: Exception) { "" }

    if (!body.trimStart().startsWith("{"))
        throw Exception("Non-JSON response from YouTube (HTTP $code)")

    return JSONObject(body)
}

// ── Response parsing ───────────────────────────────────────────────────────

private fun parsePlayerResponse(json: JSONObject, clientName: String): ExtractedVideo? {
    val status = json.optJSONObject("playabilityStatus")?.optString("status", "") ?: ""
    val reason = json.optJSONObject("playabilityStatus")?.optString("reason", "") ?: ""

    Log.d(TAG, "[$clientName] status=$status reason=${reason.take(80)}")

    when (status) {
        // Hard failure — the video itself doesn't exist/is deleted. No client will fix this.
        "ERROR" -> throw BadVideoException(reason.ifBlank { "This video is unavailable." })

        // Soft failures — a different client might succeed. Do NOT stop here.
        "LOGIN_REQUIRED", "UNPLAYABLE", "AGE_CHECK_REQUIRED" -> {
            Log.w(TAG, "[$clientName] $status — trying next client")
            return null
        }
    }

    val title = json.optJSONObject("videoDetails")
        ?.optString("title", "YouTube Video") ?: "YouTube Video"

    val streamingData = json.optJSONObject("streamingData") ?: run {
        Log.w(TAG, "[$clientName] No streamingData")
        return null
    }

    val formats = streamingData.optJSONArray("formats") ?: JSONArray()
    Log.d(TAG, "[$clientName] progressive formats: ${formats.length()}")

    for (i in 0 until formats.length()) {
        val f = formats.getJSONObject(i)
        Log.d(TAG, "[$clientName]  itag=${f.optInt("itag")} " +
                "hasUrl=${f.has("url")} " +
                "hasCipher=${f.has("signatureCipher") || f.has("cipher")}")
    }

    return pickBestDirectMp4(formats, title)
}

private fun pickBestDirectMp4(formats: JSONArray, title: String): ExtractedVideo? {
    var bestUrl    = ""
    var bestHeight = 0
    var bestLabel  = ""

    for (i in 0 until formats.length()) {
        val f        = formats.getJSONObject(i)
        val mimeType = f.optString("mimeType", "")
        if (!mimeType.startsWith("video/mp4")) continue

        val url = f.optString("url", "")
        if (url.isBlank()) continue  // cipher-protected — skip

        val height = f.optInt("height", 0)
        if (height > bestHeight) {
            bestHeight = height
            bestUrl    = url
            bestLabel  = f.optString("qualityLabel", "${height}p")
        }
    }

    return if (bestUrl.isNotBlank())
        ExtractedVideo(streamUrl = bestUrl, title = title, quality = bestLabel)
    else
        null
}

// ── Video ID extraction ────────────────────────────────────────────────────

fun parseVideoId(url: String): String? {
    val pattern = Regex(
        """(?:youtu\.be/|youtube\.com/(?:watch\?(?:.*&)?v=|shorts/|live/|embed/|v/))([a-zA-Z0-9_-]{11})"""
    )
    return pattern.find(url.trim())?.groupValues?.get(1)
}
