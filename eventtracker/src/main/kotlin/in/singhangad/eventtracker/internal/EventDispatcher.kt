package `in`.singhangad.eventtracker.internal

import android.content.Context
import android.os.SystemClock
import `in`.singhangad.eventtracker.adapter.DeliveryOutcome
import `in`.singhangad.eventtracker.adapter.EventAdapter
import `in`.singhangad.eventtracker.adapter.mapToJson
import `in`.singhangad.eventtracker.internal.db.EventDatabase
import `in`.singhangad.eventtracker.internal.db.EventEntity
import `in`.singhangad.eventtracker.internal.db.EventState
import `in`.singhangad.eventtracker.TrackEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.util.UUID
import java.util.concurrent.Executors
import java.util.regex.Pattern

private const val TAG = "ET/Dispatcher"
private val NAME_PATTERN: Pattern = Pattern.compile("[a-zA-Z0-9_]{1,128}")

/**
 * The single-threaded heart of the EventTracker pipeline.
 *
 * All track / identify calls hand off to [scope] immediately and return to the caller. [scope]
 * is backed by a single-threaded executor so DB writes and adapter fan-out are serialised without
 * locks. Actual Room I/O is delegated to [Dispatchers.IO] (Room handles its own thread
 * confinement); adapter delivery runs on [Dispatchers.Default].
 *
 * The single-threaded internal dispatcher gives strict ordering, no read-modify-write races on
 * the database, and adapters that can be written without locks.
 */
internal class EventDispatcher(
    private val context: Context,
    private val adapters: List<EventAdapter>,
    private val optOutGuard: OptOutGuard,
    private val samplingFilter: SamplingFilter,
    private val sessionManager: SessionManager,
    private val counters: DiagnosticsCounters,
    private val maxLocalEvents: Int,
    private val logger: EventLogger,
) {
    private val singleThreadDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    internal val scope = CoroutineScope(singleThreadDispatcher)

    private val db: EventDatabase = EventDatabase.get(context)

    @Volatile
    private var currentUserId: String? = null

    // ---- Public operations (non-blocking) ---------------------------------------------------

    fun track(
        name: String,
        properties: Map<String, Any?>,
        destinations: Set<String>?,
    ) {
        if (optOutGuard.isOptedOut) {
            counters.dropped.incrementAndGet()
            return
        }
        counters.tracked.incrementAndGet()

        scope.launch {
            handleTrack(name, properties, destinations)
        }
    }

    fun identify(userId: String?, traits: Map<String, Any?>) {
        if (optOutGuard.isOptedOut) return
        scope.launch {
            currentUserId = userId
            adapters.forEach { adapter ->
                try { adapter.identify(userId, traits) }
                catch (t: Throwable) { logger.error(TAG, "identify failed in ${adapter.id}", t) }
            }
        }
    }

    fun reset() {
        scope.launch { currentUserId = null }
    }

    fun flush(): Job = scope.launch { flushAll() }

    fun wipeLocalData(): Job = scope.launch {
        withContext(Dispatchers.IO) {
            db.eventDao().trimOldest(Int.MAX_VALUE)
            db.dlqDao().deleteAll()
        }
        adapters.forEach { adapter ->
            try { adapter.onOptOut() }
            catch (t: Throwable) { logger.error(TAG, "onOptOut failed in ${adapter.id}", t) }
        }
        counters.queueDepth.set(0)
        logger.info(TAG, "Local data wiped")
    }

    // ---- Internal pipeline ------------------------------------------------------------------

    private suspend fun handleTrack(
        name: String,
        properties: Map<String, Any?>,
        destinations: Set<String>?,
    ) {
        // Validate name
        if (!NAME_PATTERN.matcher(name).matches()) {
            logger.warn(TAG, "Rejected event '$name': name must match [a-zA-Z0-9_]{1,128}")
            counters.dropped.incrementAndGet()
            return
        }

        // Sampling
        if (!samplingFilter.shouldKeep(name)) {
            counters.dropped.incrementAndGet()
            return
        }

        val event = TrackEvent(
            id = UUID.randomUUID().toString(),
            name = name,
            properties = sanitiseProperties(properties),
            userId = currentUserId,
            sessionId = sessionManager.sessionId,
            clientTimestamp = System.currentTimeMillis(),
            clientUptimeMs = SystemClock.elapsedRealtime(),
            destinations = destinations,
        )

        // Persist first — before any delivery attempt
        persistEvent(event)

        // Fan out to adapters
        adapters.forEach { adapter ->
            if (!adapter.accepts(event)) return@forEach
            try {
                val outcome = adapter.deliver(event)
                when (outcome) {
                    is DeliveryOutcome.Success -> counters.delivered.incrementAndGet()
                    is DeliveryOutcome.RetryableFailure -> counters.retrying.incrementAndGet()
                    is DeliveryOutcome.PermanentFailure -> counters.deadLettered.incrementAndGet()
                }
            } catch (t: Throwable) {
                // Adapter exceptions must never propagate — log and continue
                logger.error(TAG, "Adapter ${adapter.id} threw on deliver()", t)
            }
        }
    }

    private suspend fun persistEvent(event: TrackEvent) {
        withContext(Dispatchers.IO) {
            val dao = db.eventDao()

            // Enforce local event cap: drop oldest if over the limit
            val count = dao.count()
            if (count >= maxLocalEvents) {
                val excess = (count - maxLocalEvents + 1).toInt()
                dao.trimOldest(excess)
                logger.warn(TAG, "Local cap reached — trimmed $excess oldest events")
            }

            dao.insert(
                EventEntity(
                    id = event.id,
                    name = event.name,
                    payloadJson = mapToJson(event.properties),
                    userId = event.userId,
                    sessionId = event.sessionId,
                    clientTs = event.clientTimestamp,
                    clientUptimeMs = event.clientUptimeMs,
                    schemaVersion = event.schemaVersion,
                    destinationsCsv = event.destinations?.joinToString(","),
                    state = EventState.QUEUED,
                    attemptCount = 0,
                    nextAttemptAt = 0L,
                    lastError = null,
                    createdAt = event.clientTimestamp,
                )
            )
            counters.persisted.incrementAndGet()
            counters.queueDepth.set(dao.queueDepth())
        }
    }

    private suspend fun flushAll() {
        adapters.forEach { adapter ->
            try {
                adapter.flush()
            } catch (t: Throwable) {
                logger.error(TAG, "flush() threw in adapter ${adapter.id}", t)
            }
        }
        withContext(Dispatchers.IO) {
            counters.queueDepth.set(db.eventDao().queueDepth())
        }
    }

    /**
     * Sanitise property values: non-JSON-serialisable types are coerced to their toString()
     * representation and a diagnostic counter is incremented.
     */
    private fun sanitiseProperties(props: Map<String, Any?>): Map<String, Any?> {
        var coerced = 0
        val result = props.mapValues { (_, value) ->
            when (value) {
                null, is String, is Int, is Long, is Double, is Float, is Boolean,
                is Map<*, *>, is List<*> -> value
                else -> {
                    coerced++
                    value.toString()
                }
            }
        }
        if (coerced > 0) logger.debug(TAG, "Coerced $coerced property values to String")
        return result
    }

    /** Called by FlushScheduler when the app returns to the foreground. */
    internal fun onForeground() {
        sessionManager.newSession()
    }

    /** Recover any rows stuck in SENDING state from a prior process kill. */
    internal suspend fun recoverStuckSendingRows() {
        withContext(Dispatchers.IO) {
            db.eventDao().resetSendingToQueued()
        }
    }
}
