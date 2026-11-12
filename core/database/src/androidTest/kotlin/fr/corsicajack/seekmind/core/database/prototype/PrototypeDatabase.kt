package fr.corsicajack.seekmind.core.database.prototype

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase

/** The `capture` table of docs/specs/stockage.md §3.1, reproduced column for column. */
@Entity(
    tableName = "capture",
    indices = [
        Index(name = "idx_capture_last_seen", value = ["last_seen_at"]),
        Index(name = "idx_capture_pkg_seen", value = ["package_name", "last_seen_at"]),
    ],
)
data class CaptureEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "first_seen_at") val firstSeenAt: Long,
    @ColumnInfo(name = "last_seen_at") val lastSeenAt: Long,
    @ColumnInfo(name = "seen_count", defaultValue = "1") val seenCount: Int = 1,
    @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "activity_name") val activityName: String?,
    @ColumnInfo(name = "window_title") val windowTitle: String?,
    val url: String?,
    val text: String,
    @ColumnInfo(name = "text_source") val textSource: Int,
    val simhash: Long,
    @ColumnInfo(name = "char_count") val charCount: Int,
)

@Dao
interface CaptureDao {
    @Insert
    fun insert(capture: CaptureEntity): Long

    @Query("UPDATE capture SET last_seen_at = :seenAt, seen_count = seen_count + 1 WHERE id = :id")
    fun touch(id: Long, seenAt: Long)

    @Query("UPDATE capture SET text = :text, char_count = length(:text) WHERE id = :id")
    fun replaceText(id: Long, text: String)

    @Query("DELETE FROM capture WHERE id = :id")
    fun delete(id: Long)

    @Query("SELECT count(*) FROM capture")
    fun count(): Int
}

@Database(entities = [CaptureEntity::class], version = 1, exportSchema = false)
abstract class PrototypeDatabase : RoomDatabase() {
    abstract fun captures(): CaptureDao
}
