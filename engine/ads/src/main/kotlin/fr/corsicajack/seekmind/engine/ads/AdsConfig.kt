package fr.corsicajack.seekmind.engine.ads

/**
 * Settings of the rewarded ads (docs/specs/monetisation.md §3.3). [rewardedUnitId] is Google's public
 * test unit in `debug`; the real one comes from the build property `seekmind.admob.rewardedUnitId`.
 */
data class AdsConfig(
    val rewardedUnitId: String,
    /** Longest wait for an ad to load. */
    val loadTimeoutMs: Long = 15_000,
    /** Ceiling of the content rating of the ads shown. */
    val maxContentRating: ContentRating = ContentRating.TEEN,
) {
    enum class ContentRating { GENERAL, PARENTAL_GUIDANCE, TEEN }

    companion object {
        /** Google's public test unit for rewarded ads. */
        const val TEST_REWARDED_UNIT_ID = "ca-app-pub-3940256099942544/5224354917"
    }
}
