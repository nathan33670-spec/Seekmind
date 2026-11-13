package fr.corsicajack.seekmind.engine.capture.pause

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.core.database.settings.SettingsRepository
import fr.corsicajack.seekmind.core.testing.FakeClock
import java.io.File
import java.nio.file.Files
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test

/** docs/specs/capture.md §7 (ticket M2-07). */
class PauseControllerTest {
    private val paris = ZoneId.of("Europe/Paris")
    private val directory: File = Files.createTempDirectory("pause").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val settings = SettingsRepository(
        PreferenceDataStoreFactory.create(scope = scope) { File(directory, "settings.preferences_pb") },
    )
    private val clock = FakeClock(now = at("2026-11-12T22:15"))
    private val controller = PauseController(settings, clock, { paris })

    @After
    fun tearDown() {
        scope.cancel()
        directory.deleteRecursively()
    }

    @Test
    fun `one hour, tomorrow morning and without limit`() {
        val now = at("2026-11-12T22:15")
        assertThat(PauseController.until(PauseDuration.ONE_HOUR, now, paris)).isEqualTo(now + 3_600_000)
        assertThat(PauseController.until(PauseDuration.UNTIL_TOMORROW_MORNING, now, paris))
            .isEqualTo(at("2026-11-13T06:00"))
        assertThat(PauseController.until(PauseDuration.INDEFINITELY, now, paris)).isEqualTo(Long.MAX_VALUE)
    }

    @Test
    fun `tomorrow morning is the next calendar day, even after midnight`() {
        assertThat(PauseController.until(PauseDuration.UNTIL_TOMORROW_MORNING, at("2026-11-13T00:30"), paris))
            .isEqualTo(at("2026-11-14T06:00"))
    }

    @Test
    fun `tomorrow morning is 6 a_m local time across a change of time`() {
        // The night of 28 to 29 March 2026: clocks go forward in Paris.
        val end = PauseController.until(PauseDuration.UNTIL_TOMORROW_MORNING, at("2026-03-28T23:00"), paris)
        assertThat(ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(end), paris).toLocalTime().hour).isEqualTo(6)
        assertThat(end - at("2026-03-28T23:00")).isEqualTo(6 * 3_600_000L)
    }

    @Test
    fun `pause and resume write the setting`() = runBlocking<Unit> {
        controller.pause(PauseDuration.ONE_HOUR)
        assertThat(settings.current().pausedUntil).isEqualTo(clock.now + 3_600_000)
        controller.resume()
        assertThat(settings.current().pausedUntil).isNull()
    }

    @Test
    fun `the tile's tap toggles between active and paused without limit`() = runBlocking<Unit> {
        controller.toggle()
        assertThat(settings.current().pausedUntil).isEqualTo(Long.MAX_VALUE)
        controller.toggle()
        assertThat(settings.current().pausedUntil).isNull()
    }

    @Test
    fun `a tap after a pause has ended pauses again`() = runBlocking<Unit> {
        controller.pause(PauseDuration.ONE_HOUR)
        clock.advance(3_600_000)
        controller.toggle()
        assertThat(settings.current().pausedUntil).isEqualTo(Long.MAX_VALUE)
    }

    private fun at(local: String): Long = java.time.LocalDateTime.parse(local).atZone(paris).toInstant().toEpochMilli()
}
