package `in`.singhangad.eventtracker.internal

import android.content.Context
import androidx.core.content.edit

private const val PREFS_NAME = "eventtracker_prefs"
private const val KEY_OPT_OUT = "opt_out"

/**
 * Persists the opt-out flag to SharedPreferences so it survives process death.
 * Reads are served from an in-memory cache after the first load.
 */
internal class OptOutGuard(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Volatile
    private var optedOut: Boolean = prefs.getBoolean(KEY_OPT_OUT, false)

    val isOptedOut: Boolean get() = optedOut

    fun setOptOut(value: Boolean) {
        optedOut = value
        prefs.edit { putBoolean(KEY_OPT_OUT, value) }
    }
}
