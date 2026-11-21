package app.recall.phone.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import app.recall.phone.core.MAX_VECTORS_PER_PERSON
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "people")
data class PersonEntity(
    @PrimaryKey val id: String,
    val name: String,
    val profileJson: String,
    val thumbnailPath: String?,
    val lastMetAt: String?,
    val createdAt: String,
    val isDemoBackup: Boolean = false,
)

@Entity(
    tableName = "face_vectors",
    foreignKeys = [
        ForeignKey(
            entity = PersonEntity::class,
            parentColumns = ["id"],
            childColumns = ["personId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("personId")],
)
class FaceVectorEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val personId: String,
    val vector: ByteArray,
)

/** Stops a capture being processed twice if deleting it from the glasses fails. */
@Entity(tableName = "processed_captures")
data class ProcessedCaptureEntity(
    @PrimaryKey val captureId: String,
    val personId: String,
)

@Dao
interface RecallDao {
    @Query("SELECT * FROM people ORDER BY COALESCE(lastMetAt, createdAt) DESC")
    fun observePeople(): Flow<List<PersonEntity>>

    @Query("SELECT * FROM people WHERE id = :id")
    suspend fun person(id: String): PersonEntity?

    @Upsert
    suspend fun upsertPerson(person: PersonEntity)

    @Query("DELETE FROM people WHERE id = :id")
    suspend fun deletePerson(id: String)

    @Query("SELECT * FROM face_vectors")
    suspend fun allVectors(): List<FaceVectorEntity>

    @Insert
    suspend fun insertVectors(vectors: List<FaceVectorEntity>)

    @Query(
        """DELETE FROM face_vectors WHERE personId = :personId AND id NOT IN
        (SELECT id FROM face_vectors WHERE personId = :personId ORDER BY id DESC LIMIT :keep)""",
    )
    suspend fun trimVectors(personId: String, keep: Int)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun markProcessed(capture: ProcessedCaptureEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM processed_captures WHERE captureId = :captureId)")
    suspend fun isProcessed(captureId: String): Boolean

    @Query("UPDATE people SET isDemoBackup = 0")
    suspend fun clearDemoBackup()

    @Query("UPDATE people SET isDemoBackup = 1 WHERE id = :id")
    suspend fun markDemoBackup(id: String)

    @Query("SELECT * FROM people WHERE isDemoBackup = 1 LIMIT 1")
    suspend fun demoBackup(): PersonEntity?

    @Transaction
    suspend fun setDemoBackup(id: String, enabled: Boolean) {
        clearDemoBackup()
        if (enabled) markDemoBackup(id)
    }

    @Transaction
    suspend fun saveCapture(
        person: PersonEntity,
        vectors: List<FaceVectorEntity>,
        captureId: String,
    ) {
        upsertPerson(person)
        if (vectors.isNotEmpty()) insertVectors(vectors)
        trimVectors(person.id, MAX_VECTORS_PER_PERSON)
        markProcessed(ProcessedCaptureEntity(captureId, person.id))
    }
}

@Database(
    entities = [PersonEntity::class, FaceVectorEntity::class, ProcessedCaptureEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class RecallDatabase : RoomDatabase() {
    abstract fun dao(): RecallDao

    companion object {
        fun create(context: Context): RecallDatabase =
            Room.databaseBuilder(context, RecallDatabase::class.java, "recall.db").build()
    }
}
