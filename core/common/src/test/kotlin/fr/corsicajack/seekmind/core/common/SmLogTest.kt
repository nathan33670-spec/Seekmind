package fr.corsicajack.seekmind.core.common

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test

class SmLogTest {
    private val written = mutableListOf<Triple<SmLog.Level, String, String>>()
    private val previousSink = SmLog.sink
    private val previousLevel = SmLog.minLevel

    @Before
    fun capture() {
        SmLog.sink = SmLog.Sink { level, tag, message -> written += Triple(level, tag, message) }
    }

    @After
    fun restore() {
        SmLog.sink = previousSink
        SmLog.minLevel = previousLevel
    }

    @Test
    fun `release default writes warnings and errors only`() {
        assertThat(SmLog.minLevel).isEqualTo(SmLog.Level.WARN)
        SmLog.d("Tag", "debug_event")
        SmLog.i("Tag", "info_event")
        SmLog.w("Tag", "warn_event")
        SmLog.e("Tag", "error_event")
        assertThat(written.map { it.first }).containsExactly(SmLog.Level.WARN, SmLog.Level.ERROR).inOrder()
    }

    @Test
    fun `an entry is an event name and numeric measures`() {
        SmLog.minLevel = SmLog.Level.DEBUG
        SmLog.i("Capture", "capture_stored", "chars" to 812, "ms" to 3.5)
        assertThat(written.single()).isEqualTo(Triple(SmLog.Level.INFO, "Capture", "capture_stored chars=812 ms=3.5"))
    }

    @Test
    fun `a throwable is reduced to its class name, never its message`() {
        SmLog.e("Ingest", "insert_failed", IllegalStateException("secret text from the screen"), "attempt" to 2)
        val message = written.single().third
        assertThat(message).isEqualTo("insert_failed attempt=2 error=java.lang.IllegalStateException")
        assertThat(message).doesNotContain("secret")
    }
}
