package fr.corsicajack.seekmind.engine.capture.meta

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** docs/specs/capture.md §5.1 (ticket M2-04). */
class ActivityTrackerTest {
    private val activities = setOf(APP to "$APP.MainActivity", APP to "$APP.DetailActivity", OTHER to "$OTHER.Home")
    private val lookups = mutableListOf<Pair<String, String>>()
    private val lookup = ActivityLookup { p, c ->
        lookups += p to c
        (p to c) in activities
    }

    @Test
    fun `an activity is kept, a view container after it is ignored`() {
        val tracker = ActivityTracker(lookup)
        tracker.onWindowStateChanged(APP, "$APP.MainActivity")
        tracker.onWindowStateChanged(APP, "android.widget.FrameLayout")
        tracker.onWindowStateChanged(APP, null)
        assertThat(tracker.activityOf(APP)).isEqualTo("$APP.MainActivity")
        tracker.onWindowStateChanged(APP, "$APP.DetailActivity")
        assertThat(tracker.activityOf(APP)).isEqualTo("$APP.DetailActivity")
    }

    @Test
    fun `another app's activity hides the previous one`() {
        val tracker = ActivityTracker(lookup)
        tracker.onWindowStateChanged(APP, "$APP.MainActivity")
        tracker.onWindowStateChanged(OTHER, "$OTHER.Home")
        assertThat(tracker.activityOf(APP)).isNull()
        assertThat(tracker.activityOf(OTHER)).isEqualTo("$OTHER.Home")
    }

    @Test
    fun `a class of another package is not an activity of this one`() {
        val tracker = ActivityTracker(lookup)
        tracker.onWindowStateChanged(APP, "$OTHER.Home")
        assertThat(tracker.activityOf(APP)).isNull()
    }

    @Test
    fun `answers are cached, the most recent ones only`() {
        val tracker = ActivityTracker(lookup, cacheSize = 2)
        repeat(3) { tracker.onWindowStateChanged(APP, "$APP.MainActivity") }
        assertThat(lookups).hasSize(1)
        tracker.onWindowStateChanged(APP, "a.B")
        tracker.onWindowStateChanged(APP, "c.D") // evicts MainActivity
        tracker.onWindowStateChanged(APP, "$APP.MainActivity")
        assertThat(lookups).hasSize(4)
        tracker.clearCache()
        tracker.onWindowStateChanged(APP, "$APP.MainActivity")
        assertThat(lookups).hasSize(5)
    }

    @Test
    fun `the default cache holds 200 answers`() {
        val tracker = ActivityTracker(lookup)
        repeat(200) { tracker.onWindowStateChanged(APP, "a.View$it") }
        repeat(200) { tracker.onWindowStateChanged(APP, "a.View$it") }
        assertThat(lookups).hasSize(200)
        tracker.onWindowStateChanged(APP, "a.View200")
        tracker.onWindowStateChanged(APP, "a.View0")
        assertThat(lookups).hasSize(202)
    }

    private companion object {
        const val APP = "com.example.app"
        const val OTHER = "com.example.other"
    }
}
