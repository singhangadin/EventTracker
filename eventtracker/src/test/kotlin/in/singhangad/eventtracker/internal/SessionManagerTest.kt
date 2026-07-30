package `in`.singhangad.eventtracker.internal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SessionManagerTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("eventtracker_session", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun `generates a session id on first construction`() {
        val sm = SessionManager(context)
        assertTrue(sm.sessionId.isNotBlank())
    }

    @Test
    fun `session id is stable across reads`() {
        val sm = SessionManager(context)
        assertEquals(sm.sessionId, sm.sessionId)
    }

    @Test
    fun `newSession rotates the id`() {
        val sm = SessionManager(context)
        val before = sm.sessionId
        sm.newSession()
        assertNotEquals(before, sm.sessionId)
    }

    @Test
    fun `session id persists across manager instances`() {
        val first = SessionManager(context).sessionId
        // A fresh manager reads back the stored session id rather than generating a new one.
        val second = SessionManager(context).sessionId
        assertEquals(first, second)
    }

    @Test
    fun `rotated session id is persisted`() {
        val sm = SessionManager(context)
        sm.newSession()
        val rotated = sm.sessionId
        assertEquals(rotated, SessionManager(context).sessionId)
    }
}
