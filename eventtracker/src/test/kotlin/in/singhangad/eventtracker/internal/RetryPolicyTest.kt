package `in`.singhangad.eventtracker.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RetryPolicyTest {

    private val policy = RetryPolicy(baseDelayMs = 1_000L, maxDelayMs = 60_000L)

    @Test
    fun `attempt 0 delay is in range 0 to baseDelayMs`() {
        repeat(200) {
            val d = policy.nextDelayMs(0)
            assertTrue("delay=$d must be in [0, 1000]", d in 0..1_000)
        }
    }

    @Test
    fun `delay for each attempt stays within its exponential cap`() {
        for (attempt in 0..15) {
            val cap = minOf(60_000L, 1_000L shl attempt.coerceAtMost(30))
            repeat(50) {
                val d = policy.nextDelayMs(attempt)
                assertTrue("attempt=$attempt delay=$d must be in [0, $cap]", d in 0..cap)
            }
        }
    }

    @Test
    fun `delay never exceeds maxDelayMs`() {
        repeat(200) {
            val d = policy.nextDelayMs(30)
            assertTrue("delay=$d must be <= 60000", d <= 60_000)
        }
    }

    @Test
    fun `large attemptCount does not overflow or throw`() {
        val d1 = policy.nextDelayMs(100)
        val d2 = policy.nextDelayMs(Int.MAX_VALUE)
        assertTrue(d1 in 0..60_000)
        assertTrue(d2 in 0..60_000)
    }

    @Test
    fun `retryAfterMs wins when it exceeds computed delay`() {
        val retryAfterMs = 120_000L // > maxDelayMs
        repeat(100) {
            val d = policy.nextDelayMs(0, retryAfterMs)
            assertEquals(retryAfterMs, d)
        }
    }

    @Test
    fun `retryAfterMs of zero does not reduce computed delay below zero`() {
        repeat(100) {
            val d = policy.nextDelayMs(3, retryAfterMs = 0L)
            assertTrue(d >= 0)
        }
    }

    @Test
    fun `custom baseDelayMs and maxDelayMs are respected`() {
        val custom = RetryPolicy(baseDelayMs = 500L, maxDelayMs = 5_000L)
        repeat(200) {
            val d = custom.nextDelayMs(0)
            assertTrue(d in 0..500)
        }
        repeat(200) {
            val d = custom.nextDelayMs(20)
            assertTrue(d <= 5_000)
        }
    }
}
