package `in`.singhangad.eventtracker.internal

import `in`.singhangad.eventtracker.Diagnostics
import java.util.concurrent.atomic.AtomicLong

internal class DiagnosticsCounters {
    val tracked = AtomicLong(0)
    val dropped = AtomicLong(0)
    val persisted = AtomicLong(0)
    val delivered = AtomicLong(0)
    val retrying = AtomicLong(0)
    val deadLettered = AtomicLong(0)
    val queueDepth = AtomicLong(0)

    fun snapshot(): Diagnostics = Diagnostics(
        tracked = tracked.get(),
        dropped = dropped.get(),
        persisted = persisted.get(),
        delivered = delivered.get(),
        retrying = retrying.get(),
        deadLettered = deadLettered.get(),
        queueDepth = queueDepth.get(),
    )

    /** Testing only: zero every counter. */
    internal fun reset() {
        tracked.set(0); dropped.set(0); persisted.set(0); delivered.set(0)
        retrying.set(0); deadLettered.set(0); queueDepth.set(0)
    }
}
