package fr.corsicajack.seekmind.capture

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Captures deleted from a screen (detail, journal), told to the other screens of the process so that a
 * deleted capture leaves them at once (ticket M6-04). Ids only: never any content.
 *
 * Only the screens collecting at that moment receive an event (no replay): it refreshes what is shown,
 * it is not a source of truth. A screen opened later reads the database, which already lacks the capture.
 */
@Singleton
class CaptureDeletions @Inject constructor() {
    private val deleted = MutableSharedFlow<Long>(extraBufferCapacity = BUFFER)

    val events: SharedFlow<Long> = deleted.asSharedFlow()

    suspend fun publish(captureId: Long) = deleted.emit(captureId)

    private companion object {
        const val BUFFER = 64
    }
}
