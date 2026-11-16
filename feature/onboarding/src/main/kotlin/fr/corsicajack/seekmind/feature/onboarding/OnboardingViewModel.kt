package fr.corsicajack.seekmind.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.corsicajack.seekmind.core.database.settings.Consent
import fr.corsicajack.seekmind.core.database.settings.DISCLOSURE_VERSION
import fr.corsicajack.seekmind.core.database.settings.SettingsRepository
import fr.corsicajack.seekmind.core.model.AppFamily
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The screens of the welcome flow, in their order (docs/specs/ui-ux.md §3.1). */
enum class OnboardingStep { INTRO, DISCLOSURE, FAMILIES, ACTIVATE, CONFIRMED, LOCK, DONE }

/** Where the flow starts: the welcome pages, or the disclosure reopened from the banner (EF-ONB-04). */
enum class OnboardingStart { WELCOME, DISCLOSURE }

data class OnboardingUiState(
    val step: OnboardingStep,
    val introPage: Int = 0,
    val families: Set<AppFamily> = AppFamily.entries.toSet(),
    /** The disclosure was refused (limited mode); only meaningful once [step] is [OnboardingStep.DONE]. */
    val refused: Boolean = false,
)

/**
 * The welcome flow (docs/specs/ui-ux.md §3.1, docs/05-conformite-play-store.md §4). The consent is
 * recorded at the tap on « J'accepte », the families when their screen is confirmed: both before any
 * redirection to the system settings (INV-04). Refusing, closing or going back from the disclosure is a
 * refusal: nothing is recorded but the fact that the flow was gone through.
 */
class OnboardingViewModel(
    private val settings: SettingsRepository,
    start: OnboardingStart,
    serviceEnabled: Flow<Boolean>,
    private val isDeviceSecure: () -> Boolean,
    private val now: () -> Long,
) : ViewModel() {
    private val mutable = MutableStateFlow(
        OnboardingUiState(if (start == OnboardingStart.WELCOME) OnboardingStep.INTRO else OnboardingStep.DISCLOSURE),
    )
    val state: StateFlow<OnboardingUiState> = mutable.asStateFlow()

    // The writes of the flow run one after the other: a tap never overtakes the previous one.
    private val writes = Mutex()
    private var enabled = false

    init {
        viewModelScope.launch {
            serviceEnabled.collect { on ->
                enabled = on
                // Back from the system settings with the service on: the activation is confirmed.
                if (on) {
                    mutable.update {
                        if (it.step ==
                            OnboardingStep.ACTIVATE
                        ) {
                            it.copy(step = OnboardingStep.CONFIRMED)
                        } else {
                            it
                        }
                    }
                }
            }
        }
    }

    fun nextIntroPage() = mutable.update {
        if (it.step != OnboardingStep.INTRO) {
            it
        } else if (it.introPage < INTRO_PAGES - 1) {
            it.copy(introPage = it.introPage + 1)
        } else {
            it.copy(step = OnboardingStep.DISCLOSURE)
        }
    }

    fun skipIntro() = mutable.update {
        if (it.step ==
            OnboardingStep.INTRO
        ) {
            it.copy(step = OnboardingStep.DISCLOSURE)
        } else {
            it
        }
    }

    /** « J'accepte »: the consent (version, time of the tap), then the families. */
    fun accept() = write {
        if (mutable.value.step != OnboardingStep.DISCLOSURE) return@write
        settings.acceptConsent(Consent(DISCLOSURE_VERSION, now()))
        val families = settings.current().enabledFamilies
        mutable.update { it.copy(step = OnboardingStep.FAMILIES, families = families) }
    }

    /** « Non merci », back or close: limited mode, the disclosure can be reopened at any time. */
    fun refuse() = write {
        if (mutable.value.step != OnboardingStep.DISCLOSURE) return@write
        settings.setOnboardingDone()
        mutable.update { it.copy(step = OnboardingStep.DONE, refused = true) }
    }

    fun toggleFamily(family: AppFamily) = mutable.update {
        if (it.step != OnboardingStep.FAMILIES) it else it.copy(families = it.families.toggled(family))
    }

    /** « Continuer »: the families are recorded before the activation screen, hence before any redirection. */
    fun confirmFamilies() = write {
        val current = mutable.value
        if (current.step != OnboardingStep.FAMILIES) return@write
        settings.setEnabledFamilies(current.families)
        val next = if (enabled) OnboardingStep.CONFIRMED else OnboardingStep.ACTIVATE
        mutable.update { it.copy(step = next) }
    }

    /** Whether the activation screen may send the user to the system settings (INV-04). */
    fun mayOpenSystemSettings(): Boolean = mutable.value.step == OnboardingStep.ACTIVATE

    /** « Continuer » after the confirmation: the lock is offered when the phone has a screen lock. */
    fun continueAfterConfirmation() = write {
        if (mutable.value.step != OnboardingStep.CONFIRMED) return@write
        if (isDeviceSecure()) mutable.update { it.copy(step = OnboardingStep.LOCK) } else finishLocked()
    }

    /** « Activer » or « Plus tard » on the lock screen. */
    fun chooseLock(enabled: Boolean) = write {
        if (mutable.value.step != OnboardingStep.LOCK) return@write
        settings.setLockEnabled(enabled)
        finishLocked()
    }

    /** Back after the disclosure was accepted: the flow ends, what was chosen stays. */
    fun leave() = write {
        when (mutable.value.step) {
            OnboardingStep.DISCLOSURE -> {
                settings.setOnboardingDone()
                mutable.update { it.copy(step = OnboardingStep.DONE, refused = true) }
            }

            OnboardingStep.INTRO, OnboardingStep.DONE -> Unit

            else -> finishLocked()
        }
    }

    private suspend fun finishLocked() {
        settings.setOnboardingDone()
        mutable.update { it.copy(step = OnboardingStep.DONE) }
    }

    private fun write(block: suspend () -> Unit) {
        viewModelScope.launch { writes.withLock { block() } }
    }

    private fun Set<AppFamily>.toggled(family: AppFamily) = if (family in this) this - family else this + family

    companion object {
        const val INTRO_PAGES = 3
    }
}
