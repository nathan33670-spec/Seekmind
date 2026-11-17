package fr.corsicajack.seekmind.engine.ads

import android.app.Activity

/**
 * The calls made to the ads and consent SDKs, behind an interface so that [AdMobRewardProvider] is
 * tested on the JVM. Nothing in it carries user data (INV-01, INV-08).
 */
internal interface AdsSdk {
    /** Updates the consent information, then shows Google's consent form if it is required. */
    suspend fun requestConsent(activity: Activity): ConsentResult

    /** Whether ads may be requested, from the consent information known so far. */
    fun canRequestAds(activity: Activity): Boolean

    /** Starts the ads SDK. Called once per process, once ads may be requested. */
    suspend fun initialize(activity: Activity, config: AdsConfig)

    /** Loads a rewarded ad with a bare request. */
    suspend fun load(activity: Activity, config: AdsConfig): LoadResult

    /** Shows [ad] and reports what happens on [events]. */
    fun show(activity: Activity, ad: LoadedAd, events: ShowEvents)

    suspend fun showPrivacyOptions(activity: Activity)
}

internal sealed interface ConsentResult {
    data class Updated(val privacyOptionsRequired: Boolean) : ConsentResult

    /** The consent information could not be updated; [network] when for lack of a connection. */
    data class Failed(val network: Boolean) : ConsentResult
}

/** An ad ready to be shown, opaque outside the SDK adapter. */
internal interface LoadedAd

internal sealed interface LoadResult {
    data class Loaded(val ad: LoadedAd) : LoadResult

    data class Failed(val reason: LoadFailure, val code: Int) : LoadResult
}

internal enum class LoadFailure { NO_FILL, NETWORK, OTHER }

/** Callbacks of a shown ad; delivered on the main thread, in the order the SDK reports them. */
internal interface ShowEvents {
    fun onRewardEarned()

    fun onDismissed()

    fun onFailedToShow(code: Int)
}
