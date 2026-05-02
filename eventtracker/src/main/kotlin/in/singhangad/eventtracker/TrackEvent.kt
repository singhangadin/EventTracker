package `in`.singhangad.eventtracker

/**
 * A single event flowing through the pipeline.
 *
 * @property id Locally unique UUID generated at track-time. Used as the idempotency key on the backend.
 * @property name Validated event name.
 * @property properties Event payload, after sanitization.
 * @property userId Identifier set by [EventTracker.identify], if any.
 * @property sessionId Identifier of the session this event belongs to.
 * @property clientTimestamp Wall-clock time of the track call (UTC ms).
 * @property clientUptimeMs Monotonic uptime at track-time, used to correct wall-clock skew on the backend.
 * @property schemaVersion Format version. The current code emits 1.
 * @property destinations Whitelist passed by the caller, or null for "all".
 * @property attemptCount Number of delivery attempts so far. Zero on creation.
 * @since 1.0.0
 */
data class TrackEvent(
    val id: String,
    val name: String,
    val properties: Map<String, Any?>,
    val userId: String?,
    val sessionId: String,
    val clientTimestamp: Long,
    val clientUptimeMs: Long,
    val schemaVersion: Int = 1,
    val destinations: Set<String>? = null,
    val attemptCount: Int = 0,
)
