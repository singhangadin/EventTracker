package `in`.singhangad.eventtracker.adapter

/**
 * The result of a single delivery attempt by an [EventAdapter].
 *
 * @since 1.0.0
 */
sealed class DeliveryOutcome {
    /** The event was accepted by the destination. */
    object Success : DeliveryOutcome()

    /**
     * A transient error occurred. The dispatcher will retry on its own schedule.
     *
     * @property cause The underlying error.
     * @property retryAfterMs If non-null, the minimum delay before the next attempt (e.g. from Retry-After).
     */
    data class RetryableFailure(
        val cause: Throwable,
        val retryAfterMs: Long? = null,
    ) : DeliveryOutcome()

    /**
     * A non-recoverable error. The event will be moved directly to the dead-letter queue.
     *
     * @property cause The underlying error.
     */
    data class PermanentFailure(val cause: Throwable) : DeliveryOutcome()
}
