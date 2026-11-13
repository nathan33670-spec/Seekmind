package fr.corsicajack.seekmind.core.designsystem

import android.os.Build
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks

/**
 * Turns on the automatic accessibility checks (Accessibility Test Framework) for every action and
 * assertion of [this] rule. Compose offers them from Android 14; the API 30 run goes without.
 */
fun ComposeTestRule.enableA11yChecksWhereSupported() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) enableAccessibilityChecks()
}
