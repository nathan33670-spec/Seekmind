package fr.corsicajack.seekmind.engine.ads

import android.app.Activity
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.FormError
import com.google.android.ump.UserMessagingPlatform
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * [AdsSdk] with Google's SDKs. The ad request is bare (docs/specs/monetisation.md §3.3): no keyword, no
 * content URL, no custom targeting, no publisher-provided identifier.
 */
internal class GoogleAdsSdk : AdsSdk {
    override suspend fun requestConsent(activity: Activity): ConsentResult {
        val information = UserMessagingPlatform.getConsentInformation(activity)
        val updated = suspendCancellableCoroutine<FormError?> { continuation ->
            information.requestConsentInfoUpdate(
                activity,
                ConsentRequestParameters.Builder().build(),
                { if (continuation.isActive) continuation.resume(null) },
                { error -> if (continuation.isActive) continuation.resume(error) },
            )
        }
        if (updated != null) return ConsentResult.Failed(network = updated.isNetwork())
        // Google's certified form, shown only where the regulations require it.
        suspendCancellableCoroutine { continuation ->
            UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) {
                if (continuation.isActive) continuation.resume(Unit)
            }
        }
        val required = information.privacyOptionsRequirementStatus ==
            ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
        return ConsentResult.Updated(privacyOptionsRequired = required)
    }

    override fun canRequestAds(activity: Activity): Boolean =
        UserMessagingPlatform.getConsentInformation(activity).canRequestAds()

    override suspend fun initialize(activity: Activity, config: AdsConfig) {
        MobileAds.setRequestConfiguration(
            RequestConfiguration.Builder().setMaxAdContentRating(config.maxContentRating.toSdk()).build(),
        )
        suspendCancellableCoroutine { continuation ->
            MobileAds.initialize(activity.applicationContext) { if (continuation.isActive) continuation.resume(Unit) }
        }
    }

    override suspend fun load(activity: Activity, config: AdsConfig): LoadResult =
        suspendCancellableCoroutine { continuation ->
            RewardedAd.load(
                activity,
                config.rewardedUnitId,
                AdRequest.Builder().build(),
                object : RewardedAdLoadCallback() {
                    override fun onAdLoaded(ad: RewardedAd) {
                        if (continuation.isActive) continuation.resume(LoadResult.Loaded(Rewarded(ad)))
                    }

                    override fun onAdFailedToLoad(error: LoadAdError) {
                        val reason = when (error.code) {
                            AdRequest.ERROR_CODE_NO_FILL, AdRequest.ERROR_CODE_MEDIATION_NO_FILL -> LoadFailure.NO_FILL
                            AdRequest.ERROR_CODE_NETWORK_ERROR -> LoadFailure.NETWORK
                            else -> LoadFailure.OTHER
                        }
                        if (continuation.isActive) continuation.resume(LoadResult.Failed(reason, error.code))
                    }
                },
            )
        }

    override fun show(activity: Activity, ad: LoadedAd, events: ShowEvents) {
        val rewarded = (ad as Rewarded).ad
        rewarded.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() = events.onDismissed()

            override fun onAdFailedToShowFullScreenContent(error: AdError) = events.onFailedToShow(error.code)
        }
        rewarded.show(activity) { events.onRewardEarned() }
    }

    override suspend fun showPrivacyOptions(activity: Activity) {
        suspendCancellableCoroutine { continuation ->
            UserMessagingPlatform.showPrivacyOptionsForm(activity) {
                if (continuation.isActive) continuation.resume(Unit)
            }
        }
    }

    private class Rewarded(val ad: RewardedAd) : LoadedAd

    private fun FormError.isNetwork() =
        errorCode == FormError.ErrorCode.INTERNET_ERROR || errorCode == FormError.ErrorCode.TIME_OUT

    private fun AdsConfig.ContentRating.toSdk() = when (this) {
        AdsConfig.ContentRating.GENERAL -> RequestConfiguration.MAX_AD_CONTENT_RATING_G
        AdsConfig.ContentRating.PARENTAL_GUIDANCE -> RequestConfiguration.MAX_AD_CONTENT_RATING_PG
        AdsConfig.ContentRating.TEEN -> RequestConfiguration.MAX_AD_CONTENT_RATING_T
    }
}
