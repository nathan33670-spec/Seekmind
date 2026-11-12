package fr.corsicajack.seekmind.engine.capture.url

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/** docs/specs/capture.md §5.2 step 3, as a table of cases (ticket M2-04). */
class UrlNormalizerTest {
    private val accepted = listOf(
        // Without a scheme, as most browsers display it.
        "example.com" to "https://example.com",
        "www.example.com" to "https://www.example.com",
        "fr.wikipedia.org/wiki/Corse" to "https://fr.wikipedia.org/wiki/Corse",
        "example.com:8080/path" to "https://example.com:8080/path",
        // With a scheme and a path, query or fragment kept as displayed.
        "https://example.com/a/B?c=D#e" to "https://example.com/a/B?c=D#e",
        "http://example.com/" to "http://example.com/",
        "HTTPS://Example.COM/Path" to "https://example.com/Path",
        "https://192.168.1.10/admin" to "https://192.168.1.10/admin",
        "https://café.example/menu" to "https://café.example/menu",
        // Surrounding whitespace is trimmed; user information is dropped.
        "  example.com/page  " to "https://example.com/page",
        "https://user:secret@example.com/inbox" to "https://example.com/inbox",
    )

    private val rejected = listOf(
        // Search terms typed or displayed in the bar.
        "recette pâtes truffe",
        "météo",
        "pâtes.truffe recette",
        "",
        "   ",
        // Schemes other than the web's.
        "file:///sdcard/page.html",
        "content://media/external/file.1",
        "javascript:alert(document.cookie)",
        "data:text/html,<p>a.b</p>",
        "mailto:someone@example.com",
        "intent://scan.example#Intent;scheme=zxing;end",
        "chrome://settings.privacy",
        "ftp://files.example.com/a.txt",
        // Malformed hosts and ports.
        "https://",
        "https:///path.html",
        "https://.example.com",
        "https://example..com",
        "https://example.com.",
        "https://exa_mple.com",
        "https://example.com:/x",
        "https://example.com:123456/x",
        "https://example.com:80a/x",
        "https://[2001:db8::1]:443/x", // no dot: read as search terms (§5.2)
        "localhost:8080",
        // Control characters.
        "example.com/\u0000x",
        "example.com/\nx",
    )

    @Test
    fun `web addresses are normalised`() {
        for ((input, expected) in accepted) {
            assertWithMessage("normalize(%s)", input).that(UrlNormalizer.normalize(input)).isEqualTo(expected)
        }
    }

    @Test
    fun `search terms, other schemes and malformed addresses are rejected`() {
        for (input in rejected) {
            assertWithMessage("normalize(%s)", input).that(UrlNormalizer.normalize(input)).isNull()
        }
    }

    @Test
    fun `a URL longer than the limit is rejected`() {
        val prefix = "https://example.com/"
        val longest = prefix + "a".repeat(UrlNormalizer.MAX_LENGTH - prefix.length)
        assertWithMessage("at the limit").that(UrlNormalizer.normalize(longest)).isEqualTo(longest)
        assertWithMessage("one more").that(UrlNormalizer.normalize(longest + "a")).isNull()
        // The scheme added to a displayed address counts.
        assertWithMessage("once prefixed")
            .that(UrlNormalizer.normalize("example.com/" + "a".repeat(UrlNormalizer.MAX_LENGTH - 15)))
            .isNull()
    }
}
