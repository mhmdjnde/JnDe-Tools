package com.example.ytsaverbyjnde

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class InstagramUrlNormalizerTest {

    @Test
    fun normalizesReelShareLink() {
        assertEquals(
            "https://www.instagram.com/reel/C9X123abcde/",
            normalizeInstagramMediaUrl("https://www.instagram.com/reel/C9X123abcde/?igsh=MzRlODBiNWFlZA=="),
        )
    }

    @Test
    fun normalizesPostShareLink() {
        assertEquals(
            "https://www.instagram.com/p/DBc123xyz89/",
            normalizeInstagramMediaUrl("https://instagram.com/p/DBc123xyz89/?img_index=1"),
        )
    }

    @Test
    fun normalizesInstagrAmLink() {
        assertEquals(
            "https://www.instagram.com/p/DBc123xyz89/",
            normalizeInstagramMediaUrl("https://instagr.am/p/DBc123xyz89/"),
        )
    }

    @Test
    fun rejectsProfileLinks() {
        assertThrows(IllegalArgumentException::class.java) {
            normalizeInstagramMediaUrl("https://www.instagram.com/mhmdjnde/")
        }
    }
}
