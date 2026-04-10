package com.example.ytsaverbyjnde

import org.junit.Assert.assertTrue
import org.junit.Test

class InstagramEmbedParserTest {

    @Test
    fun extractsShortcodeMediaFromGqlDataPayload() {
        val html = """
            <html>
            <body>
            <script>
            window.__additionalDataLoaded("extra", {\"gql_data\":{\"shortcode_media\":{\"id\":\"12345\",\"display_url\":\"https://cdn.example.com/photo.jpg\"}}});
            </script>
            </body>
            </html>
        """.trimIndent()

        val mediaJson = extractInstagramEmbedRawObject(html)

        assertTrue(mediaJson.contains("12345"))
        assertTrue(mediaJson.contains("cdn.example.com/photo.jpg"))
    }

    @Test
    fun extractsShortcodeMediaFromDirectPayload() {
        val html = """
            <html>
            <body>
            <script type="application/json">
            {"shortcode_media":{"id":"abcde","display_url":"https://cdn.example.com/post.jpg"}}
            </script>
            </body>
            </html>
        """.trimIndent()

        val mediaJson = extractInstagramEmbedRawObject(html)

        assertTrue(mediaJson.contains("abcde"))
        assertTrue(mediaJson.contains("cdn.example.com/post.jpg"))
    }
}
