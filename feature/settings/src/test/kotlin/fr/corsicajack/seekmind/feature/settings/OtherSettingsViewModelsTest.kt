package fr.corsicajack.seekmind.feature.settings

import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.core.database.settings.Consent
import fr.corsicajack.seekmind.core.database.settings.DISCLOSURE_VERSION
import fr.corsicajack.seekmind.core.database.settings.SettingsRepository
import fr.corsicajack.seekmind.core.model.CaptureStatus
import fr.corsicajack.seekmind.core.model.DropReason
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/** Mémorisation, Recherche and Diagnostic of Settings (ticket M6-06). */
class OtherSettingsViewModelsTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val settings = MutableStateFlow(SettingsRepository.DEFAULTS.copy(consent = Consent(DISCLOSURE_VERSION, 1)))

    @Test
    fun `withdrawing the consent stops the capture and offers to erase the data`() = runTest {
        val pauses = mutableListOf<PauseChoice>()
        var resumed = 0
        val model = MemorisationSettingsViewModel(
            status = MutableStateFlow(CaptureStatus.Active(null)),
            settings = settings,
            pause = { pauses += it },
            resume = { resumed++ },
            withdrawConsent = { settings.value = settings.value.copy(consent = null) },
        )
        assertThat(model.state.filterNotNull().first().consented).isTrue()
        model.pause(PauseChoice.ONE_HOUR)
        model.resume()
        assertThat(pauses).containsExactly(PauseChoice.ONE_HOUR)
        assertThat(resumed).isEqualTo(1)
        model.withdraw()
        val state = model.state.filterNotNull().first()
        assertThat(state.consented).isFalse()
        assertThat(state.offerWipe).isTrue()
        model.dismissWipeOffer()
        assertThat(model.state.filterNotNull().first().offerWipe).isFalse()
    }

    @Test
    fun `the history switch and its deletion`() = runTest {
        var cleared = 0
        val model = HistorySettingsViewModel(
            settings = settings,
            setEnabled = { settings.value = settings.value.copy(historyEnabled = it) },
            clear = { cleared++ },
        )
        model.setEnabled(false)
        assertThat(model.state.filterNotNull().first().enabled).isFalse()
        model.clear()
        assertThat(cleared).isEqualTo(1)
        assertThat(model.state.filterNotNull().first().cleared).isTrue()
    }

    @Test
    fun `the diagnostic counts the day and the last 7 days`() = runTest {
        val model = DiagnosticSettingsViewModel(
            metrics = { from, to ->
                assertThat(from).isEqualTo(TODAY.minusDays(6))
                assertThat(to).isEqualTo(TODAY)
                mapOf(
                    TODAY.toString() to mapOf("capture_stored" to 5L, "capture_dropped_password_field" to 2L),
                    TODAY.minusDays(3).toString() to mapOf("capture_stored" to 7L),
                )
            },
            pendingFragments = { 4 },
            today = { TODAY },
            io = main.dispatcher,
        )
        model.refresh()
        val state = model.state.filterNotNull().first()
        assertThat(state.today.stored).isEqualTo(5)
        assertThat(state.today.dropped).containsExactly(DropReason.PASSWORD_FIELD to 2L)
        assertThat(state.lastWeek.stored).isEqualTo(12)
        assertThat(state.pendingFragments).isEqualTo(4)
    }

    @Test
    fun `the shared diagnostic holds counter names and numbers only`() {
        val counters = Counters.of(
            mapOf(
                "capture_stored" to 5L,
                // A key that would carry text never reaches the report.
                "Mot de passe: hunter2" to 1L,
                "https://example.org/private" to 1L,
            ),
        )
        val report = DiagnosticSettingsViewModel.report(DiagnosticUiState(counters, counters, 3), "0.1.0", 34)
        assertThat(report).doesNotContain("hunter2")
        assertThat(report).doesNotContain("example.org")
        val line = Regex("^(\\[[a-z0-9_]+]|([a-z0-9_]+=[0-9A-Za-z._]+)( [a-z0-9_]+=[0-9A-Za-z._]+)*)$")
        report.lines().forEach { assertThat(it).matches(line.pattern) }
        assertThat(report).contains("capture_stored=5")
    }

    private companion object {
        val TODAY: LocalDate = LocalDate.of(2026, 11, 21)
    }
}
