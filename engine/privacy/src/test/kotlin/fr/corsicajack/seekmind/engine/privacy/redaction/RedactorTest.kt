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

    private class Case(val kind: String, val expected: String, val text: String, val redacted: String)

    private val cases: List<Case> by lazy {
        val dir = checkNotNull(System.getProperty("seekmind.testdata")) { "seekmind.testdata not set" }
        File(dir, "sensitive_cases.tsv").readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { line -> line.split('\t').let { Case(it[0], it[1], it[2], it[3]) } }
    }

    @Test
    fun `the reference set covers every kind`() {
        assertThat(cases.map { it.kind }.toSet()).containsExactly("card", "iban", "nir", "other")
        assertThat(cases.count { it.expected == "masked" }).isAtLeast(60)
    }

    @Test
    fun `valid numbers are masked, invalid ones and tracking numbers are kept`() {
        for (case in cases) {
            val result = redactor.redact(case.text)
            // Exact text: a partial redaction (some digits of the number left) fails here.
            assertWithMessage(
                "%s, %s: %s",
                case.kind,
                case.expected,
                case.text,
            ).that(result.text).isEqualTo(case.redacted)
            assertThat(result.maskedCount).isEqualTo(if (case.expected == "masked") 1 else 0)
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
        for (case in cases) {
            val text = case.text
            val once = redactor.redact(text).text
            val twice = redactor.redact(once)
            assertWithMessage("idempotent on %s", text).that(twice.text).isEqualTo(once)
            assertThat(twice.maskedCount).isEqualTo(0)
        }
        val all = cases.joinToString("\n") { it.text }
        val once = redactor.redact(all).text
        assertThat(redactor.redact(once).text).isEqualTo(once)
    }

    @Test
    fun `text without numbers is returned as is`() {
        val text = "Le restaurant italien de la rue Paradis sert des pâtes à la truffe."
        assertThat(redactor.redact(text)).isEqualTo(fr.corsicajack.seekmind.core.model.Redaction(text, 0))
    }
}
