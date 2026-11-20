package fr.corsicajack.seekmind.core.common

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RecurringLinesTest {
    private val lines = RecurringLines(window = 10, share = 0.6, maxLineChars = 20, minCaptures = 5)

    private fun screen(i: Int) = "Accueil\nRechercher\nArticle numéro $i du jour\nProfil"

    @Test
    fun aLineOnMostScreensIsRecurring() {
        repeat(5) { lines.record(APP, screen(it)) }
        val recurring = lines.recurringIn(APP)
        assertThat(recurring("Accueil")).isTrue()
        assertThat(recurring("  Profil ")).isTrue()
        assertThat(recurring("Article numéro 1 du jour")).isFalse()
        assertThat(recurring("Inconnue")).isFalse()
    }

    @Test
    fun nothingIsRecurringBeforeEnoughCaptures() {
        repeat(4) { lines.record(APP, screen(it)) }
        assertThat(lines.recurringIn(APP)("Accueil")).isFalse()
    }

    @Test
    fun theShareIsStrictlyAboveTheThreshold() {
        repeat(4) { lines.record(APP, "Texte autre $it") }
        repeat(6) { lines.record(APP, "Menu\nTexte $it") }
        // 6 screens out of 10 is exactly 60 %: not more than 60 %.
        assertThat(lines.recurringIn(APP)("Menu")).isFalse()
        // Window of 10: the oldest screen, without « Menu », goes out: 7 out of 10.
        lines.record(APP, "Menu")
        assertThat(lines.recurringIn(APP)("Menu")).isTrue()
    }

    @Test
    fun aLongLineIsNeverRecurring() {
        repeat(6) { lines.record(APP, "Une ligne bien trop longue pour un menu") }
        assertThat(lines.recurringIn(APP)("Une ligne bien trop longue pour un menu")).isFalse()
    }

    @Test
    fun aLineCountsOncePerCaptureAndPerPackage() {
        repeat(5) { lines.record(APP, "Accueil\nAccueil\nTexte $it") }
        lines.record(OTHER, "Accueil")
        assertThat(lines.recurringIn(OTHER)("Accueil")).isFalse()
        assertThat(lines.recurringIn(APP)("Accueil")).isTrue()
    }

    @Test
    fun theTestIsASnapshot() {
        repeat(5) { lines.record(APP, screen(it)) }
        val before = lines.recurringIn(APP)
        repeat(10) { lines.record(APP, "Autre $it") }
        assertThat(before("Accueil")).isTrue()
        assertThat(lines.recurringIn(APP)("Accueil")).isFalse()
    }

    @Test
    fun clearForgetsEverything() {
        repeat(5) { lines.record(APP, screen(it)) }
        lines.clear()
        assertThat(lines.recurringIn(APP)("Accueil")).isFalse()
    }

    private companion object {
        const val APP = "com.example.app"
        const val OTHER = "com.example.other"
    }
}
