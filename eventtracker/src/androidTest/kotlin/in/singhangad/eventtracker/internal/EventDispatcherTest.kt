package `in`.singhangad.eventtracker.internal

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import `in`.singhangad.eventtracker.TrackEvent
import `in`.singhangad.eventtracker.adapter.DeliveryOutcome
import `in`.singhangad.eventtracker.adapter.EventAdapter
import `in`.singhangad.eventtracker.internal.db.EventDatabase
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EventDispatcherTest {

    private lateinit var context: Context
    private lateinit var db: EventDatabase
    private lateinit var counters: DiagnosticsCounters
    private lateinit var optOutGuard: OptOutGuard
    private lateinit var samplingFilter: SamplingFilter
    private lateinit var sessionManager: SessionManager
    private lateinit var fakeAdapter: FakeAdapter
    private lateinit var dispatcher: EventDispatcher

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        clearPrefs()

        db = Room.inMemoryDatabaseBuilder(context, EventDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        EventDatabase.setInstanceForTesting(db)

        counters = DiagnosticsCounters()
        optOutGuard = OptOutGuard(context)
        samplingFilter = SamplingFilter(emptyMap())
        sessionManager = SessionManager(context)
        fakeAdapter = FakeAdapter()

        dispatcher = EventDispatcher(
            context = context,
            adapters = listOf(fakeAdapter),
            optOutGuard = optOutGuard,
            samplingFilter = samplingFilter,
            sessionManager = sessionManager,
            counters = counters,
            maxLocalEvents = 1_000,
            logger = NoOpLogger,
        )
    }

    @After
    fun teardown() {
        dispatcher.scope.cancel()
        db.close()
        EventDatabase.clearTestInstance()
        clearPrefs()
    }

    private fun clearPrefs() {
        context.getSharedPreferences("eventtracker_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("eventtracker_session", Context.MODE_PRIVATE).edit().clear().commit()
    }

    /** Drains the single-threaded scope: flush() queues behind all prior track() jobs. */
    private fun drain() = runBlocking { dispatcher.flush().join() }

    // ---- track ---

    @Test
    fun track_valid_event_persists_to_db() {
        dispatcher.track("button_click", mapOf("screen" to "home"), null)
        drain()
        runBlocking {
            assertEquals(1L, db.eventDao().count())
        }
    }

    @Test
    fun track_delivers_event_to_adapter() {
        dispatcher.track("page_view", emptyMap(), null)
        drain()
        assertEquals(1, fakeAdapter.delivered.size)
        assertEquals("page_view", fakeAdapter.delivered[0].name)
    }

    @Test
    fun track_increments_tracked_counter() {
        dispatcher.track("e1", emptyMap(), null)
        dispatcher.track("e2", emptyMap(), null)
        drain()
        assertEquals(2L, counters.tracked.get())
    }

    @Test
    fun track_invalid_name_drops_event_and_does_not_persist() {
        dispatcher.track("invalid name!", emptyMap(), null) // space is not allowed
        drain()
        runBlocking {
            assertEquals(0L, db.eventDao().count())
        }
        assertEquals(1L, counters.dropped.get())
        assertTrue(fakeAdapter.delivered.isEmpty())
    }

    @Test
    fun track_empty_name_is_rejected() {
        dispatcher.track("", emptyMap(), null)
        drain()
        runBlocking { assertEquals(0L, db.eventDao().count()) }
    }

    @Test
    fun track_name_over_128_chars_is_rejected() {
        val longName = "a".repeat(129)
        dispatcher.track(longName, emptyMap(), null)
        drain()
        runBlocking { assertEquals(0L, db.eventDao().count()) }
        assertEquals(1L, counters.dropped.get())
    }

    @Test
    fun track_when_opted_out_drops_event() {
        optOutGuard.setOptOut(true)
        dispatcher.track("click", emptyMap(), null)
        drain()
        runBlocking { assertEquals(0L, db.eventDao().count()) }
        assertEquals(1L, counters.dropped.get())
    }

    @Test
    fun track_with_zero_sampling_rate_drops_event() {
        val dispatcherWithSampling = EventDispatcher(
            context = context,
            adapters = listOf(fakeAdapter),
            optOutGuard = optOutGuard,
            samplingFilter = SamplingFilter(mapOf("rare_event" to 0.0)),
            sessionManager = sessionManager,
            counters = counters,
            maxLocalEvents = 1_000,
            logger = NoOpLogger,
        )
        dispatcherWithSampling.track("rare_event", emptyMap(), null)
        runBlocking { dispatcherWithSampling.flush().join() }
        runBlocking { assertEquals(0L, db.eventDao().count()) }
        dispatcherWithSampling.scope.cancel()
    }

    @Test
    fun track_respects_maxLocalEvents_cap_by_trimming_oldest() = runBlocking {
        val smallCapDispatcher = EventDispatcher(
            context = context,
            adapters = listOf(fakeAdapter),
            optOutGuard = optOutGuard,
            samplingFilter = samplingFilter,
            sessionManager = sessionManager,
            counters = counters,
            maxLocalEvents = 3,
            logger = NoOpLogger,
        )
        // Insert 3 events first
        repeat(3) { i -> smallCapDispatcher.track("event_$i", emptyMap(), null) }
        smallCapDispatcher.flush().join()
        assertEquals(3L, db.eventDao().count())

        // 4th event should trigger a trim
        smallCapDispatcher.track("event_overflow", emptyMap(), null)
        smallCapDispatcher.flush().join()

        // Cap is 3; the oldest should have been removed
        assertEquals(3L, db.eventDao().count())
        smallCapDispatcher.scope.cancel()
    }

    // ---- identify ---

    @Test
    fun identify_propagates_userId_to_adapter() {
        dispatcher.identify("user-123", mapOf("plan" to "pro"))
        drain()
        assertEquals("user-123", fakeAdapter.identifyUserId)
        assertEquals("pro", fakeAdapter.identifyTraits["plan"])
    }

    @Test
    fun identify_null_userId_clears_user() {
        dispatcher.identify("user-123", emptyMap())
        drain()
        dispatcher.identify(null, emptyMap())
        drain()
        assertNull(fakeAdapter.identifyUserId)
    }

    // ---- reset ---

    @Test
    fun reset_clears_userId_from_subsequent_events() {
        dispatcher.identify("user-abc", emptyMap())
        drain()
        dispatcher.reset()
        dispatcher.track("after_reset", emptyMap(), null)
        drain()
        val event = fakeAdapter.delivered.last()
        assertNull(event.userId)
    }

    // ---- flush ---

    @Test
    fun flush_calls_adapter_flush() {
        dispatcher.flush()
        drain()
        assertEquals(1, fakeAdapter.flushCount)
    }

    // ---- wipeLocalData ---

    @Test
    fun wipeLocalData_clears_db_and_calls_onOptOut() = runBlocking {
        dispatcher.track("e1", emptyMap(), null)
        dispatcher.track("e2", emptyMap(), null)
        dispatcher.flush().join()

        dispatcher.wipeLocalData().join()

        assertEquals(0L, db.eventDao().count())
        assertEquals(0L, db.dlqDao().count())
        assertTrue(fakeAdapter.optOutCalled)
        assertEquals(0L, counters.queueDepth.get())
    }

    // ---- onForeground ---

    @Test
    fun onForeground_rotates_the_session_id() {
        val sessionBefore = sessionManager.sessionId
        dispatcher.onForeground()
        val sessionAfter = sessionManager.sessionId
        assertNotEquals(sessionBefore, sessionAfter)
    }

    // ---- recoverStuckSendingRows ---

    @Test
    fun recoverStuckSendingRows_resets_SENDING_to_QUEUED() = runBlocking {
        // Manually inject a stuck SENDING row
        db.eventDao().insert(
            `in`.singhangad.eventtracker.internal.db.EventEntity(
                id = "stuck",
                name = "stuck_event",
                payloadJson = "{}",
                userId = null,
                sessionId = "s1",
                clientTs = 0L,
                clientUptimeMs = 0L,
                schemaVersion = 1,
                destinationsCsv = null,
                state = `in`.singhangad.eventtracker.internal.db.EventState.SENDING,
                attemptCount = 0,
                nextAttemptAt = 0L,
                lastError = null,
                createdAt = 0L,
            )
        )

        dispatcher.recoverStuckSendingRows()

        val batch = db.eventDao().nextBatch(Long.MAX_VALUE, 10)
        assertEquals(1, batch.size)
        assertEquals("stuck", batch[0].id)
        assertEquals(`in`.singhangad.eventtracker.internal.db.EventState.QUEUED, batch[0].state)
    }
}

// ---- Fake adapter ---

private class FakeAdapter : EventAdapter {
    override val id = "fake"

    val delivered = mutableListOf<TrackEvent>()
    var flushCount = 0
    var identifyUserId: String? = "unset"
    var identifyTraits: Map<String, Any?> = emptyMap()
    var optOutCalled = false

    override fun initialize(context: Context, logger: `in`.singhangad.eventtracker.internal.EventLogger) {}
    override fun accepts(event: TrackEvent) = true

    override suspend fun deliver(event: TrackEvent): DeliveryOutcome {
        delivered.add(event)
        return DeliveryOutcome.Success
    }

    override suspend fun flush(): DeliveryOutcome {
        flushCount++
        return DeliveryOutcome.Success
    }

    override suspend fun identify(userId: String?, traits: Map<String, Any?>) {
        identifyUserId = userId
        identifyTraits = traits
    }

    override fun onOptOut() {
        optOutCalled = true
    }
}
