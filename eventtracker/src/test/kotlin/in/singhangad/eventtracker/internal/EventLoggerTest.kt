package `in`.singhangad.eventtracker.internal

import android.util.Log
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
class EventLoggerTest {

    @Before
    fun setup() {
        ShadowLog.clear()
    }

    @Test
    fun `NoOpLogger swallows every level without throwing`() {
        val logger: EventLogger = NoOpLogger
        logger.verbose("t", "v")
        logger.debug("t", "d")
        logger.info("t", "i")
        logger.warn("t", "w")
        logger.error("t", "e")
        logger.error("t", "e", RuntimeException("x"))
        // NoOpLogger must not emit anything to Logcat.
        assertTrue(ShadowLog.getLogs().isEmpty())
    }

    @Test
    fun `AndroidLogger forwards each level to Logcat with correct priority`() {
        val logger = AndroidLogger()
        logger.verbose("TAG", "verbose-msg")
        logger.debug("TAG", "debug-msg")
        logger.info("TAG", "info-msg")
        logger.warn("TAG", "warn-msg")
        logger.error("TAG", "error-msg")

        val logs = ShadowLog.getLogsForTag("TAG")
        assertEquals(5, logs.size)
        assertEquals(Log.VERBOSE, logs[0].type)
        assertEquals("verbose-msg", logs[0].msg)
        assertEquals(Log.DEBUG, logs[1].type)
        assertEquals(Log.INFO, logs[2].type)
        assertEquals(Log.WARN, logs[3].type)
        assertEquals(Log.ERROR, logs[4].type)
    }

    @Test
    fun `AndroidLogger error forwards the throwable`() {
        val logger = AndroidLogger()
        val boom = IllegalStateException("kaboom")
        logger.error("TAG", "with-throwable", boom)

        val logs = ShadowLog.getLogsForTag("TAG")
        assertEquals(1, logs.size)
        assertEquals(Log.ERROR, logs[0].type)
        assertEquals(boom, logs[0].throwable)
    }
}
