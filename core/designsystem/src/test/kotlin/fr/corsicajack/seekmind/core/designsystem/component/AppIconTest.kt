package fr.corsicajack.seekmind.core.designsystem.component

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AppIconTest {
    @Test
    fun `the initial is the first letter or digit, upper-cased`() {
        assertThat(initialOf("messages")).isEqualTo("M")
        assertThat(initialOf("  (beta) app")).isEqualTo("B")
        assertThat(initialOf("2048")).isEqualTo("2")
        assertThat(initialOf("élan")).isEqualTo("É")
    }

    @Test
    fun `a label without letters has no initial`() {
        assertThat(initialOf("")).isEmpty()
        assertThat(initialOf("…!")).isEmpty()
    }
}
