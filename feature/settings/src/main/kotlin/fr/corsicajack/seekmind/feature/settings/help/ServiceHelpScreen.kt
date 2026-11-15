package fr.corsicajack.seekmind.feature.settings.help

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import fr.corsicajack.seekmind.core.designsystem.theme.Spacing
import fr.corsicajack.seekmind.feature.settings.R

/** Test tag of the troubleshooting page. */
const val SERVICE_HELP_TAG = "service_help"

/** One manufacturer of the page: its name and the steps that keep SeekMind out of battery optimisation. */
data class ManufacturerSteps(@param:StringRes val name: Int, @param:StringRes val steps: Int)

/** The manufacturers of the page, in the order of docs/plan/M6-interface.md (M6-09). */
val SERVICE_HELP_MANUFACTURERS: List<ManufacturerSteps> = listOf(
    ManufacturerSteps(R.string.settings_help_samsung, R.string.settings_help_samsung_steps),
    ManufacturerSteps(R.string.settings_help_xiaomi, R.string.settings_help_xiaomi_steps),
    ManufacturerSteps(R.string.settings_help_oppo, R.string.settings_help_oppo_steps),
    ManufacturerSteps(R.string.settings_help_huawei, R.string.settings_help_huawei_steps),
    ManufacturerSteps(R.string.settings_help_oneplus, R.string.settings_help_oneplus_steps),
    ManufacturerSteps(R.string.settings_help_other, R.string.settings_help_other_steps),
)

/**
 * « Le service s'arrête tout seul » (EF-CAP-05, risk R-04): why some phones stop accessibility services,
 * and, per manufacturer, how to exclude SeekMind from battery optimisation. Text only: SeekMind asks for
 * no permission and opens no system screen from here.
 */
@Composable
fun ServiceHelpScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.l)
            .testTag(SERVICE_HELP_TAG),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        Text(
            text = stringResource(R.string.settings_help_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
        Text(text = stringResource(R.string.settings_help_intro), style = MaterialTheme.typography.bodyMedium)
        Text(text = stringResource(R.string.settings_help_reactivate), style = MaterialTheme.typography.bodyMedium)
        SERVICE_HELP_MANUFACTURERS.forEach { manufacturer ->
            Text(
                text = stringResource(manufacturer.name),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = Spacing.s).semantics { heading() },
            )
            Text(text = stringResource(manufacturer.steps), style = MaterialTheme.typography.bodyMedium)
        }
        Text(
            text = stringResource(R.string.settings_help_versions),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
