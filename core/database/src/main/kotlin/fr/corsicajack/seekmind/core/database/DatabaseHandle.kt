package fr.corsicajack.seekmind.core.database

import fr.corsicajack.seekmind.core.database.store.AppRuleStore
import fr.corsicajack.seekmind.core.database.store.CaptureStore
import fr.corsicajack.seekmind.core.database.store.MetricsStore
import fr.corsicajack.seekmind.core.database.store.MonetizationStore
import fr.corsicajack.seekmind.core.database.store.RoomAppRuleStore
import fr.corsicajack.seekmind.core.database.store.RoomCaptureStore
import fr.corsicajack.seekmind.core.database.store.RoomMetricsStore
import fr.corsicajack.seekmind.core.database.store.RoomMonetizationStore
import fr.corsicajack.seekmind.core.database.store.RoomSearchHistoryStore
import fr.corsicajack.seekmind.core.database.store.RoomSearchStore
import fr.corsicajack.seekmind.core.database.store.RoomSecureProbeStore
import fr.corsicajack.seekmind.core.database.store.RoomVectorStore
import fr.corsicajack.seekmind.core.database.store.SearchHistoryStore
import fr.corsicajack.seekmind.core.database.store.SearchStore
import fr.corsicajack.seekmind.core.database.store.SecureProbeStore
import fr.corsicajack.seekmind.core.database.store.VectorStore
import fr.corsicajack.seekmind.core.model.config.IndexConfig

/**
 * The open database, as the other modules see it: stores only, neither Room nor SQL
 * (docs/specs/stockage.md §4). One instance per process.
 */
class DatabaseHandle internal constructor(
    internal val room: SeekMindDatabase,
    indexConfig: IndexConfig = IndexConfig(),
) {
    val captures: CaptureStore = RoomCaptureStore(room.captureDao())
    val search: SearchStore = RoomSearchStore(room.captureDao())
    val vectors: VectorStore = RoomVectorStore(room.vectorDao(), indexConfig)
    val appRules: AppRuleStore = RoomAppRuleStore(room.sideDao())
    val secureProbes: SecureProbeStore = RoomSecureProbeStore(room.sideDao())
    val monetization: MonetizationStore = RoomMonetizationStore(room.sideDao())
    val searchHistory: SearchHistoryStore = RoomSearchHistoryStore(room.sideDao())
    val metrics: MetricsStore = RoomMetricsStore(room.sideDao())

    fun close() = room.close()
}
