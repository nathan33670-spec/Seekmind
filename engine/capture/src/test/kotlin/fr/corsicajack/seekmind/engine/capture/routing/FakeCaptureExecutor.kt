package fr.corsicajack.seekmind.engine.capture.routing

import fr.corsicajack.seekmind.engine.capture.stability.Timer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher

/** A capture thread the test runs by hand: [posted] counts the wake-ups, [runAll] runs them. */
class FakeCaptureExecutor : CaptureExecutor {
    private val queue = ArrayDeque<Runnable>()
    var posted = 0
        private set
    val timer = ManualTimer()

    override val dispatcher: CoroutineDispatcher = asCoroutineDispatcher()

    override fun execute(command: Runnable) {
        posted++
        queue.addLast(command)
    }

    override fun schedule(delayMs: Long, action: () -> Unit): () -> Unit = error("not used")

    override fun newTimer(): Timer = timer

    fun runAll() {
        while (queue.isNotEmpty()) queue.removeFirst().run()
    }

    /** Runs what is queued, then counts wake-ups from zero. */
    fun settle() {
        runAll()
        posted = 0
    }

    /** The debouncer's timer: [fire] runs the pending action as if its time had come. */
    inner class ManualTimer : Timer {
        var pending: (() -> Unit)? = null
            private set
        var at: Long = -1
            private set

        override fun schedule(atElapsedMs: Long, action: () -> Unit) {
            at = atElapsedMs
            pending = action
        }

        override fun cancel() {
            pending = null
        }

        fun fire() {
            val action = checkNotNull(pending) { "nothing scheduled" }
            pending = null
            action()
            runAll()
        }
    }
}
