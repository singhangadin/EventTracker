package `in`.singhangad.eventtracker.internal

import android.util.Log

/**
 * SLF4J-style logger interface for internal EventTracker diagnostics.
 *
 * The default implementation is [NoOpLogger] — production builds are silent unless the host
 * explicitly opts in via [EventTrackerConfig.Builder.logger].
 *
 * @since 1.0.0
 */
interface EventLogger {
    fun verbose(tag: String, message: String)
    fun debug(tag: String, message: String)
    fun info(tag: String, message: String)
    fun warn(tag: String, message: String)
    fun error(tag: String, message: String, throwable: Throwable? = null)
}

internal object NoOpLogger : EventLogger {
    override fun verbose(tag: String, message: String) = Unit
    override fun debug(tag: String, message: String) = Unit
    override fun info(tag: String, message: String) = Unit
    override fun warn(tag: String, message: String) = Unit
    override fun error(tag: String, message: String, throwable: Throwable?) = Unit
}

/** Logger that forwards to Android [Log]. Useful for development. */
class AndroidLogger : EventLogger {
    override fun verbose(tag: String, message: String) { Log.v(tag, message) }
    override fun debug(tag: String, message: String) { Log.d(tag, message) }
    override fun info(tag: String, message: String) { Log.i(tag, message) }
    override fun warn(tag: String, message: String) { Log.w(tag, message) }
    override fun error(tag: String, message: String, throwable: Throwable?) {
        Log.e(tag, message, throwable)
    }
}
