package `in`.singhangad.eventtracker.internal

import kotlin.random.Random

/**
 * Evaluates per-event-name sampling rates. Events not in the map default to rate 1.0 (always kept).
 * A rate of 0.0 drops all events with that name; 1.0 keeps all.
 */
internal class SamplingFilter(private val rates: Map<String, Double>) {

    /** Returns true if the event should be kept, false if it should be dropped. */
    fun shouldKeep(eventName: String): Boolean {
        val rate = rates[eventName] ?: return true
        return Random.nextDouble() < rate
    }
}
