package fr.corsicajack.seekmind.feature.settings

import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.core.database.settings.SettingsRepository
import fr.corsicajack.seekmind.core.database.store.StorageStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test

/** Settings → Données (ticket M6-06, EF-PRIV-08, EF-PRIV-09). */
class DataSettingsViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val settings = MutableStateFlow(SettingsRepository.DEFAULTS)
    private val ranges = mutableListOf<Pair<Long, Long>>()
    private val packages = mutableListOf<String>()
    private var count = 3

    private fun model() = DataSettingsViewModel(
        settings = settings,
        stats = { StorageStats(count, 4_096, 1_000) },
        appsWithCaptures = { listOf(APP) },
        setRetentionDays = { settings.value = settings.value.copy(retentionDays = it) },
        setBudgetBytes = { settings.value = settings.value.copy(storageBudgetBytes = it) },
        deleteRange = { from, to ->
            ranges += from to to
            count = 0
        },
        deletePackage = { packages += it },
        now = { NOW },
        io = main.dispatcher,
    )

    @Test
    fun `the retention is written for the next maintenance`() = runTest {
        val model = model()
        model.setRetention(30)
        assertThat(settings.value.retentionDays).isEqualTo(30)
        model.setRetention(null)
        assertThat(settings.value.retentionDays).isNull()
        assertThat(model.state.filterNotNull().first().retentionDays).isNull()
    }

    @Test
    fun `only the offered retentions and budgets are accepted`() {
        val model = model()
        assertThrows(IllegalArgumentException::class.java) { model.setRetention(12) }
        assertThrows(IllegalArgumentException::class.java) { model.setBudget(1) }
    }

    @Test
    fun `deleting a period deletes what was seen from then to now, and reloads the usage`() = runTest {
        val model = model()
        model.refresh()
        assertThat(model.state.filterNotNull().first().stats!!.captureCount).isEqualTo(3)
        model.delete(DeletePeriod.LAST_7_DAYS)
        assertThat(ranges).containsExactly(NOW - 7 * 24 * 3_600_000L to NOW)
        val state = model.state.filterNotNull().first()
        assertThat(state.stats!!.captureCount).isEqualTo(0)
        assertThat(state.message).isEqualTo(DataMessage.PeriodDeleted(DeletePeriod.LAST_7_DAYS))
        model.messageShown()
        assertThat(model.state.filterNotNull().first().message).isNull()
    }

    @Test
    fun `deleting an application deletes its package`() = runTest {
        val model = model()
        model.delete(APP)
        assertThat(packages).containsExactly(APP.packageName)
        assertThat(model.state.filterNotNull().first().message).isEqualTo(DataMessage.AppDeleted(APP))
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
        val APP = InstalledApp("com.example.notes", "Notes")
    }
}
