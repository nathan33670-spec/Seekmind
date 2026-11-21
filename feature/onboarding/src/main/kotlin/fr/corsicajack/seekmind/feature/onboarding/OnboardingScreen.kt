package fr.corsicajack.seekmind.feature.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import fr.corsicajack.seekmind.core.designsystem.component.AppIcon
import fr.corsicajack.seekmind.core.designsystem.theme.Spacing
import fr.corsicajack.seekmind.core.model.AppFamily

const val DISCLOSURE_TEXT_TAG = "onboarding_disclosure_text"
const val DISCLOSURE_ACCEPT_TAG = "onboarding_disclosure_accept"
const val DISCLOSURE_REFUSE_TAG = "onboarding_disclosure_refuse"

/** An installed application shown next to its family (docs/specs/ui-ux.md §3.1). */
data class FamilyApp(val label: String, val icon: ImageBitmap?)

/**
 * The welcome flow (docs/specs/ui-ux.md §3.1). [onOpenSettings] opens the system accessibility settings:
 * the activation screen only offers it once the consent and the families are recorded (INV-04).
 */
@Composable
fun OnboardingScreen(
    state: OnboardingUiState,
    familyApps: Map<AppFamily, List<FamilyApp>>,
    onNextIntro: () -> Unit,
    onSkipIntro: () -> Unit,
    onAccept: () -> Unit,
    onRefuse: () -> Unit,
    onToggleFamily: (AppFamily) -> Unit,
    onConfirmFamilies: () -> Unit,
    onOpenSettings: () -> Unit,
    onHelp: () -> Unit,
    onContinueAfterConfirmation: () -> Unit,
    onChooseLock: (Boolean) -> Unit,
    onLeave: () -> Unit,
    modifier: Modifier = Modifier,
    onChoosePack: (Boolean) -> Unit = {},
) {
    // Back from the disclosure is a refusal (docs/05-conformite-play-store.md §4.1).
    BackHandler(enabled = state.step != OnboardingStep.INTRO && state.step != OnboardingStep.DONE, onBack = onLeave)
    Column(
        modifier = modifier.fillMaxSize().padding(Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.l),
    ) {
        when (state.step) {
            OnboardingStep.INTRO -> Intro(state.introPage, onNextIntro, onSkipIntro)

            OnboardingStep.DISCLOSURE -> Disclosure(onAccept, onRefuse)

            OnboardingStep.FAMILIES -> Families(state.families, familyApps, onToggleFamily, onConfirmFamilies)

            OnboardingStep.ACTIVATE -> Activate(onOpenSettings, onHelp)

            OnboardingStep.CONFIRMED -> Single(
                R.string.onboarding_confirmed_title,
                R.string.onboarding_continue,
                onContinueAfterConfirmation,
            )

            OnboardingStep.LOCK -> Lock(onChooseLock)

            OnboardingStep.LANGUAGE_PACK -> LanguagePack(onChoosePack)

            OnboardingStep.DONE -> Unit
        }
    }
}

