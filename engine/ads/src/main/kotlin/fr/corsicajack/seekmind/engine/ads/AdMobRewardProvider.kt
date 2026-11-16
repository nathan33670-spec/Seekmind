package fr.corsicajack.seekmind.engine.ads

import android.app.Activity
import fr.corsicajack.seekmind.core.common.DispatcherProvider
import fr.corsicajack.seekmind.core.common.SmLog
import fr.corsicajack.seekmind.core.model.RewardOutcome
import fr.corsicajack.seekmind.core.model.RewardReadiness
import fr.corsicajack.seekmind.core.model.port.RewardProvider
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Rewarded ads through AdMob (docs/specs/monetisation.md §3.3, ADR-0009).
 *
 * Nothing reaches the network before [prepare]: the SDK is neither started nor asked for consent at the
 * start of the application or of the service (§3.4). The SDK is started once per process, after the
 * consent, off the main thread. An ad is shown once, then forgotten.
 */
class AdMobRewardProvider internal constructor(
    private val sdk: AdsSdk,
    private val config: AdsConfig,
    private val dispatchers: DispatcherProvider,
) : RewardProvider {
    private val mutex = Mutex()
    private var initialized = false
    private var prepared: LoadedAd? = null
    private val privacyOptions = MutableStateFlow(false)

    override val isSupported: Boolean = true

    override val privacyOptionsRequired: StateFlow<Boolean> = privacyOptions.asStateFlow()

    override suspend fun prepare(activity: Activity): RewardReadiness = mutex.withLock {
        prepared = null
        val consent = withContext(dispatchers.main) { sdk.requestConsent(activity) }
        if (consent is ConsentResult.Updated) privacyOptions.value = consent.privacyOptionsRequired
        if (!withContext(dispatchers.main) { sdk.canRequestAds(activity) }) {
            // Without a connection the consent cannot be asked: the user is not refused, only offline.
            return@withLock if (consent is ConsentResult.Failed && consent.network) {
                RewardReadiness.OFFLINE
            } else {
                RewardReadiness.NOT_ALLOWED
            }
        }
        // The start of the SDK and the load share the maximal delay.
        val result = withTimeoutOrNull(config.loadTimeoutMs) {
            if (!initialized) {
                withContext(dispatchers.io) { sdk.initialize(activity, config) }
                initialized = true
            }
            withContext(dispatchers.main) { sdk.load(activity, config) }
        }
        when (result) {
            null -> {
                SmLog.i(TAG, "ad_load_timeout", "timeoutMs" to config.loadTimeoutMs)
                RewardReadiness.OFFLINE
            }

            is LoadResult.Loaded -> {
                prepared = result.ad
                RewardReadiness.READY
            }

            is LoadResult.Failed -> {
                SmLog.i(TAG, "ad_load_failed", "code" to result.code)
                when (result.reason) {
                    LoadFailure.NO_FILL -> RewardReadiness.NO_FILL
                    LoadFailure.NETWORK -> RewardReadiness.OFFLINE
                    LoadFailure.OTHER -> RewardReadiness.ERROR
                }
            }
        }
    }

    override suspend fun show(activity: Activity): RewardOutcome {
        val ad = mutex.withLock { prepared.also { prepared = null } } ?: return RewardOutcome.FAILED
        return withContext(dispatchers.main) {
            suspendCancellableCoroutine { continuation ->
                sdk.show(
                    activity,
                    ad,
                    object : ShowEvents {
                        private var earned = false
                        private var done = false

                        override fun onRewardEarned() {
                            if (!done) earned = true
                        }

                        override fun onDismissed() =
                            finish(if (earned) RewardOutcome.EARNED else RewardOutcome.DISMISSED)

                        override fun onFailedToShow(code: Int) {
                            SmLog.i(TAG, "ad_show_failed", "code" to code)
                            finish(RewardOutcome.FAILED)
                        }

                        private fun finish(outcome: RewardOutcome) {
                            if (done) return
                            done = true
                            if (continuation.isActive) continuation.resume(outcome)
                        }
                    },
                )
            }
        }
    }

    override suspend fun showPrivacyOptions(activity: Activity) {
        withContext(dispatchers.main) { sdk.showPrivacyOptions(activity) }
    }

    companion object {
        private const val TAG = "Ads"

        /** The provider backed by Google's SDKs. */
        fun create(config: AdsConfig, dispatchers: DispatcherProvider): AdMobRewardProvider =
            AdMobRewardProvider(GoogleAdsSdk(), config, dispatchers)
    }
}
