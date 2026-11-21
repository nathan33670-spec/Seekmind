package fr.corsicajack.seekmind.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import fr.corsicajack.seekmind.core.designsystem.component.ConfirmDialog
import fr.corsicajack.seekmind.core.designsystem.component.SettingRow
import fr.corsicajack.seekmind.core.designsystem.component.SwitchSettingRow
import fr.corsicajack.seekmind.core.designsystem.preview.SeekMindPreviews
import fr.corsicajack.seekmind.core.designsystem.theme.SeekMindTheme
import fr.corsicajack.seekmind.core.designsystem.theme.Spacing
import fr.corsicajack.seekmind.core.model.AppFamily
import fr.corsicajack.seekmind.engine.privacy.AppStatus
import fr.corsicajack.seekmind.engine.privacy.AppStatusReason

/** Test tag of the switch of [family]. */
fun familySwitchTag(family: AppFamily) = "settings_family_${family.name.lowercase()}"

/** Test tag of the switch of the application [packageName]. */
fun appSwitchTag(packageName: String) = "settings_app_$packageName"

/** Test tag of the row that opens the list of applications. */
const val APPS_ROW_TAG = "settings_apps"

/** Test tag of the search field of the list. */
const val APPS_SEARCH_TAG = "settings_apps_search"

/** The families in the order of the welcome flow. */
private val FAMILIES = listOf(AppFamily.BROWSERS, AppFamily.MESSAGING, AppFamily.SOCIAL_MEDIA, AppFamily.OTHER)

/**
 * « Applications » (docs/specs/ui-ux.md §3.5, docs/specs/confidentialite.md §3.4): the four families with
 * their switch, and the row that opens the list of the installed applications.
 */
@Composable
fun AppsSettingsSection(
    enabledFamilies: Set<AppFamily>,
    onToggleFamily: (AppFamily) -> Unit,
    onOpenApps: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        SectionTitle(stringResource(R.string.settings_apps_title))
        FAMILIES.forEach { family ->
            SwitchSettingRow(
                title = familyLabel(family),
                checked = family in enabledFamilies,
                onCheckedChange = { onToggleFamily(family) },
                modifier = Modifier.testTag(familySwitchTag(family)),
            )
        }
        Text(
            text = stringResource(R.string.settings_apps_never),
            modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s),
        )
        SettingRow(
            title = stringResource(R.string.settings_apps_list),
            summary = stringResource(R.string.settings_apps_list_summary),
            onClick = onOpenApps,
            modifier = Modifier.testTag(APPS_ROW_TAG),
        )
    }
}

/**
 * The list of the launchable applications (docs/specs/confidentialite.md §3.3): a switch each, its family
 * and the origin of its state; a search; the strict list locked; a confirmation before including a
 * sensitive application.
 */
@Composable
fun AppsListScreen(
    state: AppsUiState,
    onQueryChange: (String) -> Unit,
    onSetRemembered: (InstalledApp, Boolean) -> Unit,
    onConfirmInclude: () -> Unit,
    onCancelInclude: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        SectionTitle(stringResource(R.string.settings_apps_list))
        OutlinedTextField(
            value = state.query,
            onValueChange = onQueryChange,
            singleLine = true,
            label = { Text(text = stringResource(R.string.settings_apps_search)) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.l, vertical = Spacing.s)
                .testTag(APPS_SEARCH_TAG),
        )
        val rows = state.shown
        if (rows == null) {
            val loading = stringResource(R.string.settings_apps_loading)
            CircularProgressIndicator(
                modifier = Modifier.padding(Spacing.l).semantics { contentDescription = loading },
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(rows, key = { it.app.packageName }) { row ->
                    SwitchSettingRow(
                        title = row.app.label,
                        summary = originLabel(row.status),
                        checked = row.status.remembered,
                        onCheckedChange = { onSetRemembered(row.app, it) },
                        enabled = !row.status.locked,
                        modifier = Modifier.testTag(appSwitchTag(row.app.packageName)),
                    )
                }
            }
        }
    }
    state.pendingInclude?.let { app ->
        ConfirmDialog(
            title = stringResource(R.string.settings_apps_include_title, app.label),
            text = stringResource(R.string.settings_apps_include_text),
            confirmLabel = stringResource(R.string.settings_apps_include_confirm),
            dismissLabel = stringResource(R.string.settings_cancel),
            onConfirm = onConfirmInclude,
            onDismiss = onCancelInclude,
        )
    }
}

@Composable
internal fun familyLabel(family: AppFamily): String = stringResource(
    when (family) {
        AppFamily.BROWSERS -> R.string.settings_family_browsers
        AppFamily.MESSAGING -> R.string.settings_family_messaging
        AppFamily.SOCIAL_MEDIA -> R.string.settings_family_social
        AppFamily.OTHER -> R.string.settings_family_other
    },
)

/** The state of an application and where it comes from (« exclue par défaut : application bancaire »). */
@Composable
private fun originLabel(status: AppStatus): String = when (status.reason) {
    AppStatusReason.IGNORED -> stringResource(R.string.settings_app_ignored)

    AppStatusReason.STRICT_LIST -> stringResource(R.string.settings_app_strict)

    AppStatusReason.CHOICES_UNKNOWN -> stringResource(R.string.settings_apps_loading)

    AppStatusReason.USER_EXCLUDED -> stringResource(R.string.settings_app_user_excluded)

    AppStatusReason.USER_INCLUDED -> stringResource(R.string.settings_app_user_included)

    AppStatusReason.SOFT_LIST -> stringResource(R.string.settings_app_soft)

    AppStatusReason.GAME -> stringResource(R.string.settings_app_game)

    AppStatusReason.FAMILY_DISABLED ->
        stringResource(R.string.settings_app_family_disabled, status.family?.let { familyLabel(it) }.orEmpty())

    AppStatusReason.FAMILY_ENABLED ->
        stringResource(R.string.settings_app_family_enabled, status.family?.let { familyLabel(it) }.orEmpty())
}

@SeekMindPreviews
@Composable
private fun AppsSettingsSectionPreview() {
    SeekMindTheme(dynamicColor = false) {
        AppsSettingsSection(
            enabledFamilies = setOf(AppFamily.BROWSERS, AppFamily.MESSAGING),
            onToggleFamily = {},
            onOpenApps = {},
        )
    }
}
