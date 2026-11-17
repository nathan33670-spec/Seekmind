package fr.corsicajack.seekmind.engine.monetization.license

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The inputs of the activation (docs/specs/monetisation.md §5.4, §5.5, ticket M8-05). */
class ActivationCodeTest {
    private val code = TestVectors.named("valid").token

    @Test
    fun `a link of the application gives its code`() {
        assertThat(ActivationCode.fromLink("seekmind://activate?t=$code")).isEqualTo(code)
    }

    @Test
    fun `any other link gives nothing`() {
        val refused = listOf(
            null,
            "",
            "seekmind://activate",
            "seekmind://activate?t=",
            "seekmind://other?t=$code",
            "seekmind://activate?x=$code",
            "seekmind://activate?t=$code&t=other",
            "seekmind://activate?t=$code#fragment",
            "seekmind://activate?t=%41$code",
            "seekmind://activate?t=" + "a".repeat(TokenVerifier.MAX_LENGTH + 1),
            "https://example.org/a#$code",
            "SEEKMIND://activate?t=$code",
        )
        refused.forEach { assertThat(ActivationCode.fromLink(it)).isNull() }
    }

    @Test
    fun `a pasted code, link or relay link gives the code`() {
        assertThat(ActivationCode.fromInput("  $code\n")).isEqualTo(code)
        assertThat(ActivationCode.fromInput("seekmind://activate?t=$code")).isEqualTo(code)
        assertThat(ActivationCode.fromInput("https://example.org/a#$code")).isEqualTo(code)
    }

    @Test
    fun `an empty paste gives nothing, other text is left to the verifier`() {
        assertThat(ActivationCode.fromInput("   ")).isNull()
        assertThat(ActivationCode.fromInput("https://example.org/a#")).isNull()
        assertThat(ActivationCode.fromInput("hello")).isEqualTo("hello")
    }
}
