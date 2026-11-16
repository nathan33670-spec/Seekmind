package fr.corsicajack.seekmind.engine.ads

import android.app.Activity
import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.core.common.DispatcherProvider
import fr.corsicajack.seekmind.core.model.RewardOutcome
import fr.corsicajack.seekmind.core.model.RewardReadiness
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** [AdMobRewardProvider] with a simulated SDK (docs/specs/monetisation.md §3.3). */
class AdMobRewardProviderTest {
    private val dispatcher = StandardTestDispatcher()
    private val dispatchers = object : DispatcherProvider {
        override val main: CoroutineDispatcher = dispatcher
        override val io: CoroutineDispatcher = dispatcher
        override val default: CoroutineDispatcher = dispatcher
    }
    private val config = AdsConfig(rewardedUnitId = AdsConfig.TEST_REWARDED_UNIT_ID)

    /** Never read: the simulated SDK ignores it. On the JVM, Activity is a stub. */
    private val activity: Activity = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").let { field ->
        field.isAccessible = true
        val unsafe = field.get(null)
        unsafe.javaClass.getMethod(
            "allocateInstance",
            Class::class.java,
        ).invoke(unsafe, Activity::class.java) as Activity
    }

    private object Ad : LoadedAd

    private class FakeSdk : AdsSdk {
        var consent: ConsentResult = ConsentResult.Updated(privacyOptionsRequired = false)
        var canRequest = true
        var load: suspend () -> LoadResult = { LoadResult.Loaded(Ad) }
        var showScript: (ShowEvents) -> Unit = {
            it.onRewardEarned()
            it.onDismissed()
        }
        var initializations = 0
        var loads = 0
        var privacyForms = 0
        val calls = mutableListOf<String>()

        override suspend fun requestConsent(activity: Activity): ConsentResult = consent.also { calls += "consent" }

        override fun canRequestAds(activity: Activity) = canRequest

        override suspend fun initialize(activity: Activity, config: AdsConfig) {
            initializations++
            calls += "initialize"
        }

        override suspend fun load(activity: Activity, config: AdsConfig): LoadResult {
            loads++
            calls += "load"
            return load()
        }

        override fun show(activity: Activity, ad: LoadedAd, events: ShowEvents) = showScript(events)

        override suspend fun showPrivacyOptions(activity: Activity) {
            privacyForms++
        }
    }

    private val sdk = FakeSdk()
    private val provider = AdMobRewardProvider(sdk, config, dispatchers)

    private fun test(block: suspend TestScope.() -> Unit) = runTest(dispatcher) { block() }

    @Test
    fun `consent comes first, then a single initialization, then the load`() = test {
        assertThat(provider.prepare(activity)).isEqualTo(RewardReadiness.READY)
        assertThat(provider.prepare(activity)).isEqualTo(RewardReadiness.READY)
        assertThat(sdk.calls).containsExactly("consent", "initialize", "load", "consent", "load").inOrder()
        assertThat(sdk.initializations).isEqualTo(1)
    }

    @Test
    fun `nothing is loaded without the consent`() = test {
        sdk.canRequest = false
        assertThat(provider.prepare(activity)).isEqualTo(RewardReadiness.NOT_ALLOWED)
        assertThat(sdk.initializations).isEqualTo(0)
        assertThat(sdk.loads).isEqualTo(0)
    }

    @Test
    fun `a consent that cannot be asked for lack of a connection is offline`() = test {
        sdk.canRequest = false
        sdk.consent = ConsentResult.Failed(network = true)
        assertThat(provider.prepare(activity)).isEqualTo(RewardReadiness.OFFLINE)
        sdk.consent = ConsentResult.Failed(network = false)
        assertThat(provider.prepare(activity)).isEqualTo(RewardReadiness.NOT_ALLOWED)
    }

    @Test
    fun `a consent given earlier still allows an ad when the update fails`() = test {
        sdk.consent = ConsentResult.Failed(network = true)
        assertThat(provider.prepare(activity)).isEqualTo(RewardReadiness.READY)
    }

    @Test
    fun `the load errors map to no fill, offline and error`() = test {
        val cases = mapOf(
            LoadFailure.NO_FILL to RewardReadiness.NO_FILL,
            LoadFailure.NETWORK to RewardReadiness.OFFLINE,
            LoadFailure.OTHER to RewardReadiness.ERROR,
        )
        for ((failure, readiness) in cases) {
            sdk.load = { LoadResult.Failed(failure, code = 3) }
            assertThat(provider.prepare(activity)).isEqualTo(readiness)
            assertThat(provider.show(activity)).isEqualTo(RewardOutcome.FAILED)
        }
    }

    @Test
    fun `a load longer than the maximal delay is abandoned as offline`() = test {
        sdk.load = { awaitCancellation() }
        assertThat(provider.prepare(activity)).isEqualTo(RewardReadiness.OFFLINE)
        assertThat(currentTime).isEqualTo(config.loadTimeoutMs)
        assertThat(provider.show(activity)).isEqualTo(RewardOutcome.FAILED)
    }

    @Test
    fun `earned only when the reward is reported before the dismissal`() = test {
        provider.prepare(activity)
        assertThat(provider.show(activity)).isEqualTo(RewardOutcome.EARNED)

        sdk.showScript = {
            it.onDismissed()
            it.onRewardEarned()
        }
        provider.prepare(activity)
        assertThat(provider.show(activity)).isEqualTo(RewardOutcome.DISMISSED)

        sdk.showScript = { it.onDismissed() }
        provider.prepare(activity)
        assertThat(provider.show(activity)).isEqualTo(RewardOutcome.DISMISSED)
    }

    @Test
    fun `a failed display is failed, and an ad is shown only once`() = test {
        sdk.showScript = { it.onFailedToShow(1) }
        provider.prepare(activity)
        assertThat(provider.show(activity)).isEqualTo(RewardOutcome.FAILED)

        sdk.showScript = {
            it.onRewardEarned()
            it.onDismissed()
        }
        provider.prepare(activity)
        assertThat(provider.show(activity)).isEqualTo(RewardOutcome.EARNED)
        assertThat(provider.show(activity)).isEqualTo(RewardOutcome.FAILED)
    }

    @Test
    fun `the privacy options follow the consent information`() = test {
        assertThat(provider.privacyOptionsRequired.value).isFalse()
        sdk.consent = ConsentResult.Updated(privacyOptionsRequired = true)
        provider.prepare(activity)
        assertThat(provider.privacyOptionsRequired.value).isTrue()
        provider.showPrivacyOptions(activity)
        assertThat(sdk.privacyForms).isEqualTo(1)
    }

    @Test
    fun `nothing is asked of the SDK before prepare`() {
        assertThat(provider.isSupported).isTrue()
        assertThat(sdk.calls).isEmpty()
    }
}
