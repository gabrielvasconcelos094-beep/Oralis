package app.oralis.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** Lifecycle of a library item. Only READY items have a playable, validated MP3. */
enum class AudioStatus { QUEUED, GENERATING, READY, FAILED, CANCELLED, INTERRUPTED }

@Entity(tableName = "audios")
data class AudioEntity(
    @PrimaryKey val id: String,
    val title: String,
    val languageTag: String,
    val voiceId: String,
    val voiceName: String,
    val speakingRate: Float,
    val presetId: String,
    val bitrateKbps: Int,
    val createdAt: Long,
    val status: AudioStatus,
    /** Original text, stored verbatim in app-private storage. */
    val textPath: String,
    val charCount: Int,
    val wordCount: Int,
    val estimatedDurationMs: Long,
    /** Internal MP3 (app-private). Null until READY. */
    val filePath: String? = null,
    val fileSizeBytes: Long = 0,
    val durationMs: Long = 0,
    val favorite: Boolean = false,
    val playbackPositionMs: Long = 0,
    val lastPlayedAt: Long? = null,
    val segmentsTotal: Int = 0,
    val segmentsDone: Int = 0,
    val generationStartedAt: Long? = null,
    val generationFinishedAt: Long? = null,
    val errorKind: String? = null,
    val errorMessage: String? = null,
    val lastExportUri: String? = null,
) {
    val isPlayable: Boolean get() = status == AudioStatus.READY && filePath != null
    val isActive: Boolean get() = status == AudioStatus.QUEUED || status == AudioStatus.GENERATING
}

@Dao
interface AudioDao {
    @Query("SELECT * FROM audios ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<AudioEntity>>

    @Query("SELECT * FROM audios WHERE id = :id")
    fun observe(id: String): Flow<AudioEntity?>

    @Query("SELECT * FROM audios WHERE id = :id")
    suspend fun get(id: String): AudioEntity?

    @Query("SELECT * FROM audios WHERE status IN ('QUEUED','GENERATING')")
    suspend fun active(): List<AudioEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(audio: AudioEntity)

    @Update
    suspend fun update(audio: AudioEntity)

    @Query("UPDATE audios SET status = :status, errorKind = :errorKind, errorMessage = :errorMessage WHERE id = :id")
    suspend fun setStatus(id: String, status: AudioStatus, errorKind: String? = null, errorMessage: String? = null)

    @Query("UPDATE audios SET segmentsDone = :done, segmentsTotal = :total WHERE id = :id")
    suspend fun setProgress(id: String, done: Int, total: Int)

    @Query("UPDATE audios SET title = :title WHERE id = :id")
    suspend fun rename(id: String, title: String)

    @Query("UPDATE audios SET favorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: String, favorite: Boolean)

    @Query("UPDATE audios SET playbackPositionMs = :positionMs, lastPlayedAt = :at WHERE id = :id")
    suspend fun savePosition(id: String, positionMs: Long, at: Long)

    @Query("UPDATE audios SET lastExportUri = :uri WHERE id = :id")
    suspend fun setExportUri(id: String, uri: String)

    @Query("DELETE FROM audios WHERE id = :id")
    suspend fun delete(id: String)
}

@Database(entities = [AudioEntity::class], version = 1, exportSchema = true)
abstract class OralisDatabase : RoomDatabase() {
    abstract fun audioDao(): AudioDao

    companion object {
        fun build(context: Context): OralisDatabase =
            Room.databaseBuilder(context, OralisDatabase::class.java, "oralis.db").build()
    }
}
