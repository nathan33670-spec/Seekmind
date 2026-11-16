package fr.corsicajack.seekmind.feature.onboarding

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.viewModelScope
import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.core.database.settings.Consent
import fr.corsicajack.seekmind.core.database.settings.DISCLOSURE_VERSION
import fr.corsicajack.seekmind.core.database.settings.Settings
import fr.corsicajack.seekmind.core.database.settings.SettingsRepository
import fr.corsicajack.seekmind.core.model.AppFamily
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test

/** docs/specs/ui-ux.md §3.1 and docs/05-conformite-play-store.md §4 (ticket M6-02). */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {
    private lateinit var dir: File
    private lateinit var settings: SettingsRepository
    private val dataStoreScope = CoroutineScope(Dispatchers.IO + Job())
    private val service = MutableStateFlow(false)
    private var secure = true
    private val models = mutableListOf<OnboardingViewModel>()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        dir = Files.createTempDirectory("onboarding").toFile()
        settings =
            SettingsRepository(
                PreferenceDataStoreFactory.create(scope = dataStoreScope) {
                    File(dir, "s.preferences_pb")
                },
            )
    }

    @After
    fun tearDown() {
        runBlocking {
            models.forEach { it.viewModelScope.coroutineContext.job.cancelAndJoin() }
            dataStoreScope.coroutineContext.job.cancelAndJoin()
        }
        Dispatchers.resetMain()
        dir.deleteRecursively()
    }

    private fun model(start: OnboardingStart = OnboardingStart.WELCOME) =
        OnboardingViewModel(settings, start, service, { secure }, { NOW }).also { models += it }

    /** DataStore writes on its own thread: waits for the step (or fails after 5 s). */
    private fun OnboardingViewModel.await(step: OnboardingStep) = runBlocking {
        withContext(Dispatchers.Default) { withTimeout(5_000) { state.first { it.step == step } } }
    }

    private fun current(): Settings = runBlocking { settings.current() }

    @Test
    fun `three welcome pages, then the disclosure, and skip goes straight to it`() {
        val model = model()
        repeat(2) { model.nextIntroPage() }
        assertThat(model.state.value.introPage).isEqualTo(2)
        model.nextIntroPage()
        assertThat(model.state.value.step).isEqualTo(OnboardingStep.DISCLOSURE)
        val skipped = model()
        skipped.skipIntro()
        assertThat(skipped.state.value.step).isEqualTo(OnboardingStep.DISCLOSURE)
    }

    @Test
    fun `nothing is recorded at the tap, the consent and the families come together at their confirmation`() {
        val model = model(OnboardingStart.DISCLOSURE)
        assertThat(model.mayOpenSystemSettings()).isFalse()
        model.accept()
        model.await(OnboardingStep.FAMILIES)
        // Between « J'accepte » and the families, the capture has no consent: it cannot start on families
        // the user did not choose (INV-04).
        assertThat(current().hasCurrentConsent()).isFalse()
        assertThat(model.mayOpenSystemSettings()).isFalse()
        model.toggleFamily(AppFamily.MESSAGING)
        model.confirmFamilies()
        model.await(OnboardingStep.ACTIVATE)
        assertThat(current().consent).isEqualTo(Consent(DISCLOSURE_VERSION, NOW))
        assertThat(current().enabledFamilies).doesNotContain(AppFamily.MESSAGING)
    }

    @Test
    fun `back from the families is a refusal`() {
        val model = model(OnboardingStart.DISCLOSURE)
        model.accept()
        model.await(OnboardingStep.FAMILIES)
        model.leave()
        model.await(OnboardingStep.DONE)
        assertThat(current().consent).isNull()
        assertThat(model.state.value.refused).isTrue()
    }

    @Test
    fun `the families are recorded before the activation screen, the only one that opens the settings`() {
        val model = model(OnboardingStart.DISCLOSURE)
        model.accept()
        model.await(OnboardingStep.FAMILIES)
        assertThat(model.state.value.families).containsExactlyElementsIn(AppFamily.entries)
        model.toggleFamily(AppFamily.SOCIAL_MEDIA)
        model.confirmFamilies()
        model.await(OnboardingStep.ACTIVATE)
        assertThat(current().enabledFamilies).containsExactly(AppFamily.BROWSERS, AppFamily.MESSAGING, AppFamily.OTHER)
        assertThat(model.mayOpenSystemSettings()).isTrue()
    }

    @Test
    fun `back from the settings with the service on confirms, then the lock is offered`() {
        val model = model(OnboardingStart.DISCLOSURE)
        model.accept()
        model.await(OnboardingStep.FAMILIES)
        model.confirmFamilies()
        model.await(OnboardingStep.ACTIVATE)
        service.value = true
        model.await(OnboardingStep.CONFIRMED)
        model.continueAfterConfirmation()
        model.await(OnboardingStep.LOCK)
        model.chooseLock(false)
        model.await(OnboardingStep.DONE)
        assertThat(current().lockEnabled).isFalse()
        assertThat(current().onboardingDone).isTrue()
        assertThat(model.state.value.refused).isFalse()
    }

    @Test
    fun `without a screen lock the lock step is skipped`() {
        secure = false
        service.value = true
        val model = model(OnboardingStart.DISCLOSURE)
        model.accept()
        model.await(OnboardingStep.FAMILIES)
        model.confirmFamilies()
        model.await(OnboardingStep.CONFIRMED)
        model.continueAfterConfirmation()
        model.await(OnboardingStep.DONE)
        assertThat(current().lockEnabled).isTrue()
    }

    @Test
    fun `refusing records no consent and ends in the limited mode`() {
        val model = model(OnboardingStart.DISCLOSURE)
        model.refuse()
        model.await(OnboardingStep.DONE)
        assertThat(current().consent).isNull()
        assertThat(current().onboardingDone).isTrue()
        assertThat(model.state.value.refused).isTrue()
        assertThat(model.mayOpenSystemSettings()).isFalse()
    }

    @Test
    fun `back from the disclosure is a refusal`() {
        val model = model(OnboardingStart.DISCLOSURE)
        model.leave()
        model.await(OnboardingStep.DONE)
        assertThat(current().consent).isNull()
        assertThat(model.state.value.refused).isTrue()
    }

    @Test
    fun `a consent of an older version does not count`() {
        val old = Settings(
            consent = Consent(DISCLOSURE_VERSION - 1, NOW),
            enabledFamilies = emptySet(),
            pausedUntil = null,
            retentionDays = null,
            storageBudgetBytes = 0,
            lockEnabled = true,
            lockGraceMs = 0,
            activeModelId = "fr",
            historyEnabled = true,
        )
        assertThat(old.hasCurrentConsent()).isFalse()
        assertThat(old.copy(consent = Consent(DISCLOSURE_VERSION, NOW)).hasCurrentConsent()).isTrue()
        assertThat(old.copy(consent = null).hasCurrentConsent()).isFalse()
    }

    private companion object {
        const val NOW = 1_794_441_600_000L
    }
}
