package com.example.ytsaverbyjnde

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class YouTubeUrlNormalizerTest {

    @Test
    fun normalizesYoutuBeShareLink() {
        assertEquals(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            normalizeYoutubeVideoUrl("https://youtu.be/dQw4w9WgXcQ?si=abc123"),
        )
    }

    @Test
    fun normalizesWatchShareLink() {
        assertEquals(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            normalizeYoutubeVideoUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ&feature=shared"),
        )
    }

    @Test
    fun normalizesShortsShareLink() {
        assertEquals(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            normalizeYoutubeVideoUrl("https://youtube.com/shorts/dQw4w9WgXcQ?si=xyz"),
        )
    }

    @Test
    fun acceptsRawVideoId() {
        assertEquals(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            normalizeYoutubeVideoUrl("dQw4w9WgXcQ"),
        )
    }

    @Test
    fun rejectsNonVideoLink() {
        assertThrows(IllegalArgumentException::class.java) {
            normalizeYoutubeVideoUrl("https://www.youtube.com/@channel")
        }
    }
}
