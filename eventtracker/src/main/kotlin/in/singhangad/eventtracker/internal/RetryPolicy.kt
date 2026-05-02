package `in`.singhangad.eventtracker.internal

import kotlin.math.min
import kotlin.random.Random

/**
 * Truncated exponential backoff with full jitter.
 *
 * `delay(n) = random(0, min(maxDelayMs, baseDelayMs * 2^n))`
 *
 * Full jitter spreads thundering-herd waves evenly when many devices retry against a recovering
 * backend. If the server returns a Retry-After hint, the SDK uses `max(serverHint, computedDelay)`.
 */
internal class RetryPolicy(
    val baseDelayMs: Long = 1_000L,
    val maxDelayMs: Long = 600_000L,
) {
    /**
     * @param attemptCount Zero-based attempt number (0 = first retry after initial failure).
     * @param retryAfterMs Optional server-supplied minimum delay.
     */
    fun nextDelayMs(attemptCount: Int, retryAfterMs: Long? = null): Long {
        val exp = min(attemptCount, 30) // guard against Long overflow on huge attempt counts
        val cap = min(maxDelayMs, baseDelayMs * (1L shl exp))
        val computed = if (cap > 0) Random.nextLong(0, cap + 1) else 0L
        return if (retryAfterMs != null) maxOf(retryAfterMs, computed) else computed
    }
}
