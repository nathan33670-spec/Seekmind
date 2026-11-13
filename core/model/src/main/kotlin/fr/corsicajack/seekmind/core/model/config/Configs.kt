package fr.corsicajack.seekmind.core.model.config

import fr.corsicajack.seekmind.core.model.AppFamily

// Tuning values of every subsystem (architecture §7). The defaults are those of the specs; each one is
// frozen by ConfigDefaultsTest, so that a change is deliberate. Tests inject reduced configurations;
// differences between variants go through these objects, never through conditional code.

/** docs/specs/capture.md §9. */
data class CaptureConfig(
    val quietMs: Long = 1_500,
    val maxWaitMs: Long = 8_000,
    val maxWaitCeilingMs: Long = 60_000,
    val minIntervalMs: Long = 1_000,
    val dedupStreakForBackoff: Int = 3,
    val maxNodes: Int = 1_500,
    val maxDepth: Int = 40,
    val softBudgetMs: Long = 150,
    val hardBudgetMs: Long = 400,
    val maxTextChars: Int = 12_000,
    val minTextChars: Int = 20,
    /** How long the last URL read stays reusable while the address bar is hidden (§5.2). */
    val urlMemoryMs: Long = 600_000,
)

/** docs/specs/confidentialite.md §6. */
data class PrivacyConfig(
    val redactionMark: String = "[•••]",
    val excludeGames: Boolean = true,
    val privateMarkers: List<String> = listOf("incognito", "private_browsing", "private_mode", "secret_mode"),
    val defaultEnabledFamilies: Set<AppFamily> = AppFamily.entries.toSet(),
)

/** docs/specs/ocr.md §8. */
data class OcrConfig(
    val minIntervalMs: Long = 5_000,
    val maxPerMinute: Int = 6,
    val maxPerDay: Int = 400,
    val screenshotMinIntervalMs: Long = 1_000,
    val maxImageSidePx: Int = 1_600,
    val lowTextChars: Int = 80,
    val lowCoverage: Float = 0.15f,
    val lowCoverageMaxChars: Int = 600,
    val minLineConfidence: Float = 0.45f,
    val ocrMemoSize: Int = 20,
    val blackSampleGrid: Int = 24,
    val blackLumaMax: Int = 8,
    val blackRatio: Float = 0.98f,
    val probeCacheTtlMs: Long = 24 * 3_600_000L,
    val securePositiveTtlMs: Long = 600_000L,
    val lowBatteryPercent: Int = 15,
    /** Filled at injection: browsers and messaging apps with ephemeral content. */
    val probeAlwaysPackages: Set<String> = emptySet(),
)

/** docs/specs/indexation.md §8. */
data class IndexConfig(
    val maxTextChars: Int = 12_000,
    val dedupMaxHamming: Int = 9,
    val dedupWindow: Int = 8,
    val dedupRecentMs: Long = 6 * 3_600_000L,
    val dedupSpanMs: Long = 24 * 3_600_000L,
    val chunkMaxChars: Int = 800,
    val maxChunksPerCapture: Int = 4,
    val embedBatchSize: Int = 32,
    val workerBudgetMs: Long = 9 * 60_000L,
    val interBatchPauseMs: Long = 100,
    val maxAttempts: Int = 3,
)

/** docs/specs/embeddings.md §9. */
data class EmbeddingConfig(
    val maxPassageTokens: Int = 256,
    val maxQueryTokens: Int = 64,
    val subBatchSize: Int = 8,
    val int8Scale: Float = 400f,
    val idleCloseMs: Long = 60_000,
    val threadsDefault: Int = 2,
    val threadsCharging: Int = 4,
    val defaultModel: String = "fr",
)

/** docs/specs/recherche.md §10. */
data class SearchConfig(
    val lexicalLimit: Int = 100,
    val binaryTopK: Int = 200,
    val semanticTopK: Int = 100,
    val semanticFloor: Float = 0.78f,
    val rrfK: Int = 60,
    val recencyBoost: Double = 0.15,
    val recencyHalfLifeDays: Double = 30.0,
    val collapseMaxHamming: Int = 6,
    val indexPageSize: Int = 5_000,
    val cacheSize: Int = 5,
    val cacheTtlMs: Long = 600_000,
)

/** docs/specs/monetisation.md §7. The `offline` variant injects `dailyFreeCredits = 5`. */
data class MonetizationConfig(
    val welcomeCredits: Int = 10,
    val creditsPerReward: Int = 5,
    val courtesyCredits: Int = 1,
    val courtesyPerDay: Int = 3,
    val dailyFreeCredits: Int = 0,
    val maxBalance: Int = 100,
    val maxRewardsPerDay: Int = 20,
    val freeRepeatWindowMs: Long = 600_000L,
    val liveSearchDebounceMs: Long = 300,
    val adLoadTimeoutMs: Long = 15_000,
)

/** docs/specs/stockage.md §9. */
data class StorageConfig(
    /** null = unlimited. */
    val retentionDays: Int? = 90,
    val storageBudgetBytes: Long = 500L * 1024 * 1024,
    val budgetEvictionBatch: Int = 500,
    val vacuumFreeRatio: Double = 0.25,
    val historyRetentionDays: Int = 90,
    val metricsRetentionDays: Int = 30,
)
