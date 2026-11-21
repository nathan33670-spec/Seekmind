package fr.corsicajack.seekmind.capture

import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.core.database.store.CaptureStore
import fr.corsicajack.seekmind.engine.capture.routing.CaptureExecutor
import fr.corsicajack.seekmind.engine.capture.stability.Timer
import java.lang.reflect.Proxy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test

/** After every deletion, the ingestion forgets what it learnt (docs/specs/indexation.md §5.1). */
class ForgettingCaptureStoreTest {
    private val calls = mutableListOf<String>()

    /** Records the deletions; [failing] makes them throw. Every other call is unexpected. */
    private fun store(failing: Boolean = false): CaptureStore =
        Proxy.newProxyInstance(CaptureStore::class.java.classLoader, arrayOf(CaptureStore::class.java)) {
                _,
                method,
                _,
            ->
            calls += method.name
            if (failing) error("disk full")
            Unit
        } as CaptureStore

    @Test
    fun `every kind of deletion is followed by the forgetting`() = runBlocking {
        var forgotten = 0
        val store = ForgettingCaptureStore(store()) {
            calls += "forget"
            forgotten++
        }
        store.delete(1)
        store.deleteByPackage("com.example.notes")
        store.deleteByRange(0, 10)
        assertThat(forgotten).isEqualTo(3)
        assertThat(calls).containsExactly("delete", "forget", "deleteByPackage", "forget", "deleteByRange", "forget")
            .inOrder()
    }

    @Test
    fun `a failed deletion still forgets, and still fails`() {
        var forgotten = 0
        val store = ForgettingCaptureStore(store(failing = true)) { forgotten++ }
        assertThrows(IllegalStateException::class.java) { runBlocking { store.deleteByPackage("x") } }
        assertThat(forgotten).isEqualTo(1)
    }

    @Test
    fun `the forgetting runs on the capture thread`() {
        val posted = mutableListOf<Runnable>()
        var forgotten = 0
        val thread = object : CaptureExecutor {
            override val dispatcher: CoroutineDispatcher = Dispatchers.Unconfined

            override fun newTimer(): Timer = error("unused")

            override fun schedule(delayMs: Long, action: () -> Unit): () -> Unit = error("unused")

            override fun execute(command: Runnable) {
                posted += command
            }
        }
        val forgetting = CaptureForgetting(thread) { forgotten++ }
        forgetting.afterDeletion()
        // Nothing runs on the caller's thread: the ingestion's memory is only touched by the capture thread.
        assertThat(forgotten).isEqualTo(0)
        posted.single().run()
        assertThat(forgotten).isEqualTo(1)
    }
}
