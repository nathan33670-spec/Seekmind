package fr.corsicajack.seekmind.core.database.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.core.model.AppFamily
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

/** SettingsRepository on DataStore Preferences (ticket M1-05). */
@RunWith(AndroidJUnit4::class)
class SettingsRepositoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val file: File = context.preferencesDataStoreFile("settings-test-${System.nanoTime()}")
    private val dataStore = PreferenceDataStoreFactory.create(scope = scope) { file }
    private val repository = SettingsRepository(dataStore)

    @After
    fun clean() {
        scope.cancel()
        file.delete()
    }

    @Test
    fun defaultsAreThoseOfTheSpecs() = runBlocking {
        val settings = repository.current()
        assertThat(settings).isEqualTo(SettingsRepository.DEFAULTS)
        assertThat(settings.consent).isNull()
        assertThat(
            settings.enabledFamilies,
        ).containsExactly(AppFamily.MESSAGING, AppFamily.BROWSERS, AppFamily.SOCIAL_MEDIA, AppFamily.OTHER)
        assertThat(settings.pausedUntil).isNull()
        assertThat(settings.retentionDays).isEqualTo(90)
        assertThat(settings.storageBudgetBytes).isEqualTo(500L * 1024 * 1024)
        assertThat(settings.lockEnabled).isTrue()
        assertThat(settings.lockGraceMs).isEqualTo(300_000L)
        assertThat(settings.activeModelId).isEqualTo("fr")
        assertThat(settings.historyEnabled).isTrue()
    }

    @Test
    fun everySettingRoundTrips() = runBlocking {
        repository.acceptConsent(Consent(version = 2, acceptedAt = 1_000))
        repository.setEnabledFamilies(setOf(AppFamily.BROWSERS, AppFamily.OTHER))
        repository.setPausedUntil(Long.MAX_VALUE)
        repository.setRetentionDays(null)
        repository.setStorageBudgetBytes(100L * 1024 * 1024)
        repository.setLockEnabled(false)
        repository.setLockGraceMs(0)
        repository.setActiveModelId("multi")
        repository.setHistoryEnabled(false)
        assertThat(repository.current()).isEqualTo(
            Settings(
                consent = Consent(2, 1_000),
                enabledFamilies = setOf(AppFamily.BROWSERS, AppFamily.OTHER),
                pausedUntil = Long.MAX_VALUE,
                retentionDays = null,
                storageBudgetBytes = 100L * 1024 * 1024,
                lockEnabled = false,
                lockGraceMs = 0,
                activeModelId = "multi",
                historyEnabled = false,
            ),
        )
        assertThat(repository.consent.first()).isEqualTo(Consent(2, 1_000))
    }

    @Test
    fun edgeCases() = runBlocking {
        repository.setEnabledFamilies(emptySet())
        assertThat(repository.enabledFamilies.first()).isEmpty() // every family off is a valid choice
        repository.setPausedUntil(5_000)
        repository.setPausedUntil(null)
        assertThat(repository.pausedUntil.first()).isNull()
        repository.setRetentionDays(30)
        assertThat(repository.current().retentionDays).isEqualTo(30)
        repository.acceptConsent(Consent(1, 1_000))
        repository.withdrawConsent()
        assertThat(repository.consent.first()).isNull()
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.setRetentionDays(0) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.setStorageBudgetBytes(0) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.setLockGraceMs(-1) } }
    }

    @Test
    fun onlyTheKnownKeysAreEverWritten() = runBlocking {
        repository.acceptConsent(Consent(1, 1_000))
        repository.setEnabledFamilies(setOf(AppFamily.OTHER))
        repository.setPausedUntil(1)
        repository.setRetentionDays(7)
        repository.setStorageBudgetBytes(1)
        repository.setLockEnabled(true)
        repository.setLockGraceMs(1)
        repository.setActiveModelId("fr")
        repository.setHistoryEnabled(true)
        val written = dataStore.data.first().asMap().keys.map { it.name }.toSet()
        assertThat(written).isEqualTo(SettingsRepository.KEYS)
        // INV-06: switches, numbers and identifiers only; nothing captured can be stored here.
        assertThat(SettingsRepository.KEYS).hasSize(10)
    }
}
