package fr.corsicajack.seekmind.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.SkipWhenEmpty
import org.gradle.api.tasks.TaskAction

/**
 * Fails when a Compose source hard-codes user-visible text instead of using a string resource.
 *
 * Android Lint's `HardcodedText` only covers XML layouts, so this task closes the gap for Compose
 * (ticket M0-02). It is wired as a dependency of `lint` by [AndroidComposeConventionPlugin].
 */
@CacheableTask
abstract class HardcodedUiTextTask : DefaultTask() {
    @get:InputFiles
    @get:SkipWhenEmpty
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val findings = sources.files
            .filter { it.extension == "kt" }
            .sortedBy { it.path }
            .flatMap { file -> findHardcodedUiText(file.readLines()).map { "${file.path}:${it.first}: ${it.second}" } }
        report.get().asFile.writeText(findings.joinToString("\n"))
        if (findings.isNotEmpty()) {
            throw GradleException(
                "Hard-coded user-visible text found; use a string resource (FR + EN):\n" +
                    findings.joinToString("\n"),
            )
        }
    }

    companion object {
        /** A string literal containing at least one letter, passed as UI text. */
        private val patterns = listOf(
            Regex("""\bText\(\s*"[^"]*\p{L}"""),
            Regex("""\b(text|label|title|contentDescription|placeholder)\s*=\s*"[^"]*\p{L}"""),
        )

        /** Returns (line number, line) pairs; lines ending with `// ui-text-ok` are allowed. */
        fun findHardcodedUiText(lines: List<String>): List<Pair<Int, String>> = lines.mapIndexedNotNull { index, line ->
            val code = line.trim()
            when {
                code.startsWith("//") || code.startsWith("*") -> null
                code.endsWith("// ui-text-ok") -> null
                patterns.any { it.containsMatchIn(line) } -> (index + 1) to code
                else -> null
            }
        }
    }
}
