package `in`.singhangad.eventtracker.internal.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "dead_letter_events")
internal data class DeadLetterEntity(
    @PrimaryKey
    val id: String,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "payload_json")
    val payloadJson: String,

    @ColumnInfo(name = "attempt_count")
    val attemptCount: Int,

    @ColumnInfo(name = "first_failure_at")
    val firstFailureAt: Long,

    @ColumnInfo(name = "last_failure_at")
    val lastFailureAt: Long,

    @ColumnInfo(name = "last_error")
    val lastError: String,

    @ColumnInfo(name = "http_status")
    val httpStatus: Int?,

    @ColumnInfo(name = "schema_version")
    val schemaVersion: Int,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,
)
