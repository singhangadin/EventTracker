package `in`.singhangad.eventtracker.internal

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import `in`.singhangad.eventtracker.TrackEvent
import `in`.singhangad.eventtracker.adapter.DeliveryOutcome
import `in`.singhangad.eventtracker.adapter.EventAdapter
import `in`.singhangad.eventtracker.internal.db.EventDatabase
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Covers the [EventDispatcher] branches not exercised by [EventDispatcherTest]: property
 * sanitisation, non-Success delivery outcomes, and adapters that throw.
 */
@RunWith(RobolectricTestRunner::class)
class EventDispatcherExtraTest {

    private lateinit var context: Context
    private lateinit var db: EventDatabase
    private lateinit var counters: DiagnosticsCounters
    private val created = mutableListOf<EventDispatcher>()

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("eventtracker_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("eventtracker_session", Context.MODE_PRIVATE).edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(context, EventDatabase::class.java)
            .allowMainThreadQueries().build()
        EventDatabase.setInstanceForTesting(db)
        counters = DiagnosticsCounters()
    }

    @After
    fun teardown() {
        created.forEach { it.scope.cancel() }
        db.close()
        EventDatabase.clearTestInstance()
    }

    private fun dispatcher(vararg adapters: EventAdapter): EventDispatcher {
        val d = EventDispatcher(
            context = context,
            adapters = adapters.toList(),
            optOutGuard = OptOutGuard(context),
            samplingFilter = SamplingFilter(emptyMap()),
            sessionManager = SessionManager(context),
            counters = counters,
            maxLocalEvents = 1_000,
            logger = NoOpLogger,
        )
        created.add(d)
        return d
    }

    private fun EventDispatcher.drain() = runBlocking { awaitIdle() }

    @Test
    fun `non-primitive property values are coerced to strings`() = runBlocking {
        data class Point(val x: Int, val y: Int)
        val d = dispatcher(CollectingAdapter())
        d.track("evt", mapOf("p" to Point(1, 2), "n" to 5), null)
        d.awaitIdle()

        val row = db.eventDao().nextBatch(Long.MAX_VALUE, 10).single()
        assertTrue(row.payloadJson.contains("Point(x=1, y=2)"))
        assertTrue(row.payloadJson.contains("\"n\":5"))
    }

    @Test
    fun `nested maps and lists are preserved through sanitisation`() = runBlocking {
        val d = dispatcher(CollectingAdapter())
        d.track("evt", mapOf("list" to listOf(1, 2), "map" to mapOf("a" to "b")), null)
        d.awaitIdle()
        val row = db.eventDao().nextBatch(Long.MAX_VALUE, 10).single()
        assertTrue(row.payloadJson.contains("list"))
        assertTrue(row.payloadJson.contains("map"))
    }

    @Test
    fun `retryable delivery outcome increments the retrying counter`() {
        val d = dispatcher(OutcomeAdapter(DeliveryOutcome.RetryableFailure(RuntimeException())))
        d.track("e", emptyMap(), null)
        d.drain()
        assertEquals(1L, counters.retrying.get())
    }

    @Test
    fun `permanent delivery outcome increments the dead-lettered counter`() {
        val d = dispatcher(OutcomeAdapter(DeliveryOutcome.PermanentFailure(RuntimeException())))
        d.track("e", emptyMap(), null)
        d.drain()
        assertEquals(1L, counters.deadLettered.get())
    }

    @Test
    fun `an adapter that throws on deliver does not break the pipeline`() {
        val throwing = ThrowingAdapter()
        val good = CollectingAdapter()
        val d = dispatcher(throwing, good)
        d.track("e", emptyMap(), null)
        d.drain()
        // The throwing adapter is caught; the healthy adapter still receives the event.
        assertEquals(1, good.delivered.size)
    }

    @Test
    fun `an adapter that declines the event is skipped`() {
        val declining = CollectingAdapter(accept = false)
        val d = dispatcher(declining)
        d.track("e", emptyMap(), null)
        d.drain()
        assertTrue(declining.delivered.isEmpty())
        // Still persisted regardless of adapter acceptance.
        assertEquals(1L, runBlocking { db.eventDao().count() })
    }

    @Test
    fun `identify swallows adapter exceptions but still records the user`() {
        val good = CollectingAdapter()
        val d = dispatcher(ThrowingAdapter(), good)
        d.identify("user-x", mapOf("k" to "v"))
        d.track("e", emptyMap(), null)
        d.drain()
        assertEquals("user-x", good.delivered.single().userId)
    }

    @Test
    fun `flush swallows adapter exceptions`() {
        val d = dispatcher(ThrowingAdapter())
        d.track("e", emptyMap(), null)
        // ThrowingAdapter.flush throws; flushAll must catch it.
        runBlocking { d.awaitIdle(); d.flush().join() }
        // No assertion needed beyond "did not crash", but confirm the counter moved.
        assertEquals(1L, counters.tracked.get())
    }

    @Test
    fun `wipeLocalData swallows adapter onOptOut exceptions`() = runBlocking {
        val d = dispatcher(ThrowingAdapter())
        d.track("e", emptyMap(), null)
        d.awaitIdle() // ensure the event is persisted before wiping
        d.wipeLocalData().join() // ThrowingAdapter.onOptOut throws; must be caught
        assertEquals(0L, db.eventDao().count())
    }

    @Test
    fun `reset clears the current user id`() {
        val good = CollectingAdapter()
        val d = dispatcher(good)
        d.identify("u", emptyMap())
        d.drain()
        d.reset()
        d.track("e", emptyMap(), null)
        d.drain()
        assertNull(good.delivered.last().userId)
    }

    // ---- fake adapters ----

    private open class CollectingAdapter(private val accept: Boolean = true) : EventAdapter {
        override val id = "collect"
        val delivered = mutableListOf<TrackEvent>()
        override fun initialize(context: Context, logger: EventLogger) {}
        override fun accepts(event: TrackEvent) = accept
        override suspend fun deliver(event: TrackEvent): DeliveryOutcome {
            delivered.add(event); return DeliveryOutcome.Success
        }
    }

    private class OutcomeAdapter(private val outcome: DeliveryOutcome) : EventAdapter {
        override val id = "outcome"
        override fun initialize(context: Context, logger: EventLogger) {}
        override fun accepts(event: TrackEvent) = true
        override suspend fun deliver(event: TrackEvent): DeliveryOutcome = outcome
    }

    private class ThrowingAdapter : EventAdapter {
        override val id = "throwing"
        override fun initialize(context: Context, logger: EventLogger) {}
        override fun accepts(event: TrackEvent) = true
        override suspend fun deliver(event: TrackEvent): DeliveryOutcome = throw RuntimeException("deliver boom")
        override suspend fun flush(): DeliveryOutcome = throw RuntimeException("flush boom")
        override suspend fun identify(userId: String?, traits: Map<String, Any?>) = throw RuntimeException("identify boom")
        override fun onOptOut() = throw RuntimeException("optout boom")
    }
}
