package `in`.singhangad.eventtracker.adapter

import android.content.Context
import android.os.Bundle
import `in`.singhangad.eventtracker.TrackEvent
import `in`.singhangad.eventtracker.internal.EventLogger
import com.google.firebase.analytics.FirebaseAnalytics

/**
 * Adapter that wraps [FirebaseAnalytics]. Delivers events immediately using
 * `FirebaseAnalytics.logEvent`.
 *
 * Add this module to your app's dependencies to use it:
 * ```
 * implementation(project(":eventtracker-adapter-firebase"))
 * ```
 *
 * Property values are mapped to Firebase's supported Bundle types:
 * - `String`, `Int`, `Long`, `Double`, `Float` → stored as-is
 * - `Boolean` → stored as Int (1 or 0)
 * - Everything else → `toString()`
 *
 * Firebase event names and property keys are truncated to their platform limits automatically.
 *
 * @since 1.0.0
 */
class FirebaseAdapter : EventAdapter {

    override val id: String = "firebase"

    private lateinit var analytics: FirebaseAnalytics
    private lateinit var logger: EventLogger

    override fun initialize(context: Context, logger: EventLogger) {
        this.logger = logger
        analytics = FirebaseAnalytics.getInstance(context)
    }

    override fun accepts(event: TrackEvent): Boolean =
        event.destinations?.contains(id) ?: true

    override suspend fun deliver(event: TrackEvent): DeliveryOutcome = try {
        analytics.logEvent(sanitizeName(event.name), buildBundle(event.properties))
        DeliveryOutcome.Success
    } catch (t: Throwable) {
        logger.error("ET/Firebase", "logEvent failed for ${event.name}", t)
        DeliveryOutcome.RetryableFailure(t)
    }

    override suspend fun identify(userId: String?, traits: Map<String, Any?>) {
        try {
            analytics.setUserId(userId)
            for ((key, value) in traits) {
                analytics.setUserProperty(key.take(24), value?.toString()?.take(36))
            }
        } catch (t: Throwable) {
            logger.error("ET/Firebase", "identify failed", t)
        }
    }

    private fun buildBundle(properties: Map<String, Any?>): Bundle {
        val bundle = Bundle()
        for ((key, value) in properties) {
            val k = key.take(40) // Firebase key limit
            when (value) {
                is String -> bundle.putString(k, value.take(100))
                is Int -> bundle.putInt(k, value)
                is Long -> bundle.putLong(k, value)
                is Double -> bundle.putDouble(k, value)
                is Float -> bundle.putFloat(k, value)
                is Boolean -> bundle.putInt(k, if (value) 1 else 0)
                else -> bundle.putString(k, value?.toString()?.take(100))
            }
        }
        return bundle
    }

    /** Firebase event names: max 40 chars, alphanumeric + underscore. */
    private fun sanitizeName(name: String): String =
        name.replace(Regex("[^a-zA-Z0-9_]"), "_").take(40)
}
