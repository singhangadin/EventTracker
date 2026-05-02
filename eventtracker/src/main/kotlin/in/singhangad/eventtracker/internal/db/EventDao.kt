package `in`.singhangad.eventtracker.internal.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
internal interface EventDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: EventEntity)

    /**
     * Returns up to [limit] QUEUED events whose next_attempt_at is in the past, oldest first.
     */
    @Query(
        """
        SELECT * FROM events
        WHERE state = 'QUEUED' AND next_attempt_at <= :now
        ORDER BY created_at ASC
        LIMIT :limit
        """
    )
    suspend fun nextBatch(now: Long, limit: Int): List<EventEntity>

    @Query("UPDATE events SET state = 'SENDING' WHERE id IN (:ids)")
    suspend fun markSending(ids: List<String>)

    /** Reset SENDING rows back to QUEUED — called on startup to recover from a process kill mid-send. */
    @Query("UPDATE events SET state = 'QUEUED' WHERE state = 'SENDING'")
    suspend fun resetSendingToQueued()

    @Query("DELETE FROM events WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query(
        """
        UPDATE events
        SET state = 'QUEUED',
            attempt_count = attempt_count + 1,
            next_attempt_at = :nextAt,
            last_error = :err
        WHERE id IN (:ids)
        """
    )
    suspend fun rescheduleFailed(ids: List<String>, nextAt: Long, err: String)

    @Query("SELECT COUNT(*) FROM events")
    suspend fun queueDepth(): Long

    /** Drop the N oldest rows to enforce the maxLocalEvents cap. */
    @Query(
        "DELETE FROM events WHERE id IN (SELECT id FROM events ORDER BY created_at ASC LIMIT :n)"
    )
    suspend fun trimOldest(n: Int)

    @Query("SELECT COUNT(*) FROM events")
    suspend fun count(): Long
}
