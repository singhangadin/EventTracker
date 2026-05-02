package `in`.singhangad.eventtracker

import android.content.Context
import `in`.singhangad.eventtracker.adapter.BackendBatchAdapter
import `in`.singhangad.eventtracker.internal.DiagnosticsCounters
import `in`.singhangad.eventtracker.internal.EventDispatcher
import `in`.singhangad.eventtracker.internal.FlushScheduler
import `in`.singhangad.eventtracker.internal.OptOutGuard
import `in`.singhangad.eventtracker.internal.SamplingFilter
import `in`.singhangad.eventtracker.internal.SessionManager
import `in`.singhangad.eventtracker.internal.db.EventDatabase
import `in`.singhangad.eventtracker.internal.db.EventEntity
import `in`.singhangad.eventtracker.internal.db.EventState
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * EventTracker is the single entry point for the EventTracker Android library.
 *
 * The library captures events in the host app, persists them to a local database, and dispatches
 * them to one or more configured [in.singhangad.eventtracker.adapter.EventAdapter] destinations.
 * Network destinations use batched delivery with retry and dead-letter handling.
 *
 * Initialization must happen exactly once per process, typically in `Application.onCreate`.
 * After initialization, all calls are safe from any thread.
 *
 * ```kotlin
 * EventTracker.initialize(
 *     context = applicationContext,
 *     config = EventTrackerConfig.Builder()
 *         .addAdapter(BackendBatchAdapter(endpoint = "https://api.example.com/v1/events"))
 *         .addAdapter(FirebaseAdapter()) // requires :eventtracker-adapter-firebase
 *         .batchSize(50)
 *         .batchIntervalMs(30_000)
 *         .maxRetries(8)
 *         .build()
 * )
 *
 * EventTracker.track("checkout_started", mapOf("cart_size" to 3))
 * ```
 *
 * @see EventTrackerConfig
 * @see in.singhangad.eventtracker.adapter.EventAdapter
 * @since 1.0.0
 */
object EventTracker {

    @Volatile
    internal var isInitialized: Boolean = false
        private set

    private lateinit var dispatcher: EventDispatcher
    private lateinit var flushScheduler: FlushScheduler
    private lateinit var optOutGuard: OptOutGuard
    private lateinit var appContext: Context
    private val counters = DiagnosticsCounters()

    // ---- Initialization ---------------------------------------------------------------------

    /**
     * Initialize the library. Safe to call multiple times; subsequent calls are no-ops and log
     * a warning. Must be called before any [track] or [identify] call.
     *
     * @param context Application context. The library retains only the application context,
     *   never an activity context.
     * @param config Immutable configuration produced by [EventTrackerConfig.Builder].
     * @throws IllegalStateException if config has zero adapters.
     */
    fun initialize(context: Context, config: EventTrackerConfig) {
        if (isInitialized) {
            config.logger.warn("ET/Tracker", "EventTracker.initialize() called more than once — ignoring.")
            return
        }
        synchronized(this) {
            if (isInitialized) return

            appContext = context.applicationContext

            optOutGuard = OptOutGuard(appContext)
            val sessionManager = SessionManager(appContext)

            // Inject config-level batchSize / maxRetries into BackendBatchAdapter before initialize()
            for (adapter in config.adapters) {
                if (adapter is BackendBatchAdapter) {
                    adapter.configure(config.batchSize, config.maxRetries)
                }
                adapter.initialize(appContext, config.logger)
            }

            dispatcher = EventDispatcher(
                context = appContext,
                adapters = config.adapters,
                optOutGuard = optOutGuard,
                samplingFilter = SamplingFilter(config.samplingRates),
                sessionManager = sessionManager,
                counters = counters,
                maxLocalEvents = config.maxLocalEvents,
                logger = config.logger,
            )

            flushScheduler = FlushScheduler(appContext, config.batchIntervalMs, config.logger)
            flushScheduler.start()

            // Recover rows stuck in SENDING state from a prior process kill
            dispatcher.scope.launch { dispatcher.recoverStuckSendingRows() }

            isInitialized = true
            config.logger.info("ET/Tracker", "EventTracker initialized — ${config.adapters.size} adapter(s)")
        }
    }

    // ---- Public API -------------------------------------------------------------------------

    /**
     * Track an event. Returns immediately; persistence and delivery happen on a background
     * dispatcher.
     *
     * @param name Event name. Must be 1–128 chars, alphanumeric + underscore. Names outside this
     *   pattern are rejected and a diagnostic counter is incremented.
     * @param properties Event properties. Values must be JSON-serialisable primitives, lists, or
     *   nested maps. Non-serialisable values are coerced to their `toString()` representation.
     * @param destinations Optional whitelist of destination IDs (e.g. `"backend"`, `"firebase"`).
     *   When `null`, all configured adapters that accept the event will receive it.
     */
    fun track(
        name: String,
        properties: Map<String, Any?> = emptyMap(),
        destinations: Set<String>? = null,
    ) {
        checkInitialized()
        try {
            dispatcher.track(name, properties, destinations)
        } catch (t: Throwable) {
            counters.dropped.incrementAndGet()
        }
    }

