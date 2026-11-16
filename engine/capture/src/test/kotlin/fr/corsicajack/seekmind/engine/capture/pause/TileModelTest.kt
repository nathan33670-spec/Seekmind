package fr.corsicajack.seekmind.engine.capture.pause

import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.core.database.settings.Consent
import fr.corsicajack.seekmind.core.database.settings.SettingsRepository
import fr.corsicajack.seekmind.core.model.CaptureStatus
import fr.corsicajack.seekmind.core.testing.FakeClock
import fr.corsicajack.seekmind.engine.capture.pause.TileModel.Action
import fr.corsicajack.seekmind.engine.capture.pause.TileModel.Subtitle
import fr.corsicajack.seekmind.engine.capture.status.CaptureStatusRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** docs/specs/capture.md §7, the quick settings tile (ticket M2-07). */
class TileModelTest {
    @Test
    fun `the tile shows each state`() {
        assertThat(
            TileModel.of(CaptureStatus.Active(null)),
        ).isEqualTo(TileModel(true, Subtitle.Active, Action.TOGGLE_PAUSE))
        assertThat(
            TileModel.of(CaptureStatus.Paused(null)),
        ).isEqualTo(TileModel(false, Subtitle.Paused, Action.TOGGLE_PAUSE))
        assertThat(TileModel.of(CaptureStatus.Paused(42))).isEqualTo(
            TileModel(false, Subtitle.PausedUntil(42), Action.TOGGLE_PAUSE),
        )
        assertThat(
            TileModel.of(CaptureStatus.ConsentMissing),
        ).isEqualTo(TileModel(false, Subtitle.NotSetUp, Action.OPEN_APP))
        assertThat(
            TileModel.of(CaptureStatus.ServiceDisabled),
        ).isEqualTo(TileModel(false, Subtitle.NotSetUp, Action.OPEN_APP))
    }

    @Test
    fun `the tile follows a change made in the application`() = runTest {
        val clock = FakeClock()
        val settings = MutableStateFlow(SettingsRepository.DEFAULTS.copy(consent = Consent(1, 0)))
        val repository = CaptureStatusRepository(MutableStateFlow(true), settings, clock)
        val shown = mutableListOf<TileModel>()
        backgroundScope.launch { repository.status.map(TileModel::of).collect(shown::add) }
        runCurrent()
        // The application pauses for one hour, then resumes.
        settings.value = settings.value.copy(pausedUntil = clock.now + 3_600_000)
        runCurrent()
        settings.value = settings.value.copy(pausedUntil = null)
        runCurrent()
        assertThat(shown.map { it.subtitle }).containsExactly(
            Subtitle.Active,
            Subtitle.PausedUntil(clock.now + 3_600_000),
            Subtitle.Active,
        ).inOrder()
    }

    @Test
    fun `resuming from the lock screen asks for the unlock, pausing does not`() {
        val active = TileModel.of(CaptureStatus.Active(null))
        val paused = TileModel.of(CaptureStatus.Paused(null))
        val notSetUp = TileModel.of(CaptureStatus.ConsentMissing)
        assertThat(paused.needsUnlock(locked = true)).isTrue()
        assertThat(paused.needsUnlock(locked = false)).isFalse()
        assertThat(active.needsUnlock(locked = true)).isFalse()
        assertThat(notSetUp.needsUnlock(locked = true)).isFalse()
    }
}
