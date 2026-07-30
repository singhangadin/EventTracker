package `in`.singhangad.eventtracker

import `in`.singhangad.eventtracker.internal.DiagnosticsCounters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the small data holders: [DiagnosticsCounters], [Diagnostics] and [TrackEvent].
 * None of these touch the Android framework, so they run without Robolectric.
 */
class DiagnosticsAndModelTest {

    @Test
    fun `counters start at zero and snapshot reflects them`() {
        val counters = DiagnosticsCounters()
        val zero = counters.snapshot()
        assertEquals(0L, zero.tracked)
        assertEquals(0L, zero.dropped)
        assertEquals(0L, zero.persisted)
        assertEquals(0L, zero.delivered)
        assertEquals(0L, zero.retrying)
        assertEquals(0L, zero.deadLettered)
        assertEquals(0L, zero.queueDepth)
    }

    @Test
    fun `snapshot captures each individual counter value`() {
        val counters = DiagnosticsCounters()
        counters.tracked.set(10)
        counters.dropped.set(2)
        counters.persisted.set(8)
        counters.delivered.set(7)
        counters.retrying.set(1)
        counters.deadLettered.set(3)
        counters.queueDepth.set(4)

        val snap = counters.snapshot()
        assertEquals(10L, snap.tracked)
        assertEquals(2L, snap.dropped)
        assertEquals(8L, snap.persisted)
        assertEquals(7L, snap.delivered)
        assertEquals(1L, snap.retrying)
        assertEquals(3L, snap.deadLettered)
        assertEquals(4L, snap.queueDepth)
    }

    @Test
    fun `snapshot is a point-in-time copy, not a live view`() {
        val counters = DiagnosticsCounters()
        val before = counters.snapshot()
        counters.tracked.incrementAndGet()
        assertEquals(0L, before.tracked) // unchanged
        assertEquals(1L, counters.snapshot().tracked)
    }

    @Test
    fun `Diagnostics data class equality and copy`() {
        val d1 = Diagnostics(1, 2, 3, 4, 5, 6, 7)
        val d2 = Diagnostics(1, 2, 3, 4, 5, 6, 7)
        assertEquals(d1, d2)
        assertEquals(d1.hashCode(), d2.hashCode())
        assertEquals(d1, d1.copy())
        assertNotEquals(d1, d1.copy(tracked = 99))
        assertTrue(d1.toString().contains("tracked=1"))
    }

    @Test
    fun `TrackEvent applies documented defaults`() {
        val e = TrackEvent(
            id = "abc",
            name = "evt",
            properties = mapOf("k" to "v"),
            userId = null,
            sessionId = "s1",
            clientTimestamp = 100L,
            clientUptimeMs = 50L,
        )
        assertEquals(1, e.schemaVersion)
        assertNull(e.destinations)
        assertEquals(0, e.attemptCount)
    }

    @Test
    fun `TrackEvent equality, copy and destructuring`() {
        val e = TrackEvent(
            id = "abc",
            name = "evt",
            properties = emptyMap(),
            userId = "u",
            sessionId = "s",
            clientTimestamp = 1L,
            clientUptimeMs = 2L,
            schemaVersion = 2,
            destinations = setOf("backend"),
            attemptCount = 3,
        )
        assertEquals(e, e.copy())
        assertNotEquals(e, e.copy(id = "other"))
        assertEquals("abc", e.id)
        assertEquals(setOf("backend"), e.destinations)
        assertEquals(2, e.schemaVersion)
        assertEquals(3, e.attemptCount)
    }
}
