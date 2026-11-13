package fr.corsicajack.seekmind.feature.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.core.database.settings.Settings
import fr.corsicajack.seekmind.core.database.settings.SettingsRepository
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SecuritySettingsViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var dir: File
    private lateinit var settings: SettingsRepository
    private var secure = true
    private var sessionsKeptOpen = 0

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        dir = Files.createTempDirectory("settings").toFile()
        settings = SettingsRepository(PreferenceDataStoreFactory.create { File(dir, "test.preferences_pb") })
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        dir.deleteRecursively()
    }

    /** DataStore writes on its own thread: waits for the value (or fails after 5 s). */
    private suspend fun awaitSettings(condition: (Settings) -> Boolean) {
        withContext(Dispatchers.Default) { withTimeout(5_000) { settings.settings.first(condition) } }
    }

    private fun viewModel() = SecuritySettingsViewModel(settings, { secure }, { sessionsKeptOpen++ })

    @Test
    fun `the state shows the settings and the device security`() = runTest(dispatcher) {
        val state = viewModel().state.filterNotNull().first()
        assertThat(state).isEqualTo(SecurityUiState(lockEnabled = true, graceMs = 5 * 60_000L, deviceSecure = true))
    }

    @Test
    fun `turning the lock off and on again keeps the current session open`() = runTest(dispatcher) {
        val model = viewModel()
        model.setLockEnabled(false)
        awaitSettings { !it.lockEnabled }
        assertThat(sessionsKeptOpen).isEqualTo(0)

        model.setLockEnabled(true)
        awaitSettings { it.lockEnabled }
        assertThat(sessionsKeptOpen).isEqualTo(1)
    }

    @Test
    fun `without a secure screen lock the lock cannot be turned on`() = runTest(dispatcher) {
        settings.setLockEnabled(false)
        secure = false
        val model = viewModel()
        model.setLockEnabled(true)
        assertThat(settings.current().lockEnabled).isFalse()
        assertThat(model.state.filterNotNull().first().deviceSecure).isFalse()
    }

    @Test
    fun `refresh reads the device security again`() = runTest(dispatcher) {
        secure = false
        val model = viewModel()
        assertThat(model.state.filterNotNull().first().deviceSecure).isFalse()
        secure = true
        model.refresh()
        assertThat(model.state.filterNotNull().first { it.deviceSecure }.deviceSecure).isTrue()
    }

    @Test
    fun `the grace delay is one of the choices`() = runTest(dispatcher) {
        val model = viewModel()
        model.setGraceMs(60_000L)
        awaitSettings { it.lockGraceMs == 60_000L }
        val refused = runCatching { model.setGraceMs(42L) }
        assertThat(refused.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
    }
}
