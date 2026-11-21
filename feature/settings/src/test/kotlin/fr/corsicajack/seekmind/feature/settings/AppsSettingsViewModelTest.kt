package fr.corsicajack.seekmind.feature.settings

import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.core.database.settings.SettingsRepository
import fr.corsicajack.seekmind.core.database.store.AppRule
import fr.corsicajack.seekmind.core.database.store.AppRuleMode
import fr.corsicajack.seekmind.core.model.AppFamily
import fr.corsicajack.seekmind.engine.privacy.AppStatus
import fr.corsicajack.seekmind.engine.privacy.AppStatusReason
import fr.corsicajack.seekmind.engine.privacy.UserPrivacyChoices
import fr.corsicajack.seekmind.engine.privacy.apps.SensitiveApps
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/** Settings → Applications (ticket M6-06): families, rules, the locked list, the confirmation. */
class AppsSettingsViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val settings = MutableStateFlow(SettingsRepository.DEFAULTS)
    private val rules = MutableStateFlow<List<AppRule>>(emptyList())
    private val written = mutableListOf<Pair<String, AppRuleMode?>>()

    /** The gate's rules, simplified: strict, soft, then the user's rule, then the family. */
    private fun statusOf(packageName: String, choices: UserPrivacyChoices): AppStatus {
        val level = when (packageName) {
            STRICT.packageName -> SensitiveApps.Level.STRICT
            BANK.packageName -> SensitiveApps.Level.SOFT
            else -> null
        }
        val family = if (level == null) AppFamily.OTHER else null
        val reason = when {
            level == SensitiveApps.Level.STRICT -> AppStatusReason.STRICT_LIST
            choices.rules[packageName] == AppRuleMode.EXCLUDED -> AppStatusReason.USER_EXCLUDED
            choices.rules[packageName] == AppRuleMode.INCLUDED -> AppStatusReason.USER_INCLUDED
            level == SensitiveApps.Level.SOFT -> AppStatusReason.SOFT_LIST
            AppFamily.OTHER !in choices.enabledFamilies -> AppStatusReason.FAMILY_DISABLED
            else -> AppStatusReason.FAMILY_ENABLED
        }
        return AppStatus(reason, family, level)
    }

    private fun model() = AppsSettingsViewModel(
        settings = settings,
        rules = rules,
        installed = { listOf(NOTES, BANK, STRICT) },
        statusOf = ::statusOf,
        setFamilies = { settings.value = settings.value.copy(enabledFamilies = it) },
        setRule = { packageName, mode ->
            written += packageName to mode
            rules.value = rules.value.filter { it.packageName != packageName } +
                listOfNotNull(mode?.let { AppRule(packageName, it, 0) })
        },
        io = main.dispatcher,
    )

    private suspend fun AppsSettingsViewModel.loaded(): AppsUiState {
        refresh()
        return state.filterNotNull().first { it.apps != null }
    }

    @Test
    fun `turning a family off writes the families`() = runTest {
        val model = model()
        model.loaded()
        model.toggleFamily(AppFamily.OTHER)
        assertThat(settings.value.enabledFamilies).doesNotContain(AppFamily.OTHER)
        assertThat(model.loaded().apps!!.first { it.app == NOTES }.status.reason)
            .isEqualTo(AppStatusReason.FAMILY_DISABLED)
    }

    @Test
    fun `excluding an application writes a rule, and back to the default removes it`() = runTest {
        val model = model()
        model.loaded()
        model.setRemembered(NOTES, false)
        assertThat(rules.value).containsExactly(AppRule(NOTES.packageName, AppRuleMode.EXCLUDED, 0))
        model.setRemembered(NOTES, true)
        assertThat(rules.value).isEmpty()
        assertThat(
            written,
        ).containsExactly(NOTES.packageName to AppRuleMode.EXCLUDED, NOTES.packageName to null).inOrder()
    }

    @Test
    fun `including a soft-list application waits for the confirmation`() = runTest {
        val model = model()
        model.loaded()
        model.setRemembered(BANK, true)
        assertThat(written).isEmpty()
        assertThat(model.loaded().pendingInclude).isEqualTo(BANK)
        model.confirmInclude()
        assertThat(rules.value).containsExactly(AppRule(BANK.packageName, AppRuleMode.INCLUDED, 0))
        assertThat(model.loaded().pendingInclude).isNull()
    }

    @Test
    fun `a cancelled confirmation writes nothing`() = runTest {
        val model = model()
        model.loaded()
        model.setRemembered(BANK, true)
        model.cancelInclude()
        assertThat(written).isEmpty()
    }

    @Test
    fun `the strict list is locked`() = runTest {
        val model = model()
        val row = model.loaded().apps!!.first { it.app == STRICT }
        assertThat(row.status.locked).isTrue()
        model.setRemembered(STRICT, true)
        assertThat(written).isEmpty()
    }

    @Test
    fun `the search filters by label or package`() = runTest {
        val model = model()
        model.loaded()
        model.setQuery("bank")
        assertThat(model.loaded().shown!!.map { it.app }).containsExactly(BANK)
        model.setQuery("Not")
        assertThat(model.loaded().shown!!.map { it.app }).containsExactly(NOTES)
    }

    private companion object {
        val NOTES = InstalledApp("com.example.notes", "Notes")
        val BANK = InstalledApp("com.example.mybank", "Ma banque")
        val STRICT = InstalledApp("com.example.vault", "Coffre")
    }
}
