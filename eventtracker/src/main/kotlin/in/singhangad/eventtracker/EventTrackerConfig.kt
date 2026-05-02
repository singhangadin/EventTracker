package `in`.singhangad.eventtracker

import `in`.singhangad.eventtracker.adapter.EventAdapter
import `in`.singhangad.eventtracker.internal.EventLogger
import `in`.singhangad.eventtracker.internal.NoOpLogger

/**
 * Immutable configuration for [EventTracker]. Use [Builder] to construct.
 *
 * @property adapters Ordered list of destinations. Order matters only for logging; runtime
 *   delivery is parallelised where safe.
 * @property batchSize Maximum number of events in a single backend batch. Range: 1–1000.
 * @property batchIntervalMs Maximum time, in milliseconds, an event may sit in the queue before
 *   forcing a flush. Default: 30,000 ms (30 s).
 * @property maxRetries Maximum HTTP retry attempts before events are moved to the dead-letter
 *   queue. Default: 8.
 * @property maxLocalEvents Hard cap on the events table. Oldest rows are dropped on overflow.
 *   Default: 10,000.
 * @property samplingRates Per-event-name sampling rates in 0.0..1.0. Names not present default
 *   to 1.0 (always tracked).
 * @property encryptAtRest If true, the database is opened with SQLCipher (requires additional
 *   setup in the host app). Default: false.
 * @property logger SLF4J-style logger for internal diagnostics. Default: no-op.
 * @since 1.0.0
 */
class EventTrackerConfig private constructor(
    val adapters: List<EventAdapter>,
    val batchSize: Int,
    val batchIntervalMs: Long,
    val maxRetries: Int,
    val maxLocalEvents: Int,
    val samplingRates: Map<String, Double>,
    val encryptAtRest: Boolean,
    val logger: EventLogger,
) {
    companion object {
        const val DEFAULT_BATCH_SIZE = 50
        const val DEFAULT_BATCH_INTERVAL_MS = 30_000L
        const val DEFAULT_MAX_RETRIES = 8
        const val DEFAULT_MAX_LOCAL_EVENTS = 10_000
    }

    /**
     * Fluent builder for [EventTrackerConfig].
     *
     * ```
     * EventTrackerConfig.Builder()
     *     .addAdapter(BackendBatchAdapter("https://api.example.com/v1/events"))
     *     .addAdapter(FirebaseAdapter()) // requires :eventtracker-adapter-firebase
     *     .batchSize(75)
     *     .maxRetries(10)
     *     .build()
     * ```
     */
    class Builder {
        private val adapters = mutableListOf<EventAdapter>()
        private var batchSize = DEFAULT_BATCH_SIZE
        private var batchIntervalMs = DEFAULT_BATCH_INTERVAL_MS
        private var maxRetries = DEFAULT_MAX_RETRIES
        private var maxLocalEvents = DEFAULT_MAX_LOCAL_EVENTS
        private val samplingRates = mutableMapOf<String, Double>()
        private var encryptAtRest = false
        private var logger: EventLogger = NoOpLogger

        /**
         * Register a destination adapter. Adapters are called in registration order.
         * At least one adapter is required by [build].
         */
        fun addAdapter(adapter: EventAdapter): Builder = apply { adapters.add(adapter) }

        /**
         * Maximum events per backend batch. Clamped to 1..1000.
         * @param size Event count. Default 50.
         */
        fun batchSize(size: Int): Builder = apply {
            batchSize = size.coerceIn(1, 1000)
        }

        /**
         * Maximum age of a queued event before an automatic flush is triggered.
         * @param ms Duration in milliseconds. Default 30,000.
         */
        fun batchIntervalMs(ms: Long): Builder = apply {
            batchIntervalMs = maxOf(1_000L, ms)
        }

        /**
         * Maximum HTTP delivery attempts before events are dead-lettered.
         * @param n Retry count. Default 8.
         */
        fun maxRetries(n: Int): Builder = apply {
            maxRetries = maxOf(1, n)
        }

        /**
         * Hard cap on the local events table. Excess oldest rows are dropped.
         * @param n Row limit. Default 10,000.
         */
        fun maxLocalEvents(n: Int): Builder = apply {
            maxLocalEvents = maxOf(100, n)
        }

        /**
         * Set a per-event-name sampling rate.
         *
         * @param eventName The event name to sample (exact match).
         * @param rate Fraction of events to keep: 0.0 = drop all, 1.0 = keep all (default).
         */
        fun samplingRate(eventName: String, rate: Double): Builder = apply {
            samplingRates[eventName] = rate.coerceIn(0.0, 1.0)
        }

        /**
         * Enable SQLCipher encryption for the local database. Requires the host app to include
         * the `net.zetetic:android-database-sqlcipher` dependency.
         */
        fun encryptAtRest(enabled: Boolean): Builder = apply {
            encryptAtRest = enabled
        }

        /** Provide a custom logger. Defaults to a no-op logger in production. */
        fun logger(logger: EventLogger): Builder = apply {
            this.logger = logger
        }

        /**
         * Build the immutable [EventTrackerConfig].
         * @throws IllegalStateException if no adapters have been added.
         */
        fun build(): EventTrackerConfig {
            check(adapters.isNotEmpty()) {
                "EventTrackerConfig requires at least one adapter. Call addAdapter() before build()."
            }
            return EventTrackerConfig(
                adapters = adapters.toList(),
                batchSize = batchSize,
                batchIntervalMs = batchIntervalMs,
                maxRetries = maxRetries,
                maxLocalEvents = maxLocalEvents,
                samplingRates = samplingRates.toMap(),
                encryptAtRest = encryptAtRest,
                logger = logger,
            )
        }
    }
}
