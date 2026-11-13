package fr.corsicajack.seekmind.engine.capture.meta

import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.core.common.IntRect
import fr.corsicajack.seekmind.core.model.ScreenMeta
import fr.corsicajack.seekmind.core.model.TreeExtraction
import fr.corsicajack.seekmind.core.testing.FakeClock
import org.junit.Test

/** docs/specs/capture.md §5.1 (ticket M2-04). */
class ScreenMetaReaderTest {
    private val clock = FakeClock(now = 1_794_441_600_000L)
    private val activities = ActivityTracker({ _, c -> c.endsWith("Activity") })
    private val reader = ScreenMetaReader(activities, clock)

    @Test
    fun `the meta carries the package, the activity, the window and the time`() {
        activities.onWindowStateChanged(APP, "$APP.MainActivity")
        assertThat(reader.read(APP, windowId = 7, windowTitle = "Boîte de réception")).isEqualTo(
            ScreenMeta(APP, "$APP.MainActivity", "Boîte de réception", 7, 1_794_441_600_000L),
        )
    }

    @Test
    fun `the window title is cleaned and capped at 200 characters`() {
        assertThat(reader.read(APP, 1, "  Deux\n lignes ").windowTitle).isEqualTo("Deux lignes")
        assertThat(reader.read(APP, 1, "t".repeat(300)).windowTitle).hasLength(200)
        assertThat(reader.read(APP, 1, "   ").windowTitle).isNull()
    }

    @Test
    fun `without a window title the first heading is used`() {
        val meta = reader.read(APP, 1, null)
        assertThat(
            reader.completed(meta, extraction(listOf("Résultats", "Filtres"))).windowTitle,
        ).isEqualTo("Résultats")
        assertThat(reader.completed(meta, extraction(emptyList())).windowTitle).isNull()
        val titled = reader.read(APP, 1, "Fenêtre")
        assertThat(reader.completed(titled, extraction(listOf("Résultats"))).windowTitle).isEqualTo("Fenêtre")
    }

    private fun extraction(headings: List<String>) = TreeExtraction(
        lines = emptyList(),
        headings = headings,
        hasPasswordField = false,
        viewIds = emptySet(),
        nodeCount = 1,
        truncated = false,
        textChars = 0,
        textCoverage = 0f,
        windowBounds = IntRect(0, 0, 100, 100),
        durationMs = 1,
    )

    private companion object {
        const val APP = "com.example.app"
    }
}
