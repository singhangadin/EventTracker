package `in`.singhangad.eventtracker

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import `in`.singhangad.eventtracker.adapter.DeliveryOutcome
import `in`.singhangad.eventtracker.adapter.EventAdapter
import `in`.singhangad.eventtracker.internal.EventLogger
import `in`.singhangad.eventtracker.internal.FlushWorker
import `in`.singhangad.eventtracker.internal.db.DeadLetterEntity
import `in`.singhangad.eventtracker.internal.db.EventDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EventTrackerTest {

    private lateinit var context: Context
    private lateinit var db: EventDatabase
    private lateinit var adapter: FakeAdapter

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        // Robolectric may reuse a sandbox across tests, so clear any leaked singleton state first.
        EventTracker.resetForTesting()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        clearPrefs()
        db = Room.inMemoryDatabaseBuilder(context, EventDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        EventDatabase.setInstanceForTesting(db)
        adapter = FakeAdapter()
    }

    @After
    fun teardown() {
        EventTracker.resetForTesting() // stop scheduler / cancel dispatcher before closing the db
        db.close()
        EventDatabase.clearTestInstance()
        clearPrefs()
    }

    private fun clearPrefs() {
        context.getSharedPreferences("eventtracker_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("eventtracker_session", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun init(vararg extra: EventAdapter): EventTrackerConfig {
        val builder = EventTrackerConfig.Builder().addAdapter(adapter)
        extra.forEach { builder.addAdapter(it) }
        val config = builder.build()
        EventTracker.initialize(context, config)
        return config
    }

    private fun drainFlush() = runBlocking {
        EventTracker.flush().join()
        EventTracker.awaitIdleForTesting() // also wait for track/identify handlers to finish
    }

    // ---- initialization ----

    @Test
    fun `calls before initialize throw IllegalStateException`() {
        val ex = assertThrows(IllegalStateException::class.java) {
            EventTracker.track("nope")
        }
        assertTrue(ex.message!!.contains("not initialized"))
    }

    @Test
    fun `initialize marks the SDK initialised`() {
        assertFalse(EventTracker.isInitialized)
        init()
        assertTrue(EventTracker.isInitialized)
    }

    @Test
    fun `initialize is idempotent - second call is ignored`() {
        init()
        // Second call with a different config must be a no-op (no throw, still initialised).
        EventTracker.initialize(context, EventTrackerConfig.Builder().addAdapter(FakeAdapter()).build())
        assertTrue(EventTracker.isInitialized)
    }

    // ---- track ----

    @Test
    fun `track persists and delivers a valid event`() {
        init()
        EventTracker.track("checkout", mapOf("total" to 9.99))
        drainFlush()
        assertEquals(1, adapter.delivered.size)
        assertEquals("checkout", adapter.delivered[0].name)
        val d = EventTracker.diagnostics()
        assertEquals(1L, d.tracked)
        assertEquals(1L, d.persisted)
    }

    @Test
    fun `track with an explicit destination set is routed`() {
        init()
        EventTracker.track("ping", destinations = setOf("fake"))
        drainFlush()
        assertEquals(1, adapter.delivered.size)
        assertEquals(setOf("fake"), adapter.delivered[0].destinations)
    }

    // ---- identify / reset ----

    @Test
    fun `identify then reset clears the user id on later events`() {
        init()
        EventTracker.identify("user-1", mapOf("plan" to "pro"))
        drainFlush()
        assertEquals("user-1", adapter.identifyUserId)

        EventTracker.reset()
        EventTracker.track("after_reset")
        drainFlush()
        assertEquals(null, adapter.delivered.last().userId)
    }

    // ---- flush ----

    @Test
    fun `flush drains adapters`() {
        init()
        EventTracker.track("e")
        drainFlush()
        assertTrue(adapter.flushCount >= 1)
    }

    // ---- opt-out ----

    @Test
    fun `setOptOut true drops subsequent events`() {
        init()
        EventTracker.setOptOut(true)
        EventTracker.track("blocked")
        drainFlush()
        assertEquals(0L, runBlocking { db.eventDao().count() })
        assertTrue(adapter.delivered.isEmpty())
    }

    // ---- wipeLocalData ----

    @Test
    fun `wipeLocalData clears events and DLQ and notifies adapters`() = runBlocking {
        init()
        EventTracker.track("e1"); EventTracker.track("e2")
        EventTracker.awaitIdleForTesting() // ensure both events are persisted before wiping
        db.dlqDao().insert(dlq("z"))

        EventTracker.wipeLocalData().join()
        EventTracker.awaitIdleForTesting()

        assertEquals(0L, db.eventDao().count())
        assertEquals(0L, db.dlqDao().count())
        assertTrue(adapter.optOutCalled)
        assertEquals(0L, EventTracker.diagnostics().queueDepth)
    }

    // ---- diagnostics ----

    @Test
    fun `diagnostics reports dropped events for invalid names`() {
        init()
        EventTracker.track("bad name!!") // invalid -> dropped
        drainFlush()
        assertEquals(1L, EventTracker.diagnostics().dropped)
    }

    // ---- DLQ API ----

    @Test
    fun `deadLetterSize reports the DLQ row count`() = runBlocking {
        init()
        db.dlqDao().insert(dlq("a")); db.dlqDao().insert(dlq("b"))
        assertEquals(2L, EventTracker.deadLetterSize())
    }

    @Test
    fun `replayDeadLetters moves DLQ rows back into the live queue`() = runBlocking {
        init()
        db.dlqDao().insert(dlq("a")); db.dlqDao().insert(dlq("b")); db.dlqDao().insert(dlq("c"))

        val requeued = EventTracker.replayDeadLetters(limit = 500)

        assertEquals(3, requeued)
        assertEquals(0L, db.dlqDao().count())
        assertEquals(3L, db.eventDao().count())
    }

    @Test
    fun `replayDeadLetters on an empty DLQ returns zero`() = runBlocking {
        init()
        assertEquals(0, EventTracker.replayDeadLetters())
    }

    @Test
    fun `purgeDeadLetters empties the DLQ`() = runBlocking {
        init()
        db.dlqDao().insert(dlq("a")); db.dlqDao().insert(dlq("b"))
        EventTracker.purgeDeadLetters()
        assertEquals(0L, db.dlqDao().count())
    }

    // ---- internal hooks ----

    @Test
    fun `newSessionInternal rotates the session id used for events`() {
        init()
        EventTracker.track("before")
        drainFlush()
        val sessionBefore = adapter.delivered.last().sessionId

        EventTracker.newSessionInternal()
        EventTracker.track("after")
        drainFlush()
        val sessionAfter = adapter.delivered.last().sessionId

        assertNotEquals(sessionBefore, sessionAfter)
    }

    @Test
    fun `flushInternal is a no-op before initialize`() = runBlocking {
        // Must not throw when the SDK has not been initialised.
        EventTracker.flushInternal()
    }

    @Test
    fun `newSessionInternal is a no-op before initialize`() {
        EventTracker.newSessionInternal() // no throw, no effect
        assertFalse(EventTracker.isInitialized)
    }

    @Test
    fun `flush worker drives flushInternal once initialised`() {
        init()
        EventTracker.track("e")
        val worker = TestListenableWorkerBuilder<FlushWorker>(context).build()
        val result = runBlocking { worker.doWork() }
        assertEquals(ListenableWorker.Result.success(), result)
        assertTrue(adapter.flushCount >= 1)
    }

    // ---- helpers ----

    private fun dlq(id: String) = DeadLetterEntity(
        id = id,
        name = "failed_$id",
        payloadJson = "{}",
        attemptCount = 8,
        firstFailureAt = 0L,
        lastFailureAt = 1_000L,
        lastError = "HTTP 400",
        httpStatus = 400,
        schemaVersion = 1,
        createdAt = 1_000L,
    )

    private class FakeAdapter : EventAdapter {
        override val id = "fake"
        val delivered = mutableListOf<TrackEvent>()
        var flushCount = 0
        var identifyUserId: String? = "unset"
        var optOutCalled = false

        override fun initialize(context: Context, logger: EventLogger) {}
        override fun accepts(event: TrackEvent) = true
        override suspend fun deliver(event: TrackEvent): DeliveryOutcome {
            delivered.add(event)
            return DeliveryOutcome.Success
        }
        override suspend fun flush(): DeliveryOutcome { flushCount++; return DeliveryOutcome.Success }
        override suspend fun identify(userId: String?, traits: Map<String, Any?>) { identifyUserId = userId }
        override fun onOptOut() { optOutCalled = true }
    }
}
