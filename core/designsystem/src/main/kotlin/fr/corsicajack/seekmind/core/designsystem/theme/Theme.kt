package fr.corsicajack.seekmind.core.designsystem.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * Theme of the whole application (docs/specs/ui-ux.md §4): dynamic colour on Android 12 and later,
 * brand palette otherwise, light and dark schemes, [SeekMindTypography] and [SeekMindShapes].
 */
@Composable
fun SeekMindTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme: ColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> BrandDarkColors

        else -> BrandLightColors
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = SeekMindTypography,
        shapes = SeekMindShapes,
        content = content,
    )
}
