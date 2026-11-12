package fr.corsicajack.seekmind.core.model.config

import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.core.model.AppFamily
import org.junit.Test

/**
 * Freezes the defaults of every *Config (ticket M1-01): each expected value is copied from its spec.
 * A failure here means a tuning value changed; change the spec and this test together, deliberately.
 */
class ConfigDefaultsTest {
    @Test
    fun `capture, docs-specs-capture §9`() {
        assertThat(CaptureConfig()).isEqualTo(
            CaptureConfig(
                quietMs = 1_500, maxWaitMs = 8_000, maxWaitCeilingMs = 60_000, minIntervalMs = 1_000,
                dedupStreakForBackoff = 3, maxNodes = 1_500, maxDepth = 40, softBudgetMs = 150,
                hardBudgetMs = 400, maxTextChars = 12_000, minTextChars = 20, urlMemoryMs = 600_000,
            ),
        )
    }

    @Test
    fun `privacy, docs-specs-confidentialite §6`() {
        assertThat(PrivacyConfig()).isEqualTo(
            PrivacyConfig(
                redactionMark = "[•••]",
                excludeGames = true,
                privateMarkers = listOf("incognito", "private_browsing", "private_mode", "secret_mode"),
                defaultEnabledFamilies = setOf(
                    AppFamily.MESSAGING,
                    AppFamily.BROWSERS,
                    AppFamily.SOCIAL_MEDIA,
                    AppFamily.OTHER,
                ),
            ),
        )
    }

    @Test
    fun `ocr, docs-specs-ocr §8`() {
        assertThat(OcrConfig()).isEqualTo(
            OcrConfig(
                minIntervalMs = 5_000, maxPerMinute = 6, maxPerDay = 400, screenshotMinIntervalMs = 1_000,
                maxImageSidePx = 1_600, lowTextChars = 80, lowCoverage = 0.15f, lowCoverageMaxChars = 600,
                minLineConfidence = 0.45f, ocrMemoSize = 20, blackSampleGrid = 24, blackLumaMax = 8,
                blackRatio = 0.98f, probeCacheTtlMs = 86_400_000L, securePositiveTtlMs = 600_000L,
                lowBatteryPercent = 15, probeAlwaysPackages = emptySet(),
            ),
        )
    }

    @Test
    fun `index, docs-specs-indexation §8`() {
        assertThat(IndexConfig()).isEqualTo(
            IndexConfig(
                maxTextChars = 12_000, dedupMaxHamming = 9, dedupWindow = 8, dedupRecentMs = 21_600_000L,
                dedupSpanMs = 86_400_000L, chunkMaxChars = 800, maxChunksPerCapture = 4, embedBatchSize = 32,
                workerBudgetMs = 540_000L, interBatchPauseMs = 100, maxAttempts = 3,
            ),
        )
    }

    @Test
    fun `embedding, docs-specs-embeddings §9`() {
        assertThat(EmbeddingConfig()).isEqualTo(
            EmbeddingConfig(
                maxPassageTokens = 256,
                maxQueryTokens = 64,
                subBatchSize = 8,
                int8Scale = 400f,
                idleCloseMs = 60_000,
                threadsDefault = 2,
                threadsCharging = 4,
                defaultModel = "fr",
            ),
        )
    }

    @Test
    fun `search, docs-specs-recherche §10`() {
        assertThat(SearchConfig()).isEqualTo(
            SearchConfig(
                lexicalLimit = 100, binaryTopK = 200, semanticTopK = 100, semanticFloor = 0.78f, rrfK = 60,
                recencyBoost = 0.15, recencyHalfLifeDays = 30.0, collapseMaxHamming = 6, indexPageSize = 5_000,
                cacheSize = 5, cacheTtlMs = 600_000,
            ),
        )
    }

    @Test
    fun `monetization, docs-specs-monetisation §7`() {
        assertThat(MonetizationConfig()).isEqualTo(
            MonetizationConfig(
                welcomeCredits = 10, creditsPerReward = 5, courtesyCredits = 1, courtesyPerDay = 3,
                dailyFreeCredits = 0, maxBalance = 100, maxRewardsPerDay = 20, freeRepeatWindowMs = 600_000L,
                liveSearchDebounceMs = 300, adLoadTimeoutMs = 15_000,
            ),
        )
    }

    @Test
    fun `storage, docs-specs-stockage §9`() {
        assertThat(StorageConfig()).isEqualTo(
            StorageConfig(
                retentionDays = 90,
                storageBudgetBytes = 524_288_000L,
                budgetEvictionBatch = 500,
                vacuumFreeRatio = 0.25,
                historyRetentionDays = 90,
                metricsRetentionDays = 30,
            ),
        )
    }
}
