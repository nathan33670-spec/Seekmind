package fr.corsicajack.seekmind.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.unit.sp

/** Smallest text size of the application (docs/specs/ui-ux.md §4). */
val MinTextSize = 12.sp

/**
 * Material 3 default type scale, except `labelSmall`, raised from 11 sp to [MinTextSize] so that no
 * text is smaller than 12 sp.
 */
val SeekMindTypography: Typography = Typography().let { base ->
    base.copy(labelSmall = base.labelSmall.copy(fontSize = MinTextSize, lineHeight = 16.sp))
}
