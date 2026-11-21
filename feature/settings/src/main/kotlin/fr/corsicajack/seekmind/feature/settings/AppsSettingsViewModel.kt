package fr.corsicajack.seekmind.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.corsicajack.seekmind.core.database.settings.Settings
import fr.corsicajack.seekmind.core.database.store.AppRule
import fr.corsicajack.seekmind.core.database.store.AppRuleMode
import fr.corsicajack.seekmind.core.model.AppFamily
import fr.corsicajack.seekmind.engine.privacy.AppStatus
import fr.corsicajack.seekmind.engine.privacy.UserPrivacyChoices
import fr.corsicajack.seekmind.engine.privacy.apps.SensitiveApps
import java.text.Collator
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A launchable application: its package and its label. Never anything it showed. */
data class InstalledApp(val packageName: String, val label: String)

/** A row of the list: the application, its effective state and its origin. */
data class AppRow(val app: InstalledApp, val status: AppStatus)

/** State of the « Applications » section and of its list. */
data class AppsUiState(
    val enabledFamilies: Set<AppFamily>,
    /** null while the installed applications are being read. */
    val apps: List<AppRow>?,
    val query: String,
    /** A soft-list application the user wants to include: waiting for the confirmation (§3.3). */
    val pendingInclude: InstalledApp?,
) {
    /** The rows matching [query], by label or package. */
    val shown: List<AppRow>? get() = apps?.filter {
        query.isBlank() || it.app.label.contains(query.trim(), ignoreCase = true) ||
            it.app.packageName.contains(query.trim(), ignoreCase = true)
    }
}

/**
 * « Applications » (docs/specs/ui-ux.md §3.5, docs/specs/confidentialite.md §3.3, §3.4): the four families,
 * then the launchable applications with their effective state and its origin, as the privacy gate decides
 * it ([statusOf]). A switch writes a user rule only when it differs from the default; back to the default,
 * the rule is removed. The strict list and the always-ignored packages are locked; including a soft-list
 * application asks for a confirmation. Both take effect at the next capture: the gate reads the rules and
 * the families as they change.
 */
class AppsSettingsViewModel(
    settings: Flow<Settings>,
    rules: Flow<List<AppRule>>,
    private val installed: suspend () -> List<InstalledApp>,
    private val statusOf: (String, UserPrivacyChoices) -> AppStatus,
    private val setFamilies: suspend (Set<AppFamily>) -> Unit,
    /** null removes the rule. */
    private val setRule: suspend (String, AppRuleMode?) -> Unit,
    private val io: CoroutineDispatcher,
) : ViewModel() {
    private val apps = MutableStateFlow<List<InstalledApp>?>(null)
    private val query = MutableStateFlow("")
    private val pendingInclude = MutableStateFlow<InstalledApp?>(null)
    private var choices: UserPrivacyChoices? = null

    val state: StateFlow<AppsUiState?> = combine(settings, rules, apps, query, pendingInclude) {
            values,
            list,
            installedApps,
            text,
            pending,
        ->
        val current = UserPrivacyChoices(list.associate { it.packageName to it.mode }, values.enabledFamilies)
        choices = current
        AppsUiState(
            enabledFamilies = values.enabledFamilies,
            apps = installedApps?.map { AppRow(it, statusOf(it.packageName, current)) },
            query = text,
            pendingInclude = pending,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /** Reads the installed applications; again when the list comes back, an installation may have happened. */
    fun refresh() {
        viewModelScope.launch {
            val collator = Collator.getInstance()
            apps.value = withContext(io) { installed().sortedWith(compareBy(collator) { it.label }) }
        }
    }

    fun toggleFamily(family: AppFamily) {
        val current = state.value?.enabledFamilies ?: return
        viewModelScope.launch { setFamilies(if (family in current) current - family else current + family) }
    }

    fun setQuery(text: String) {
        query.value = text
    }

    /** The switch of [app]: remembered or not. */
    fun setRemembered(app: InstalledApp, remembered: Boolean) {
        val current = choices ?: return
        if (statusOf(app.packageName, current).locked) return
        val default = statusOf(app.packageName, current.copy(rules = current.rules - app.packageName))
        if (remembered && !default.remembered && default.level == SensitiveApps.Level.SOFT) {
            pendingInclude.value = app
            return
        }
        apply(app, remembered, default)
    }

    fun confirmInclude() {
        val app = pendingInclude.value ?: return
        pendingInclude.value = null
        val current = choices ?: return
        apply(app, true, statusOf(app.packageName, current.copy(rules = current.rules - app.packageName)))
    }

    fun cancelInclude() {
        pendingInclude.value = null
    }

    private fun apply(app: InstalledApp, remembered: Boolean, default: AppStatus) {
        val mode = when {
            remembered == default.remembered -> null
            remembered -> AppRuleMode.INCLUDED
            else -> AppRuleMode.EXCLUDED
        }
        viewModelScope.launch { setRule(app.packageName, mode) }
    }
}
