package fr.corsicajack.seekmind.engine.capture.pause

import fr.corsicajack.seekmind.core.common.Clock
import fr.corsicajack.seekmind.core.database.settings.SettingsRepository
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** The pauses offered by the application (docs/specs/capture.md §7). */
enum class PauseDuration {
    ONE_HOUR,

    /** Until 6 a.m. on the next calendar day, local time. */
    UNTIL_TOMORROW_MORNING,
    INDEFINITELY,
}

/**
 * Pauses and resumes the capture (docs/specs/capture.md §7, EF-CAP-04) by writing `pausedUntil`. The
 * router stops passing events as soon as the setting is read, and lets them through again once its
 * time has come: an expired pause needs no clean-up.
 */
class PauseController(
    private val settings: SettingsRepository,
    private val clock: Clock,
    private val zone: () -> ZoneId,
) {
    suspend fun pause(duration: PauseDuration) {
        settings.setPausedUntil(until(duration, clock.nowMillis(), zone()))
    }

    suspend fun resume() {
        settings.setPausedUntil(null)
    }

    /** The tile's tap: active → paused without limit, paused → active. */
    suspend fun toggle() {
        val pausedUntil = settings.current().pausedUntil
        if (pausedUntil != null && clock.nowMillis() < pausedUntil) resume() else pause(PauseDuration.INDEFINITELY)
    }

    companion object {
        private const val HOUR_MS = 3_600_000L
        private val MORNING: LocalTime = LocalTime.of(6, 0)

        /** End of a pause started at [now] (epoch ms); [Long.MAX_VALUE] for no limit. */
        fun until(duration: PauseDuration, now: Long, zone: ZoneId): Long = when (duration) {
            PauseDuration.ONE_HOUR -> now + HOUR_MS

            PauseDuration.UNTIL_TOMORROW_MORNING ->
                Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(1).atTime(MORNING)
                    .atZone(zone).toInstant().toEpochMilli()

            PauseDuration.INDEFINITELY -> Long.MAX_VALUE
        }
    }
}
