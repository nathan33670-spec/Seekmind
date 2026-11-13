package fr.corsicajack.seekmind.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

class TypographyTest {
    @Test
    fun `no text style is smaller than 12 sp`() {
        val styles = SeekMindTypography.allStyles()
        assertThat(styles).hasSize(15)
        styles.forEach { (name, style) ->
            assertThat(style.fontSize.isSp).isTrue()
            assertWithMessage(name).that(style.fontSize.value).isAtLeast(MinTextSize.value)
        }
    }

    @Test
    fun `cards have 16 dp corners`() {
        assertThat(SeekMindShapes.medium.toString()).contains("16.0.dp")
    }

    private fun Typography.allStyles(): List<Pair<String, TextStyle>> = listOf(
        "displayLarge" to displayLarge, "displayMedium" to displayMedium, "displaySmall" to displaySmall,
        "headlineLarge" to headlineLarge, "headlineMedium" to headlineMedium, "headlineSmall" to headlineSmall,
        "titleLarge" to titleLarge, "titleMedium" to titleMedium, "titleSmall" to titleSmall,
        "bodyLarge" to bodyLarge, "bodyMedium" to bodyMedium, "bodySmall" to bodySmall,
        "labelLarge" to labelLarge, "labelMedium" to labelMedium, "labelSmall" to labelSmall,
    )
}
