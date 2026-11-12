package fr.corsicajack.seekmind.core.database

import fr.corsicajack.seekmind.core.database.store.CaptureStore
import fr.corsicajack.seekmind.core.database.store.RoomCaptureStore
import fr.corsicajack.seekmind.core.database.store.RoomSearchStore
import fr.corsicajack.seekmind.core.database.store.RoomVectorStore
import fr.corsicajack.seekmind.core.database.store.SearchStore
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

    fun close() = room.close()
}
