package fr.corsicajack.seekmind.engine.capture.routing

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import fr.corsicajack.seekmind.core.common.Clock
import fr.corsicajack.seekmind.engine.capture.stability.Timer
import java.util.concurrent.Executor
import kotlin.math.max
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher

/** The capture thread, as the router and the capture loop see it (architecture §5). */
interface CaptureExecutor : Executor {
    /** Single-threaded dispatcher on the same thread. */
    val dispatcher: CoroutineDispatcher

    /** A [Timer] whose actions run on this thread. */
    fun newTimer(): Timer

    /** Runs [action] on this thread after [delayMs]; returns a handle that cancels it. */
    fun schedule(delayMs: Long, action: () -> Unit): () -> Unit
}

/**
 * `sm-capture`, a [HandlerThread] that serialises the whole ingestion: debouncer, tree reading, gate,
 * storage (architecture §5). One per process; it never stops.
 */
class CaptureThread(private val clock: Clock) : CaptureExecutor {
    private val thread = HandlerThread(NAME).apply { start() }
    private val handler = Handler(thread.looper)

    /** For receivers registered on this thread. */
    val looper: Looper get() = thread.looper

    override val dispatcher: CoroutineDispatcher = asCoroutineDispatcher()

    override fun execute(command: Runnable) {
        handler.post(command)
    }

    override fun schedule(delayMs: Long, action: () -> Unit): () -> Unit {
        val runnable = Runnable(action)
        handler.postDelayed(runnable, max(0, delayMs))
        return { handler.removeCallbacks(runnable) }
    }

    override fun newTimer(): Timer = object : Timer {
        private var cancel: (() -> Unit)? = null

        override fun schedule(atElapsedMs: Long, action: () -> Unit) {
            cancel()
            cancel = this@CaptureThread.schedule(atElapsedMs - clock.elapsedRealtime()) {
                cancel = null
                action()
            }
        }

        override fun cancel() {
            cancel?.invoke()
            cancel = null
        }
    }

    private companion object {
        const val NAME = "sm-capture"
    }
}
