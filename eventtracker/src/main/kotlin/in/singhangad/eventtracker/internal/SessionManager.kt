package `in`.singhangad.eventtracker.internal

import android.content.Context
import androidx.core.content.edit
import java.util.UUID

private const val PREFS_NAME = "eventtracker_session"
private const val KEY_SESSION_ID = "session_id"

/**
 * Manages the current session ID. A new session is started:
 * - On first SDK initialization (no stored session).
 * - When the host app comes back to the foreground via [newSession].
 *
 * The session ID is stored in SharedPreferences so it is stable across configuration changes
 * within the same foreground visit, but rotated on every foreground return.
 */
internal class SessionManager(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Volatile
    private var currentSessionId: String = prefs.getString(KEY_SESSION_ID, null) ?: generateAndStore()

    val sessionId: String get() = currentSessionId

    fun newSession() {
        currentSessionId = generateAndStore()
    }

    private fun generateAndStore(): String {
        val id = UUID.randomUUID().toString()
        prefs.edit { putString(KEY_SESSION_ID, id) }
        return id
    }
}