@Composable
private fun Title(text: String) {
    Text(text, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
}

@Composable
private fun Intro(page: Int, onNext: () -> Unit, onSkip: () -> Unit) {
    val (title, text) = when (page) {
        0 -> R.string.onboarding_intro_1_title to R.string.onboarding_intro_1_text
        1 -> R.string.onboarding_intro_2_title to R.string.onboarding_intro_2_text
        else -> R.string.onboarding_intro_3_title to R.string.onboarding_intro_3_text
    }
    Title(stringResource(title))
    Text(stringResource(text), style = MaterialTheme.typography.bodyLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
        TextButton(onClick = onSkip) { Text(stringResource(R.string.onboarding_skip)) }
        Button(onClick = onNext) { Text(stringResource(R.string.onboarding_next)) }
    }
}

/** The disclosure: « J'accepte » is enabled once the end of the text has been shown, and stays so. */
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.Disclosure(onAccept: () -> Unit, onRefuse: () -> Unit) {
    val scroll = rememberScrollState()
    var reachedEnd by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(scroll) {
        snapshotFlow { scroll.value >= scroll.maxValue }
            .collect { if (it) reachedEnd = true }
    }
    Column(
        modifier = Modifier.weight(1f).verticalScroll(scroll).testTag(DISCLOSURE_TEXT_TAG),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        Title(stringResource(R.string.onboarding_disclosure_title))
        Text(stringResource(R.string.onboarding_disclosure_intro))
        Section(R.string.onboarding_disclosure_reads_title)
        Bullet(R.string.onboarding_disclosure_reads_1)
        Bullet(R.string.onboarding_disclosure_reads_2)
        Section(R.string.onboarding_disclosure_why_title)
        Text(stringResource(R.string.onboarding_disclosure_why))
        Section(R.string.onboarding_disclosure_data_title)
        Bullet(R.string.onboarding_disclosure_data_1)
        Bullet(R.string.onboarding_disclosure_data_2)
        Bullet(R.string.onboarding_disclosure_data_3)
        Section(R.string.onboarding_disclosure_not_title)
        Text(stringResource(R.string.onboarding_disclosure_not))
        Text(stringResource(R.string.onboarding_disclosure_actions))
    }
    if (!reachedEnd) {
        Text(
            stringResource(R.string.onboarding_disclosure_scroll_hint),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    // Same visual weight for both choices (docs/05-conformite-play-store.md §4.1).
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
        OutlinedButton(onClick = onRefuse, modifier = Modifier.weight(1f).testTag(DISCLOSURE_REFUSE_TAG)) {
            Text(stringResource(R.string.onboarding_disclosure_refuse))
        }
        OutlinedButton(
            onClick = onAccept,
            enabled = reachedEnd,
            modifier = Modifier.weight(1f).testTag(DISCLOSURE_ACCEPT_TAG),
        ) {
            Text(stringResource(R.string.onboarding_disclosure_accept))
        }
    }
}

@Composable
private fun Section(title: Int) {
    Text(
        stringResource(title),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.semantics { heading() },
    )
}

@Composable
private fun Bullet(text: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Text("•") // ui-text-ok: a bullet, not text
        Text(stringResource(text))
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.Families(
    selected: Set<AppFamily>,
    apps: Map<AppFamily, List<FamilyApp>>,
    onToggle: (AppFamily) -> Unit,
    onConfirm: () -> Unit,
) {
    Title(stringResource(R.string.onboarding_families_title))
    Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        for (family in FAMILY_ORDER) {
            val label = stringResource(family.label())
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.minTouchTarget)
                    .toggleable(value = family in selected, role = Role.Checkbox, onValueChange = { onToggle(family) }),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                Checkbox(checked = family in selected, onCheckedChange = null)
                Text(label, modifier = Modifier.weight(1f))
                apps[family].orEmpty().take(ICONS_PER_FAMILY).forEach { AppIcon(it.icon, it.label) }
            }
        }
        Text(stringResource(R.string.onboarding_families_never), style = MaterialTheme.typography.bodyMedium)
    }
    Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.onboarding_continue))
    }
}

@Composable
private fun Activate(onOpenSettings: () -> Unit, onHelp: () -> Unit) {
    Title(stringResource(R.string.onboarding_activate_title))
    Text(stringResource(R.string.onboarding_activate_step_1))
    Text(stringResource(R.string.onboarding_activate_step_2))
    Text(stringResource(R.string.onboarding_activate_step_3))
    Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.onboarding_activate_open))
    }
    TextButton(onClick = onHelp) { Text(stringResource(R.string.onboarding_activate_help)) }
}

@Composable
private fun Single(title: Int, action: Int, onAction: () -> Unit) {
    Title(stringResource(title))
    Button(onClick = onAction, modifier = Modifier.fillMaxWidth()) { Text(stringResource(action)) }
}

@Composable
private fun Lock(onChoose: (Boolean) -> Unit) {
    Title(stringResource(R.string.onboarding_lock_title))
    Button(onClick = {
        onChoose(true)
    }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.onboarding_lock_enable)) }
    TextButton(onClick = { onChoose(false) }) { Text(stringResource(R.string.onboarding_lock_later)) }
}

/** The multilingual pack, when no language of the phone is French (docs/specs/ui-ux.md §3.1). */
@Composable
private fun LanguagePack(onChoose: (Boolean) -> Unit) {
    Title(stringResource(R.string.onboarding_pack_title))
    Text(stringResource(R.string.onboarding_pack_text), style = MaterialTheme.typography.bodyLarge)
    Button(onClick = { onChoose(true) }, modifier = Modifier.fillMaxWidth().testTag(PACK_DOWNLOAD_TAG)) {
        Text(stringResource(R.string.onboarding_pack_download))
    }
    TextButton(onClick = { onChoose(false) }) { Text(stringResource(R.string.onboarding_pack_later)) }
}

/** Test tag of « Télécharger » on the language pack screen. */
const val PACK_DOWNLOAD_TAG = "onboarding_pack_download"

private val FAMILY_ORDER = listOf(AppFamily.BROWSERS, AppFamily.MESSAGING, AppFamily.SOCIAL_MEDIA, AppFamily.OTHER)
private const val ICONS_PER_FAMILY = 3

private fun AppFamily.label(): Int = when (this) {
    AppFamily.BROWSERS -> R.string.onboarding_family_browsers
    AppFamily.MESSAGING -> R.string.onboarding_family_messaging
    AppFamily.SOCIAL_MEDIA -> R.string.onboarding_family_social
    AppFamily.OTHER -> R.string.onboarding_family_other
}
