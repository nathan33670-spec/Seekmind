package fr.corsicajack.seekmind.engine.capture.status

import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.core.database.settings.Consent
import fr.corsicajack.seekmind.core.database.settings.SettingsRepository
import fr.corsicajack.seekmind.core.model.CaptureStatus
import fr.corsicajack.seekmind.core.testing.FakeClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** docs/specs/capture.md §8 (ticket M2-01). */
class CaptureStatusRepositoryTest {
    private val clock = FakeClock()
    private val enabled = MutableStateFlow(true)
    private val settings = MutableStateFlow(SettingsRepository.DEFAULTS.copy(consent = Consent(1, 0)))
    private val repository = CaptureStatusRepository(enabled, settings, clock)

    @Test
    fun `the four states`() = runBlocking<Unit> {
        assertThat(status()).isEqualTo(CaptureStatus.Active(null))

        repository.onCaptureStored(1_234)
        assertThat(status()).isEqualTo(CaptureStatus.Active(1_234))

        settings.value = settings.value.copy(pausedUntil = clock.now + 3_600_000)
        assertThat(status()).isEqualTo(CaptureStatus.Paused(clock.now + 3_600_000))

        settings.value = settings.value.copy(pausedUntil = Long.MAX_VALUE)
        assertThat(status()).isEqualTo(CaptureStatus.Paused(until = null))

        enabled.value = false
        assertThat(status()).isEqualTo(CaptureStatus.ServiceDisabled)

        settings.value = settings.value.copy(consent = null)
        assertThat(status()).isEqualTo(CaptureStatus.ConsentMissing)
    }

    @Test
    fun `consent comes before the service, the service before the pause`() {
        val now = clock.now
        fun resolve(enabled: Boolean, consented: Boolean, pausedUntil: Long?) =
            CaptureStatusRepository.resolve(enabled, consented, pausedUntil, now, lastCaptureAt = null)
        assertThat(resolve(enabled = false, consented = false, pausedUntil = Long.MAX_VALUE))
            .isEqualTo(CaptureStatus.ConsentMissing)
        assertThat(resolve(enabled = false, consented = true, pausedUntil = Long.MAX_VALUE))
            .isEqualTo(CaptureStatus.ServiceDisabled)
        assertThat(resolve(enabled = true, consented = true, pausedUntil = now + 1))
            .isEqualTo(CaptureStatus.Paused(now + 1))
    }

    @Test
    fun `a past pause is over`() {
        val now = clock.now
        assertThat(CaptureStatusRepository.resolve(true, true, pausedUntil = now, now = now, lastCaptureAt = 5))
            .isEqualTo(CaptureStatus.Active(5))
    }

    @Test
    fun `a scheduled pause ends on time`() = runTest {
        settings.value = settings.value.copy(pausedUntil = clock.now + 3_600_000)
        val seen = mutableListOf<CaptureStatus>()
        backgroundScope.launch { repository.status.collect(seen::add) }
        runCurrent()
        assertThat(seen).containsExactly(CaptureStatus.Paused(clock.now + 3_600_000))

        // Virtual time and the fake wall clock move together.
        clock.advance(3_599_999)
        advanceTimeBy(3_599_999)
        runCurrent()
        assertThat(seen).hasSize(1)

        clock.advance(1)
        advanceTimeBy(1)
        runCurrent()
        assertThat(seen.last()).isEqualTo(CaptureStatus.Active(null))
    }

    @Test
    fun `a pause without limit never ends by itself`() = runTest {
        settings.value = settings.value.copy(pausedUntil = Long.MAX_VALUE)
        val seen = mutableListOf<CaptureStatus>()
        backgroundScope.launch { repository.status.collect(seen::add) }
        clock.advance(365L * 24 * 3_600_000)
        advanceTimeBy(365L * 24 * 3_600_000)
        runCurrent()
        assertThat(seen).containsExactly(CaptureStatus.Paused(until = null))
    }

    private suspend fun status() = repository.status.first()
}
