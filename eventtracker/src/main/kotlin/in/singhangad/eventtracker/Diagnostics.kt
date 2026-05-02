package `in`.singhangad.eventtracker

/**
 * Read-only snapshot of internal counters. Values are monotonically increasing within a process
 * lifetime and reset to zero on process start.
 *
 * @property tracked Total events accepted by [EventTracker.track].
 * @property dropped Events rejected by validation, sampling, or opt-out.
 * @property persisted Events written to the local database.
 * @property delivered Events successfully delivered to all required adapters.
 * @property retrying Events currently waiting for a retry attempt.
 * @property deadLettered Events moved to the dead-letter queue.
 * @property queueDepth Current number of un-delivered rows in events table.
 * @since 1.0.0
 */
data class Diagnostics(
    val tracked: Long,
    val dropped: Long,
    val persisted: Long,
    val delivered: Long,
    val retrying: Long,
    val deadLettered: Long,
    val queueDepth: Long,
)
