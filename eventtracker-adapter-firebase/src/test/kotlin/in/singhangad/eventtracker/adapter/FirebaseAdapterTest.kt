package `in`.singhangad.eventtracker.adapter

import android.content.Context
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import `in`.singhangad.eventtracker.TrackEvent
import `in`.singhangad.eventtracker.internal.EventLogger
import com.google.firebase.analytics.FirebaseAnalytics
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockedStatic
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FirebaseAdapterTest {

    private lateinit var context: Context
    private lateinit var mockedStatic: MockedStatic<FirebaseAnalytics>
    private lateinit var analytics: FirebaseAnalytics
    private lateinit var adapter: FirebaseAdapter

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        analytics = mock()
        mockedStatic = Mockito.mockStatic(FirebaseAnalytics::class.java)
        mockedStatic.`when`<FirebaseAnalytics> { FirebaseAnalytics.getInstance(context) }
            .thenReturn(analytics)

        adapter = FirebaseAdapter()
        adapter.initialize(context, TestLogger) // exercises FirebaseAnalytics.getInstance
    }

    /** Local no-op logger: the SDK's NoOpLogger is `internal` to the core module. */
    private object TestLogger : EventLogger {
        override fun verbose(tag: String, message: String) {}
        override fun debug(tag: String, message: String) {}
        override fun info(tag: String, message: String) {}
        override fun warn(tag: String, message: String) {}
        override fun error(tag: String, message: String, throwable: Throwable?) {}
    }

    @After
    fun teardown() {
        mockedStatic.close()
    }

    private fun event(name: String = "screen_view", props: Map<String, Any?> = emptyMap()) =
        TrackEvent(
            id = "1", name = name, properties = props, userId = "u",
            sessionId = "s", clientTimestamp = 0L, clientUptimeMs = 0L,
        )

    @Test
    fun `id is firebase`() {
        assertEquals("firebase", adapter.id)
    }

    @Test
    fun `accepts honours the destinations whitelist`() {
        assertTrue(adapter.accepts(event().copy(destinations = null)))
        assertTrue(adapter.accepts(event().copy(destinations = setOf("firebase"))))
        assertFalse(adapter.accepts(event().copy(destinations = setOf("backend"))))
    }

    @Test
    fun `deliver logs the event and returns Success`() = runBlocking {
        val outcome = adapter.deliver(event(name = "purchase"))
        assertEquals(DeliveryOutcome.Success, outcome)
        verify(analytics).logEvent(eq("purchase"), any())
    }

    @Test
    fun `deliver sanitises the event name`() = runBlocking {
        adapter.deliver(event(name = "weird name!"))
        // Non-alphanumerics become underscores.
        verify(analytics).logEvent(eq("weird_name_"), any())
    }

    @Test
    fun `deliver returns RetryableFailure when logEvent throws`() = runBlocking {
        whenever(analytics.logEvent(any(), any())).thenThrow(RuntimeException("boom"))
        val outcome = adapter.deliver(event())
        assertTrue(outcome is DeliveryOutcome.RetryableFailure)
    }

    @Test
    fun `buildBundle maps every supported property type`() = runBlocking {
        val longString = "s".repeat(200)
        adapter.deliver(
            event(
                props = mapOf(
                    "str" to longString,
                    "int" to 7,
                    "long" to 99L,
                    "double" to 3.5,
                    "float" to 1.5f,
                    "bool_true" to true,
                    "bool_false" to false,
                    "other" to listOf(1, 2, 3),
                )
            )
        )

        val captor = argumentCaptor<Bundle>()
        verify(analytics).logEvent(any(), captor.capture())
        val b = captor.firstValue

        assertEquals(100, b.getString("str")!!.length) // truncated to 100
        assertEquals(7, b.getInt("int"))
        assertEquals(99L, b.getLong("long"))
        assertEquals(3.5, b.getDouble("double"), 0.0001)
        assertEquals(1.5f, b.getFloat("float"), 0.0001f)
        assertEquals(1, b.getInt("bool_true"))
        assertEquals(0, b.getInt("bool_false"))
        assertTrue(b.getString("other")!!.contains("1")) // list -> toString
    }

    @Test
    fun `buildBundle truncates long property keys to 40 chars`() = runBlocking {
        val longKey = "k".repeat(60)
        adapter.deliver(event(props = mapOf(longKey to "v")))
        val captor = argumentCaptor<Bundle>()
        verify(analytics).logEvent(any(), captor.capture())
        assertTrue(captor.firstValue.containsKey("k".repeat(40)))
        assertFalse(captor.firstValue.containsKey(longKey))
    }

    @Test
    fun `identify forwards user id and traits`() = runBlocking {
        adapter.identify("user-42", mapOf("plan" to "pro"))
        verify(analytics).setUserId("user-42")
        verify(analytics).setUserProperty(eq("plan"), eq("pro"))
    }

    @Test
    fun `identify truncates trait keys and values`() = runBlocking {
        val longKey = "k".repeat(40)
        val longVal = "v".repeat(60)
        adapter.identify("u", mapOf(longKey to longVal))
        verify(analytics).setUserProperty(eq("k".repeat(24)), eq("v".repeat(36)))
    }

    @Test
    fun `identify swallows exceptions from the Firebase SDK`() = runBlocking {
        whenever(analytics.setUserId("boom")).thenThrow(RuntimeException("firebase down"))
        // Must not propagate.
        adapter.identify("boom", emptyMap())
    }
}
