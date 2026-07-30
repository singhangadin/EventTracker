package `in`.singhangad.eventtracker.adapter

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import `in`.singhangad.eventtracker.TrackEvent
import `in`.singhangad.eventtracker.internal.NoOpLogger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
class LoggingAdapterTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        ShadowLog.clear()
    }

    private fun setDebuggable(enabled: Boolean) {
        val info = context.applicationInfo
        info.flags = if (enabled) {
            info.flags or ApplicationInfo.FLAG_DEBUGGABLE
        } else {
            info.flags and ApplicationInfo.FLAG_DEBUGGABLE.inv()
        }
    }

    private fun event() = TrackEvent(
        id = "1", name = "screen_view", properties = mapOf("k" to "v"),
        userId = "user-9", sessionId = "abcdefghible", clientTimestamp = 0L, clientUptimeMs = 0L,
    )

    @Test
    fun `id is log`() {
        assertEquals("log", LoggingAdapter().id)
    }

    @Test
    fun `accepts returns true in a debuggable build`() {
        setDebuggable(true)
        val adapter = LoggingAdapter()
        adapter.initialize(context, NoOpLogger)
        assertTrue(adapter.accepts(event()))
    }

    @Test
    fun `accepts returns false in a non-debuggable (release) build`() {
        setDebuggable(false)
        val adapter = LoggingAdapter()
        adapter.initialize(context, NoOpLogger)
        assertFalse(adapter.accepts(event()))
    }

    @Test
    fun `deliver logs the event and returns Success`() = runBlocking {
        setDebuggable(true)
        val adapter = LoggingAdapter()
        adapter.initialize(context, NoOpLogger)

        val outcome = adapter.deliver(event())
        assertEquals(DeliveryOutcome.Success, outcome)

        val logs = ShadowLog.getLogsForTag("ET/Log")
        assertEquals(1, logs.size)
        val line = logs[0].msg
        assertTrue(line.contains("[screen_view]"))
        assertTrue(line.contains("user=user-9"))
        assertTrue(line.contains("session=abcdefgh")) // first 8 chars of sessionId
    }

    @Test
    fun `deliver omits user when userId is null`() = runBlocking {
        setDebuggable(true)
        val adapter = LoggingAdapter()
        adapter.initialize(context, NoOpLogger)

        adapter.deliver(event().copy(userId = null))
        val line = ShadowLog.getLogsForTag("ET/Log")[0].msg
        assertFalse(line.contains("user="))
    }

    @Test
    fun `identify logs the user id and traits`() = runBlocking {
        setDebuggable(true)
        val adapter = LoggingAdapter()
        adapter.initialize(context, NoOpLogger)

        adapter.identify("user-42", mapOf("plan" to "pro"))
        val logs = ShadowLog.getLogsForTag("ET/Log")
        assertEquals(1, logs.size)
        assertTrue(logs[0].msg.contains("user-42"))
        assertTrue(logs[0].msg.contains("plan"))
    }
}
