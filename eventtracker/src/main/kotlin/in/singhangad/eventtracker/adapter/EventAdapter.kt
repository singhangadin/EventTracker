package `in`.singhangad.eventtracker.adapter

import android.content.Context
import `in`.singhangad.eventtracker.TrackEvent
import `in`.singhangad.eventtracker.internal.EventLogger

/**
 * Contract for a destination that receives events. Implementations may deliver immediately
 * (in-process SDKs) or batch over the network.
 *
 * ## Implementation requirements
 * - Safe to invoke from a single background thread. The dispatcher serializes calls per adapter
 *   instance, so adapters do not need to be reentrant.
 * - Never throw to the caller. Wrap exceptions and return a [DeliveryOutcome] instead.
 * - Idempotent: the same event may be delivered more than once if the library cannot distinguish
 *   a successful delivery (e.g. timeout after the server received the request).
 *
 * @see in.singhangad.eventtracker.EventTrackerConfig.Builder.addAdapter
 * @since 1.0.0
 */
interface EventAdapter {

    /** Stable, unique identifier for this destination (e.g. `"firebase"`, `"backend"`). */
    val id: String

    /**
     * Called once during [in.singhangad.eventtracker.EventTracker.initialize]. Use this to
     * initialise any underlying SDK, open connections, or set up resources.
     *
     * @param context Application context. Never an Activity context.
     * @param logger The logger configured by the host app.
     */
    fun initialize(context: Context, logger: EventLogger)

    /**
     * Decide whether this adapter wants the given event.
     *
     * Adapters should check `event.destinations`: if it is non-null and does not contain [id],
     * return `false`. Additional filtering (by event name, build type, etc.) is also permitted.
     */
    fun accepts(event: TrackEvent): Boolean

    /**
     * Deliver a single event. Realtime adapters forward to their SDK here; batch adapters
     * typically return [DeliveryOutcome.Success] immediately (the event is already persisted).
     *
     * @return [DeliveryOutcome.Success] on success,
     *         [DeliveryOutcome.RetryableFailure] for transient errors (the dispatcher will retry),
     *         [DeliveryOutcome.PermanentFailure] for non-retryable errors (event goes to DLQ).
     */
    suspend fun deliver(event: TrackEvent): DeliveryOutcome

    /**
     * Forwarded user identification. Called when the host app calls
     * [in.singhangad.eventtracker.EventTracker.identify]. Default is a no-op.
     *
     * @param userId Stable user ID, or `null` to clear.
     * @param traits Optional user attributes.
     */
    suspend fun identify(userId: String?, traits: Map<String, Any?>) {}

    /**
     * Called when [in.singhangad.eventtracker.EventTracker.flush] is invoked.
     * Batch adapters should drain their queue here. Default is a no-op.
     */
    suspend fun flush(): DeliveryOutcome = DeliveryOutcome.Success

    /**
     * Called when the host opts out and local data is being cleared.
     * Implementations should clear any locally cached user state.
     */
    fun onOptOut() {}
}
