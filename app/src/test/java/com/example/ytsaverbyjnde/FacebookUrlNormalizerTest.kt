package com.example.ytsaverbyjnde

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FacebookUrlNormalizerTest {

    @Test
    fun normalizesWatchUrl() {
        assertEquals(
            "https://www.facebook.com/watch/?v=647537299265662",
            normalizeFacebookVideoUrl("https://www.facebook.com/watch/?v=647537299265662&mibextid=wwXIfr"),
        )
    }

    @Test
    fun normalizesFbWatchShortUrl() {
        assertEquals(
            "https://fb.watch/abc123xyz/",
            normalizeFacebookVideoUrl("https://fb.watch/abc123xyz/?mibextid=wwXIfr"),
        )
    }

    @Test
    fun normalizesReelUrl() {
        assertEquals(
            "https://www.facebook.com/reel/1195289147628387/",
            normalizeFacebookVideoUrl("https://www.facebook.com/reel/1195289147628387/?mibextid=wwXIfr"),
        )
    }

    @Test
    fun normalizesVideoPhpUrl() {
        assertEquals(
            "https://www.facebook.com/video.php?v=1234567890",
            normalizeFacebookVideoUrl("https://www.facebook.com/video.php?v=1234567890&fs=e"),
        )
    }

    @Test
    fun normalizesStoryUrl() {
        assertEquals(
            "https://www.facebook.com/story.php?story_fbid=98765&id=43210",
            normalizeFacebookVideoUrl("https://www.facebook.com/story.php?story_fbid=98765&id=43210&mibextid=wwXIfr"),
        )
    }

    @Test
    fun decodesFacebookLinkShim() {
        assertEquals(
            "https://www.facebook.com/watch/?v=647537299265662",
            normalizeFacebookVideoUrl(
                "https://l.facebook.com/l.php?u=https%3A%2F%2Fwww.facebook.com%2Fwatch%2F%3Fv%3D647537299265662"
            ),
        )
    }

    @Test
    fun rejectsProfileLink() {
        assertThrows(IllegalArgumentException::class.java) {
            normalizeFacebookVideoUrl("https://www.facebook.com/zuck/")
        }
    }
}
