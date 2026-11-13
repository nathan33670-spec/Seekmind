package fr.corsicajack.seekmind.engine.capture.pause

import fr.corsicajack.seekmind.core.model.CaptureStatus

/** What the quick settings tile shows and does (docs/specs/capture.md §7), from the capture status. */
data class TileModel(val active: Boolean, val subtitle: Subtitle, val onTap: Action) {
    sealed interface Subtitle {
        data object Active : Subtitle

        data object Paused : Subtitle

        /** [at]: epoch ms, shown as a local time. */
        data class PausedUntil(val at: Long) : Subtitle

        /** Consent missing or service disabled: the application sets it up. */
        data object NotSetUp : Subtitle
    }

    enum class Action { TOGGLE_PAUSE, OPEN_APP }

    /**
     * A tap that resumes the capture from the lock screen first asks for the unlock: someone holding the
     * locked phone cannot restart it behind the user's back. Pausing needs no unlock.
     */
    fun needsUnlock(locked: Boolean): Boolean = locked && onTap == Action.TOGGLE_PAUSE && !active

    companion object {
        fun of(status: CaptureStatus): TileModel = when (status) {
            is CaptureStatus.Active -> TileModel(true, Subtitle.Active, Action.TOGGLE_PAUSE)

            is CaptureStatus.Paused -> TileModel(
                active = false,
                subtitle = status.until?.let(Subtitle::PausedUntil) ?: Subtitle.Paused,
                onTap = Action.TOGGLE_PAUSE,
            )

            CaptureStatus.ConsentMissing, CaptureStatus.ServiceDisabled ->
                TileModel(false, Subtitle.NotSetUp, Action.OPEN_APP)
        }
    }
}