    /**
     * Associate a stable user identifier and optional user traits with the current session.
     * Subsequent events will carry this user id until [reset] is called.
     *
     * @param userId Stable user identifier. Pass `null` to clear.
     * @param traits Optional user attributes (email, plan, etc.).
     */
    fun identify(userId: String?, traits: Map<String, Any?> = emptyMap()) {
        checkInitialized()
        try { dispatcher.identify(userId, traits) } catch (_: Throwable) { }
    }

    /**
     * Forget the current user and start a new anonymous session. Does not clear queued events;
     * those continue to be delivered with the user id they were tracked under.
     */
    fun reset() {
        checkInitialized()
        try { dispatcher.reset() } catch (_: Throwable) { }
    }

    /**
     * Force an immediate flush of all queued backend events, bypassing batch-size and
     * batch-interval triggers. The returned [Job] completes when the flush attempt finishes.
     */
    fun flush(): Job {
        checkInitialized()
        return dispatcher.flush()
    }

    /**
     * Set the opt-out flag. When `true`, all subsequent [track] and [identify] calls are dropped
     * silently. Already-persisted events are NOT deleted unless [wipeLocalData] is also called.
     */
    fun setOptOut(optedOut: Boolean) {
        checkInitialized()
        optOutGuard.setOptOut(optedOut)
    }

    /**
     * Delete all locally persisted events, including the dead-letter queue. Intended for GDPR /
     * opt-out compliance and QA reset. Does not affect events already delivered to destinations.
     */
    fun wipeLocalData(): Job {
        checkInitialized()
        return dispatcher.wipeLocalData()
    }

    /**
     * Snapshot of internal counters for observability. Values are monotonically increasing within
     * a process lifetime and reset to zero on process start.
     */
    fun diagnostics(): Diagnostics {
        checkInitialized()
        return counters.snapshot()
    }

    // ---- Dead-letter queue API --------------------------------------------------------------

    /**
     * Number of events currently in the dead-letter queue.
     */
    suspend fun deadLetterSize(): Long {
        checkInitialized()
        return withContext(Dispatchers.IO) {
            EventDatabase.get(appContext).dlqDao().count()
        }
    }

    /**
     * Move up to [limit] DLQ rows back into the live queue, with `attempt_count` reset to zero.
     * Useful after a backend fix.
     *
     * @return The number of events actually re-queued.
     */
    suspend fun replayDeadLetters(limit: Int = 500): Int {
        checkInitialized()
        return withContext(Dispatchers.IO) {
            val db = EventDatabase.get(appContext)
            val dlqDao = db.dlqDao()
            val eventDao = db.eventDao()

            val rows = dlqDao.peek(limit)
            if (rows.isEmpty()) return@withContext 0

            db.withTransaction {
                dlqDao.deleteByIds(rows.map { it.id })
                for (dlq in rows) {
                    eventDao.insert(
                        EventEntity(
                            id = dlq.id,
                            name = dlq.name,
                            payloadJson = dlq.payloadJson,
                            userId = null,
                            sessionId = "",
                            clientTs = dlq.createdAt,
                            clientUptimeMs = 0L,
                            schemaVersion = dlq.schemaVersion,
                            destinationsCsv = null,
                            state = EventState.QUEUED,
                            attemptCount = 0,
                            nextAttemptAt = 0L,
                            lastError = null,
                            createdAt = dlq.createdAt,
                        )
                    )
                }
            }
            rows.size
        }
    }

    /**
     * Permanently delete all DLQ entries.
     */
    suspend fun purgeDeadLetters() {
        checkInitialized()
        withContext(Dispatchers.IO) {
            EventDatabase.get(appContext).dlqDao().deleteAll()
        }
    }

    // ---- Internal API -----------------------------------------------------------------------

    /** Called by [FlushWorker]. */
    internal suspend fun flushInternal() {
        if (!isInitialized) return
        dispatcher.flush().join()
    }

    /** Called by [FlushScheduler] on app foreground. */
    internal fun newSessionInternal() {
        if (!isInitialized) return
        dispatcher.onForeground()
    }

    // ---- Helpers ----------------------------------------------------------------------------

    private fun checkInitialized() {
        check(isInitialized) {
            "EventTracker is not initialized. Call EventTracker.initialize() in Application.onCreate()."
        }
    }
}
