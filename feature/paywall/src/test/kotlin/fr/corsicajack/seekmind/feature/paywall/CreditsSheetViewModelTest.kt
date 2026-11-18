package fr.corsicajack.seekmind.feature.paywall

import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.core.model.RewardOutcome
import fr.corsicajack.seekmind.core.model.RewardReadiness
import fr.corsicajack.seekmind.core.model.config.MonetizationConfig
import fr.corsicajack.seekmind.core.testing.FakeClock
import fr.corsicajack.seekmind.core.testing.FakeMonetizationStore
import fr.corsicajack.seekmind.core.testing.FakeRewardProvider
import fr.corsicajack.seekmind.core.testing.FakeRewardProvider.Companion.ACTIVITY
import fr.corsicajack.seekmind.engine.monetization.CreditLedger
import fr.corsicajack.seekmind.engine.monetization.RewardFlow
import fr.corsicajack.seekmind.engine.monetization.RewardMessage
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/** The credits sheet (docs/specs/monetisation.md §6, ticket M8-04). */
@OptIn(ExperimentalCoroutinesApi::class)
class CreditsSheetViewModelTest {
    private val store = FakeMonetizationStore()
    private val ledger = CreditLedger(store, MonetizationConfig(), FakeClock()) { ZoneOffset.UTC }
    private val ads = FakeRewardProvider()
    private var credited = 0

    private fun model(provider: FakeRewardProvider? = ads) = CreditsSheetViewModel(RewardFlow(ledger, provider)) {
        credited++
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `the ad is prepared when the sheet opens, not before, and once`() {
        val model = model()
        assertThat(model.state.value.button).isEqualTo(AdButton.LOADING)
        assertThat(ads.prepares).isEqualTo(0)
        model.open(ACTIVITY)
        model.open(ACTIVITY)
        assertThat(ads.prepares).isEqualTo(1)
        assertThat(model.state.value.button).isEqualTo(AdButton.READY)
    }

    @Test
    fun `a reward credits, says so and lets the search go on`() {
        val model = model()
        model.open(ACTIVITY)
        model.watch(ACTIVITY)
        assertThat(model.state.value.message).isEqualTo(RewardMessage.Earned(5))
        assertThat(credited).isEqualTo(1)
    }

    @Test
    fun `a dismissed ad prepares the next one`() {
        ads.outcome = RewardOutcome.DISMISSED
        val model = model()
        model.open(ACTIVITY)
        model.watch(ACTIVITY)
        assertThat(model.state.value).isEqualTo(CreditsSheetState(AdButton.READY, RewardMessage.Dismissed))
        assertThat(ads.prepares).isEqualTo(2)
        assertThat(credited).isEqualTo(0)
    }

    @Test
    fun `no fill gives the courtesy search`() {
        ads.readiness = RewardReadiness.NO_FILL
        val model = model()
        model.open(ACTIVITY)
        model.watch(ACTIVITY)
        assertThat(model.state.value.message).isEqualTo(RewardMessage.Courtesy(1))
        assertThat(credited).isEqualTo(1)
    }

    @Test
    fun `without ads no button and nothing prepared`() {
        val model = model(FakeRewardProvider(isSupported = false))
        model.open(ACTIVITY)
        model.watch(ACTIVITY)
        assertThat(model.state.value.button).isEqualTo(AdButton.ABSENT)
        val none = model(null)
        none.open(ACTIVITY)
        assertThat(none.state.value.button).isEqualTo(AdButton.ABSENT)
    }

    @Test
    fun `watching before the ad is ready does nothing`() {
        val model = model()
        model.watch(ACTIVITY)
        assertThat(ads.shows).isEqualTo(0)
    }
}
