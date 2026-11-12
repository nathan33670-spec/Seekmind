package fr.corsicajack.seekmind.core.database.maintenance

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.core.common.Clock
import fr.corsicajack.seekmind.core.database.DatabaseState
import fr.corsicajack.seekmind.core.database.TestDatabase
import fr.corsicajack.seekmind.core.database.UnavailableReason
import fr.corsicajack.seekmind.core.database.settings.SettingsRepository
import fr.corsicajack.seekmind.core.database.store.NewCapture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** MaintenanceWorker and its planning (ticket M1-06). */
@RunWith(AndroidJUnit4::class)
class MaintenanceWorkerTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val test = TestDatabase("worker")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val settingsFile = context.preferencesDataStoreFile("worker-settings-${System.nanoTime()}")
    private val settings = SettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { settingsFile })
    private val now = 100 * 86_400_000L
    private val clock = object : Clock {
        override fun nowMillis() = now

        override fun elapsedRealtime() = 0L
    }

    @After
    fun clean() {
        scope.cancel()
        settingsFile.delete()
        test.delete()
    }

    @Test
    fun theWorkerAppliesTheRetentionOfTheSettings() = runBlocking<Unit> {
        test.delete()
        val handle = test.handle()
        val old = handle.captures.insert(
            NewCapture(1_000, 1_000, "com.example.a", null, null, null, "très ancienne", 1, 1),
            emptyList(),
        )
        settings.setRetentionDays(30)
        val result = worker { DatabaseState.Ready(handle) }.doWork()
        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        assertThat(handle.captures.get(old)).isNull()
    }

    @Test
    fun anUnavailableDatabaseIsNotAnError() = runBlocking<Unit> {
        val result = worker { DatabaseState.Unavailable(UnavailableReason.KEY_MISSING) }.doWork()
        assertThat(result).isEqualTo(ListenableWorker.Result.success())
    }

    @Test
    fun theWorkIsDailyOnBatteryNotLowWithoutNetworkConstraint() {
        val request = MaintenanceScheduler.request()
        assertThat(request.workSpec.intervalDuration).isEqualTo(TimeUnit.HOURS.toMillis(24))
        assertThat(request.workSpec.constraints.requiresBatteryNotLow()).isTrue()
        assertThat(request.workSpec.constraints.requiredNetworkType).isEqualTo(NetworkType.NOT_REQUIRED)
        assertThat(request.workSpec.workerClassName).isEqualTo(MaintenanceWorker::class.java.name)
    }

    private fun worker(database: () -> DatabaseState): MaintenanceWorker =
        TestListenableWorkerBuilder<MaintenanceWorker>(context)
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ) = MaintenanceWorker(appContext, workerParameters, database, settings, clock)
                },
            )
            .build()
}
