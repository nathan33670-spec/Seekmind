package fr.corsicajack.seekmind.core.common

import java.util.logging.Logger

/**
 * The only way to log in SeekMind (INV-07, architecture §11). The API cannot carry captured content:
 * an entry is a constant event name and numeric measurements (counts, durations, lengths); a throwable
 * is reduced to its class name, because its message may quote user text.
 *
 * Entries go through `java.util.logging`, which Android routes to logcat, so that this module stays
 * pure JVM. Below [minLevel] nothing is written: `:app` keeps WARN, the default, in release builds and
 * sets DEBUG in debug builds.
 *
 * Usage: `SmLog.i(TAG, "capture_stored", "chars" to text.length, "ms" to duration)`.
 */
object SmLog {
    enum class Level(internal val jul: java.util.logging.Level) {
        DEBUG(java.util.logging.Level.FINE),
        INFO(java.util.logging.Level.INFO),
        WARN(java.util.logging.Level.WARNING),
        ERROR(java.util.logging.Level.SEVERE),
    }

    /** Receives formatted entries; replaced by tests. */
    fun interface Sink {
        fun write(level: Level, tag: String, message: String)
    }

    /** Set once at start-up by :app; a plain volatile field, not a configuration store. */
    @Volatile
    var minLevel: Level = Level.WARN

    @Volatile
    var sink: Sink = JulSink

    fun d(tag: String, event: String, vararg measures: Pair<String, Number>) =
        log(Level.DEBUG, tag, event, null, measures)

    fun i(tag: String, event: String, vararg measures: Pair<String, Number>) =
        log(Level.INFO, tag, event, null, measures)

    fun w(tag: String, event: String, error: Throwable? = null, vararg measures: Pair<String, Number>) =
        log(Level.WARN, tag, event, error, measures)

    fun e(tag: String, event: String, error: Throwable? = null, vararg measures: Pair<String, Number>) =
        log(Level.ERROR, tag, event, error, measures)

    /** `event key=value … error=ClassName`: never a free-form message. */
    fun format(event: String, error: Throwable?, measures: Array<out Pair<String, Number>>): String = buildString {
        append(event)
        for ((key, value) in measures) append(' ').append(key).append('=').append(value)
        if (error != null) append(" error=").append(error.javaClass.name)
    }

    private fun log(
        level: Level,
        tag: String,
        event: String,
        error: Throwable?,
        measures: Array<out Pair<String, Number>>,
    ) {
        if (level < minLevel) return
        sink.write(level, tag, format(event, error, measures))
    }

    private object JulSink : Sink {
        override fun write(level: Level, tag: String, message: String) {
            Logger.getLogger(tag).log(level.jul, message)
        }
    }
}
