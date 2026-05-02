package `in`.singhangad.eventtracker.internal.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
internal interface DLQDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: DeadLetterEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<DeadLetterEntity>)

    @Query("SELECT COUNT(*) FROM dead_letter_events")
    suspend fun count(): Long

    /** Returns up to [limit] DLQ rows, oldest first. */
    @Query(
        "SELECT * FROM dead_letter_events ORDER BY created_at ASC LIMIT :limit"
    )
    suspend fun peek(limit: Int): List<DeadLetterEntity>

    @Query("DELETE FROM dead_letter_events WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("DELETE FROM dead_letter_events")
    suspend fun deleteAll()

    /** Drop the N oldest DLQ entries to stay within the cap. */
    @Query(
        "DELETE FROM dead_letter_events WHERE id IN (SELECT id FROM dead_letter_events ORDER BY created_at ASC LIMIT :n)"
    )
    suspend fun trimOldest(n: Int)
}
