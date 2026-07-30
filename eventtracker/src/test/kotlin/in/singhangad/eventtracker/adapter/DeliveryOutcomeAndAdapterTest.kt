package `in`.singhangad.eventtracker.adapter

import android.content.Context
import `in`.singhangad.eventtracker.TrackEvent
import `in`.singhangad.eventtracker.internal.EventLogger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [DeliveryOutcome] variants and the default methods on the [EventAdapter]
 * interface. Neither requires the Android framework.
 */
class DeliveryOutcomeAndAdapterTest {

    @Test
    fun `Success is a singleton object`() {
        assertSame(DeliveryOutcome.Success, DeliveryOutcome.Success)
    }

    @Test
    fun `RetryableFailure carries cause and optional retryAfter`() {
        val cause = RuntimeException("boom")
        val a = DeliveryOutcome.RetryableFailure(cause)
        assertSame(cause, a.cause)
        assertEquals(null, a.retryAfterMs)

        val b = DeliveryOutcome.RetryableFailure(cause, 5_000L)
        assertEquals(5_000L, b.retryAfterMs)

        assertEquals(a, DeliveryOutcome.RetryableFailure(cause))
        assertNotEquals(a, b)
        assertTrue(a.toString().contains("RetryableFailure"))
    }

    @Test
    fun `PermanentFailure carries cause`() {
        val cause = IllegalStateException("nope")
        val p = DeliveryOutcome.PermanentFailure(cause)
        assertSame(cause, p.cause)
        assertEquals(p, DeliveryOutcome.PermanentFailure(cause))
        assertNotEquals(p as DeliveryOutcome, DeliveryOutcome.Success)
    }

    /** Minimal adapter that overrides only the required members, exercising the interface defaults. */
    private class MinimalAdapter : EventAdapter {
        override val id: String = "minimal"
        var initialized = false
        override fun initialize(context: Context, logger: EventLogger) { initialized = true }
        override fun accepts(event: TrackEvent): Boolean = true
        override suspend fun deliver(event: TrackEvent): DeliveryOutcome = DeliveryOutcome.Success
    }

    private fun sampleEvent() = TrackEvent(
        id = "1", name = "e", properties = emptyMap(), userId = null,
        sessionId = "s", clientTimestamp = 0L, clientUptimeMs = 0L,
    )

    @Test
    fun `default identify flush and onOptOut are safe no-ops`() = runBlocking {
        val adapter = MinimalAdapter()
        // Defaults must not throw.
        adapter.identify("u", mapOf("k" to "v"))
        adapter.onOptOut()
        val outcome = adapter.flush()
        assertEquals(DeliveryOutcome.Success, outcome)

        // And the overridden required members still work.
        assertEquals("minimal", adapter.id)
        assertTrue(adapter.accepts(sampleEvent()))
        assertEquals(DeliveryOutcome.Success, adapter.deliver(sampleEvent()))
    }
}
