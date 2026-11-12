package fr.corsicajack.seekmind.engine.privacy.redaction

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import fr.corsicajack.seekmind.core.model.config.PrivacyConfig
import java.io.File
import org.junit.Test

/** docs/specs/confidentialite.md §5 on testdata/sensitive_cases.tsv (ticket M2-05). */
class RedactorTest {
    private val mark = PrivacyConfig().redactionMark
    private val redactor = Redactor(mark)

    private val cases: List<Triple<String, String, String>> by lazy {
        val dir = checkNotNull(System.getProperty("seekmind.testdata")) { "seekmind.testdata not set" }
        File(dir, "sensitive_cases.tsv").readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { line -> line.split('\t').let { Triple(it[0], it[1], it[2]) } }
    }

    @Test
    fun `the reference set covers every kind`() {
        assertThat(cases.map { it.first }.toSet()).containsExactly("card", "iban", "nir", "other")
        assertThat(cases.count { it.second == "masked" }).isAtLeast(60)
    }

    @Test
    fun `valid numbers are masked, invalid ones and tracking numbers are kept`() {
        for ((kind, expected, text) in cases) {
            val result = redactor.redact(text)
            when (expected) {
                "masked" -> {
                    assertWithMessage("%s should be masked: %s", kind, text).that(result.maskedCount).isEqualTo(1)
                    assertWithMessage("mark in %s", text).that(result.text).contains(mark)
                    // What is left of the text has fewer digits than the number had: it was replaced whole.
                    assertWithMessage("digits left in %s", result.text)
                        .that(result.text.count(Char::isDigit)).isLessThan(4)
                }

                "intact" -> {
                    assertWithMessage("%s should stay: %s", kind, text).that(result.text).isEqualTo(text)
                    assertThat(result.maskedCount).isEqualTo(0)
                }

                else -> error("unknown expectation $expected")
            }
        }
    }

    @Test
    fun `the number is replaced, its surroundings kept`() {
        assertThat(redactor.redact("Carte 4111 1111 1111 1111 enregistrée").text).isEqualTo("Carte $mark enregistrée")
        assertThat(redactor.redact("Virement vers FR14 2004 1010 0505 0001 3M02 606 EUR").text)
            .isEqualTo("Virement vers $mark EUR")
    }

    @Test
    fun `several numbers in one text are all masked`() {
        val result = redactor.redact("4111111111111111 puis DE89370400440532013000 puis 2 69 05 49 588 157 80")
        assertThat(result.maskedCount).isEqualTo(3)
        assertThat(result.text).isEqualTo("$mark puis $mark puis $mark")
    }

    @Test
    fun `redact is idempotent`() {
        for ((_, _, text) in cases) {
            val once = redactor.redact(text).text
            val twice = redactor.redact(once)
            assertWithMessage("idempotent on %s", text).that(twice.text).isEqualTo(once)
            assertThat(twice.maskedCount).isEqualTo(0)
        }
        val all = cases.joinToString("\n") { it.third }
        val once = redactor.redact(all).text
        assertThat(redactor.redact(once).text).isEqualTo(once)
    }

    @Test
    fun `text without numbers is returned as is`() {
        val text = "Le restaurant italien de la rue Paradis sert des pâtes à la truffe."
        assertThat(redactor.redact(text)).isEqualTo(fr.corsicajack.seekmind.core.model.Redaction(text, 0))
    }
}
