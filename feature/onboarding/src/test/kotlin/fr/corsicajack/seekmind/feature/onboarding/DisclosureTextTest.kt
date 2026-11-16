package fr.corsicajack.seekmind.feature.onboarding

import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * The disclosure resources are, word for word, the text of docs/05-conformite-play-store.md §4.2 (FR)
 * and §4.3 (EN): the published declaration and the screen must never drift apart (ticket M6-02).
 */
class DisclosureTextTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "AGENTS.md").isFile }
    private val doc = File(root, "docs/05-conformite-play-store.md").readText()

    private fun strings(dir: String): Map<String, String> =
        Regex("""<string name="(onboarding_disclosure_[a-z0-9_]+)">(.*?)</string>""")
            .findAll(File(root, "feature/onboarding/src/main/res/$dir/strings.xml").readText())
            .associate { it.groupValues[1] to it.groupValues[2].replace("\\'", "'") }

    /** The quoted block of [section], without the markdown marks and the buttons, one line per item. */
    private fun quoted(section: String): List<String> {
        val start = doc.indexOf("### $section")
        val end = doc.indexOf("\n### ", start + 1)
        val text = doc.substring(start, end).lines().filter { it.startsWith(">") }.map { it.removePrefix(">").trim() }
        val items = mutableListOf<String>()
        for (line in text) {
            when {
                line.isEmpty() -> items += ""

                line.startsWith("`[") -> Unit

                line.startsWith("- ") -> items += line.removePrefix("- ")

                // A heading, wholly in bold, is an item of its own even without a blank line after it.
                line.startsWith("**") && line.endsWith("**") -> {
                    items += line
                    items += ""
                }

                items.isEmpty() || items.last().isEmpty() -> {
                    if (items.isNotEmpty()) items.removeAt(items.size - 1)
                    items +=
                        line
                }

                else -> items[items.size - 1] = items.last() + " " + line
            }
        }
        return items.filter { it.isNotEmpty() }.map { it.replace("**", "").trim() }
    }

    private fun check(section: String, dir: String) {
        val res = strings(dir)
        val order = listOf(
            "title", "intro", "reads_title", "reads_1", "reads_2", "why_title", "why", "data_title",
            "data_1", "data_2", "data_3", "not_title", "not", "actions",
        )
        val screen = order.map { res.getValue("onboarding_disclosure_$it") }
        assertWithMessage(
            "§$section against values$dir",
        ).that(screen).containsExactlyElementsIn(quoted(section)).inOrder()
        assertWithMessage("buttons").that(doc.substring(doc.indexOf("### $section")))
            .contains(
                "`[ ${res.getValue(
                    "onboarding_disclosure_refuse",
                )} ]` `[ ${res.getValue("onboarding_disclosure_accept")} ]`",
            )
    }

    @Test
    fun `the French text is the one of the declaration`() = check("4.2 Texte — version 1 — français", "values-fr")

    @Test
    fun `the English text is the one of the declaration`() = check("4.3 Texte — version 1 — anglais", "values")
}
