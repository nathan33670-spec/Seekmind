package fr.corsicajack.seekmind.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import fr.corsicajack.seekmind.core.database.schema.AppRuleEntity
import fr.corsicajack.seekmind.core.database.schema.CaptureEntity
import fr.corsicajack.seekmind.core.database.schema.ChunkEntity
import fr.corsicajack.seekmind.core.database.schema.CreditLedgerEntity
import fr.corsicajack.seekmind.core.database.schema.EmbeddingEntity
import fr.corsicajack.seekmind.core.database.schema.LicenseEntity
import fr.corsicajack.seekmind.core.database.schema.MetaEntity
import fr.corsicajack.seekmind.core.database.schema.MetricDailyEntity
import fr.corsicajack.seekmind.core.database.schema.SearchHistoryEntity
import fr.corsicajack.seekmind.core.database.schema.SecureProbeEntity
import fr.corsicajack.seekmind.core.database.store.CaptureDao
import fr.corsicajack.seekmind.core.database.store.VectorDao

/**
 * The SeekMind database, schema version 1 (docs/specs/stockage.md §3). Exported schemas live in
 * core/database/schemas/; every new version adds a [Migration] to [MIGRATIONS] and a MigrationTestHelper
 * test (spec §7). Opened only through [DatabaseOpener]; other modules see a [DatabaseHandle].
 */
@Database(
    entities = [
        CaptureEntity::class,
        ChunkEntity::class,
        EmbeddingEntity::class,
        AppRuleEntity::class,
        SecureProbeEntity::class,
        CreditLedgerEntity::class,
        LicenseEntity::class,
        SearchHistoryEntity::class,
        MetricDailyEntity::class,
        MetaEntity::class,
    ],
    version = SeekMindDatabase.VERSION,
    exportSchema = true,
)
internal abstract class SeekMindDatabase : RoomDatabase() {
    abstract fun captureDao(): CaptureDao

    abstract fun vectorDao(): VectorDao

    companion object {
        const val VERSION = 1
        const val NAME = "seekmind.db"

        /** Migrations from each version to the next. fallbackToDestructiveMigration is forbidden. */
        val MIGRATIONS: Array<Migration> = emptyArray()
    }
}
