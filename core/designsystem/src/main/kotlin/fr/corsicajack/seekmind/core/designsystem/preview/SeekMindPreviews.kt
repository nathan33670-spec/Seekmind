package fr.corsicajack.seekmind.core.designsystem.preview

import android.content.res.Configuration
import androidx.compose.ui.tooling.preview.Preview

/** The three previews every component has (docs/specs/ui-ux.md §4): light, dark, 200 % font. */
@Preview(name = "light", showBackground = true)
@Preview(
    name = "dark",
    showBackground = true,
    uiMode =
        Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL,
)
@Preview(name = "font 200%", showBackground = true, fontScale = 2f)
annotation class SeekMindPreviews
