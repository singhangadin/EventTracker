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
}
