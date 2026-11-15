package fr.corsicajack.seekmind.feature.search

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** « Dernière mémorisation il y a … » (docs/specs/ui-ux.md §3.2, ticket M6-09). */
class ElapsedTest {
    private val now = 1_794_441_600_000L

    @Test
    fun underAMinuteIsJustNow() {
        assertThat(Elapsed.between(now - 59_999, now)).isEqualTo(Elapsed.JustNow)
        assertThat(Elapsed.between(now + 5_000, now)).isEqualTo(Elapsed.JustNow)
    }

    @Test
    fun minutesThenHoursThenDays() {
        assertThat(Elapsed.between(now - 2 * 60_000, now)).isEqualTo(Elapsed.Minutes(2))
        assertThat(Elapsed.between(now - 59 * 60_000 - 59_999, now)).isEqualTo(Elapsed.Minutes(59))
        assertThat(Elapsed.between(now - 3_600_000, now)).isEqualTo(Elapsed.Hours(1))
        assertThat(Elapsed.between(now - 23 * 3_600_000L, now)).isEqualTo(Elapsed.Hours(23))
        assertThat(Elapsed.between(now - 3 * 86_400_000L, now)).isEqualTo(Elapsed.Days(3))
    }
}
