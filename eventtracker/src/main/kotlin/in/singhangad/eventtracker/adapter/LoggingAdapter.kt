package `in`.singhangad.eventtracker.adapter

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import `in`.singhangad.eventtracker.TrackEvent
import `in`.singhangad.eventtracker.internal.EventLogger

private const val TAG = "ET/Log"

/**
 * Debug adapter that logs every event to Logcat.
 * [accepts] returns `false` in release builds so it is automatically disabled in production.
 *
 * @since 1.0.0
 */
class LoggingAdapter : EventAdapter {

    override val id: String = "log"

    private var logger: EventLogger? = null
    private var isDebug: Boolean = false

    override fun initialize(context: Context, logger: EventLogger) {
        this.logger = logger
        isDebug = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    }

    override fun accepts(event: TrackEvent): Boolean = isDebug

    override suspend fun deliver(event: TrackEvent): DeliveryOutcome {
        Log.d(TAG, buildString {
            append("[${event.name}]")
            if (event.userId != null) append(" user=${event.userId}")
            append(" session=${event.sessionId.take(8)}")
            append(" props=${event.properties}")
        })
        return DeliveryOutcome.Success
    }

    override suspend fun identify(userId: String?, traits: Map<String, Any?>) {
        Log.d(TAG, "identify userId=$userId traits=$traits")
    }
}
