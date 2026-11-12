package fr.corsicajack.seekmind.core.common

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OutcomeTest {
    private val ok: Outcome<Int, String> = Outcome.Success(2)
    private val failed: Outcome<Int, String> = Outcome.Failure("no fill")

    @Test
    fun `map transforms a success and keeps a failure`() {
        assertThat(ok.map { it * 10 }).isEqualTo(Outcome.Success(20))
        assertThat(failed.map { it * 10 }).isEqualTo(failed)
    }

    @Test
    fun `fold and getOrNull`() {
        assertThat(ok.fold({ "value $it" }, { "error $it" })).isEqualTo("value 2")
        assertThat(failed.fold({ "value $it" }, { "error $it" })).isEqualTo("error no fill")
        assertThat(ok.getOrNull()).isEqualTo(2)
        assertThat(failed.getOrNull()).isNull()
    }

    @Test
    fun `int rect size`() {
        val rect = IntRect(10, 20, 110, 70)
        assertThat(rect.width).isEqualTo(100)
        assertThat(rect.height).isEqualTo(50)
    }
}
