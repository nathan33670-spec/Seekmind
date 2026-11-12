package fr.corsicajack.seekmind.core.database

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.core.database.key.AndroidKeystoreWrapper
import fr.corsicajack.seekmind.core.database.settings.Consent
import fr.corsicajack.seekmind.core.database.settings.SettingsRepository
import fr.corsicajack.seekmind.core.database.store.NewCapture
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** "Erase everything" (ticket M1-06, EF-PRIV-09). */
@RunWith(AndroidJUnit4::class)
class WipeServiceTest {
    private val test = TestDatabase("wipe")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val settingsFile = test.context.preferencesDataStoreFile("wipe-settings-${System.nanoTime()}")
    private val settings = SettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { settingsFile })

    @After
    fun clean() {
        scope.cancel()
        settingsFile.delete()
        test.delete()
    }

    @Test
    fun afterWipeNothingRemainsAndTheNextOpeningIsANewDatabase() = runBlocking {
        test.delete()
        val handle = test.handle()
        handle.captures.insert(
            NewCapture(1_000, 1_000, "com.example.a", null, null, null, "texte à effacer", 1, 1),
            emptyList(),
        )
        settings.acceptConsent(Consent(1, 1_000))
        settings.setRetentionDays(7)
        val keyFile = File(test.context.noBackupFilesDir, "wipe.key")
        val databaseDir = test.file().parentFile!!
        assertThat(keyFile.exists()).isTrue()

        DatabaseWipeService(test.context, test.keyStore, settings, current = {
            handle
        }, name = "wipe.db").wipeEverything()

        assertThat(databaseDir.listFiles()!!.filter { it.name.startsWith("wipe.db") }).isEmpty()
        assertThat(keyFile.exists()).isFalse()
        assertThat(AndroidKeystoreWrapper("seekmind.db.kek.test.wipe").exists()).isFalse()
        assertThat(settings.current()).isEqualTo(SettingsRepository.DEFAULTS)

        val fresh = test.handle()
        assertThat(fresh.captures.stats().captureCount).isEqualTo(0)
        assertThat(keyFile.exists()).isTrue()
    }
}
