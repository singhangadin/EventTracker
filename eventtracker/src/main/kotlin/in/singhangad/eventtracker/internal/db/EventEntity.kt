package `in`.singhangad.eventtracker.internal.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** State values for [EventEntity.state]. */
internal object EventState {
    const val QUEUED = "QUEUED"
    const val SENDING = "SENDING"
}

@Entity(
    tableName = "events",
    indices = [
        Index(value = ["state", "next_attempt_at"]),
        Index(value = ["created_at"]),
    ]
)
internal data class EventEntity(
    @PrimaryKey
    val id: String,

    @ColumnInfo(name = "name")
    val name: String,

    /** JSON-serialised properties map. */
    @ColumnInfo(name = "payload_json")
    val payloadJson: String,

    @ColumnInfo(name = "user_id")
    val userId: String?,

    @ColumnInfo(name = "session_id")
    val sessionId: String,

    @ColumnInfo(name = "client_ts")
    val clientTs: Long,

    @ColumnInfo(name = "client_uptime_ms")
    val clientUptimeMs: Long,

    @ColumnInfo(name = "schema_version")
    val schemaVersion: Int,

    /** Comma-separated destination IDs, or null for "all". */
    @ColumnInfo(name = "destinations_csv")
    val destinationsCsv: String?,

    /** One of [EventState.QUEUED] or [EventState.SENDING]. */
    @ColumnInfo(name = "state")
    val state: String,

    @ColumnInfo(name = "attempt_count")
    val attemptCount: Int,

    /** Epoch-ms timestamp before which this row must not be retried. */
    @ColumnInfo(name = "next_attempt_at")
    val nextAttemptAt: Long,

    @ColumnInfo(name = "last_error")
    val lastError: String?,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,
)
