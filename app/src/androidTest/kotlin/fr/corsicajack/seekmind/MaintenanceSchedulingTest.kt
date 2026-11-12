package fr.corsicajack.seekmind

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.core.database.maintenance.MaintenanceScheduler
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The application plans its daily maintenance at start-up (ticket M1-06), in both variants: the
 * `offline` one, without ACCESS_NETWORK_STATE, initialises WorkManager without failing.
 */
@RunWith(AndroidJUnit4::class)
class MaintenanceSchedulingTest {
    @Test
    fun maintenanceIsPlannedAtStartUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val works = WorkManager.getInstance(context).getWorkInfosForUniqueWork(MaintenanceScheduler.UNIQUE_NAME).get()
        assertThat(works).hasSize(1)
        assertThat(works.single().state).isEqualTo(WorkInfo.State.ENQUEUED)
    }
}
