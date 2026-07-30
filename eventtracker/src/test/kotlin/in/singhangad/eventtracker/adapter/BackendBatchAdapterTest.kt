package `in`.singhangad.eventtracker.adapter

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import `in`.singhangad.eventtracker.TrackEvent
import `in`.singhangad.eventtracker.internal.NoOpLogger
import `in`.singhangad.eventtracker.internal.db.EventDatabase
import `in`.singhangad.eventtracker.internal.db.EventEntity
import `in`.singhangad.eventtracker.internal.db.EventState
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BackendBatchAdapterTest {

    private lateinit var context: Context
    private lateinit var db: EventDatabase
    private lateinit var server: MockWebServer

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, EventDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        EventDatabase.setInstanceForTesting(db)
        server = MockWebServer()
        server.start()
    }

    @After
    fun teardown() {
        server.shutdown()
        db.close()
        EventDatabase.clearTestInstance()
    }

    // ---- helpers ----

    private fun newAdapter(
        authToken: String? = null,
        batchSize: Int = 50,
        maxRetries: Int = 8,
    ): BackendBatchAdapter {
        val adapter = BackendBatchAdapter(server.url("/events").toString(), authToken)
        adapter.initialize(context, NoOpLogger)
        adapter.configure(batchSize, maxRetries)
        return adapter
    }

    private fun seed(
        id: String,
        attemptCount: Int = 0,
        payloadJson: String = "{}",
        createdAt: Long = 1_000L,
    ) = runBlocking {
        db.eventDao().insert(
            EventEntity(
                id = id,
                name = "evt_$id",
                payloadJson = payloadJson,
                userId = "user-$id",
                sessionId = "sess",
                clientTs = createdAt,
                clientUptimeMs = 10L,
                schemaVersion = 1,
                destinationsCsv = null,
                state = EventState.QUEUED,
                attemptCount = attemptCount,
                nextAttemptAt = 0L,
                lastError = null,
                createdAt = createdAt,
            )
        )
    }

    private fun eventCount(): Long = runBlocking { db.eventDao().count() }
    private fun dlqCount(): Long = runBlocking { db.dlqDao().count() }
    private fun sampleEvent(destinations: Set<String>? = null) = TrackEvent(
        id = "1", name = "e", properties = emptyMap(), userId = null,
        sessionId = "s", clientTimestamp = 0L, clientUptimeMs = 0L, destinations = destinations,
    )

    // ---- accepts / deliver / id ----

    @Test
    fun `id is backend`() {
        assertEquals("backend", newAdapter().id)
    }

    @Test
    fun `accepts honours the destinations whitelist`() {
        val adapter = newAdapter()
        assertTrue(adapter.accepts(sampleEvent(destinations = null)))
        assertTrue(adapter.accepts(sampleEvent(destinations = setOf("backend", "firebase"))))
        assertTrue(!adapter.accepts(sampleEvent(destinations = setOf("firebase"))))
    }

    @Test
    fun `deliver is a no-op returning Success`() = runBlocking {
        assertEquals(DeliveryOutcome.Success, newAdapter().deliver(sampleEvent()))
    }

    // ---- flush: empty queue ----

    @Test
    fun `flush with empty queue makes no request and returns Success`() = runBlocking {
        val outcome = newAdapter().flush()
        assertEquals(DeliveryOutcome.Success, outcome)
        assertEquals(0, server.requestCount)
    }

    // ---- flush: 2xx success ----

    @Test
    fun `flush 200 deletes delivered events`() = runBlocking {
        seed("a"); seed("b")
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val outcome = newAdapter().flush()

        assertTrue(outcome is DeliveryOutcome.Success)
        assertEquals(0L, eventCount())
        assertEquals(0L, dlqCount())
    }

    @Test
    fun `flush 202 is treated as success`() = runBlocking {
        seed("a")
        server.enqueue(MockResponse().setResponseCode(202))
        val outcome = newAdapter().flush()
        assertTrue(outcome is DeliveryOutcome.Success)
        assertEquals(0L, eventCount())
    }

    // ---- flush: request shape ----

    @Test
    fun `request carries batch headers and a well-formed JSON body`() = runBlocking {
        seed("a", payloadJson = """{"screen":"home"}""")
        server.enqueue(MockResponse().setResponseCode(200))

        newAdapter().flush()

        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertTrue(req.getHeader("X-Eventtracker-Sdk")!!.startsWith("android/"))
        assertTrue(!req.getHeader("X-Eventtracker-Batch-Id").isNullOrBlank())
        assertNull(req.getHeader("Content-Encoding")) // small body: not gzipped

        val body = JSONObject(req.body.readUtf8())
        assertEquals(1, body.getInt("schema_version"))
        assertTrue(body.has("sent_at"))
        assertTrue(body.has("device"))
        val events = body.getJSONArray("events")
        assertEquals(1, events.length())
        val e0 = events.getJSONObject(0)
        assertEquals("a", e0.getString("id"))
        assertEquals("evt_a", e0.getString("name"))
        assertEquals("user-a", e0.getString("user_id"))
        assertEquals("home", e0.getJSONObject("properties").getString("screen"))
    }

    @Test
    fun `authToken is sent as a Bearer Authorization header`() = runBlocking {
        seed("a")
        server.enqueue(MockResponse().setResponseCode(200))
        newAdapter(authToken = "secret").flush()
        assertEquals("Bearer secret", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `large payloads are gzip-compressed`() = runBlocking {
        // >1 KB of JSON forces gzip.
        val big = "x".repeat(2_000)
        seed("a", payloadJson = """{"blob":"$big"}""")
        server.enqueue(MockResponse().setResponseCode(200))

        newAdapter().flush()

        assertEquals("gzip", server.takeRequest().getHeader("Content-Encoding"))
    }

    // ---- flush: 207 partial ----

    @Test
    fun `flush 207 deletes succeeded and reschedules failed`() = runBlocking {
        seed("ok"); seed("bad")
        server.enqueue(
            // Retry-After pins the failed row's next attempt far in the future, so the drain
            // loop deterministically stops after one request instead of possibly re-picking it.
            MockResponse().setResponseCode(207)
                .addHeader("Retry-After", "3600")
                .setBody("""{"failed":["bad"]}""")
        )

        val outcome = newAdapter().flush()

        assertTrue(outcome is DeliveryOutcome.Success)
        // "ok" deleted, "bad" rescheduled (still present, not dead-lettered).
        assertEquals(1L, eventCount())
        assertEquals(0L, dlqCount())
        val remaining = db.eventDao().nextBatch(Long.MAX_VALUE, 10).single()
        assertEquals("bad", remaining.id)
        assertEquals(1, remaining.attemptCount)
    }

    @Test
    fun `flush 207 with empty failed list deletes everything`() = runBlocking {
        seed("a"); seed("b")
        server.enqueue(MockResponse().setResponseCode(207).setBody("""{"failed":[]}"""))
        newAdapter().flush()
        assertEquals(0L, eventCount())
    }

    // ---- flush: 400 permanent ----

    @Test
    fun `flush 400 moves the batch straight to the DLQ`() = runBlocking {
        seed("a"); seed("b")
        server.enqueue(MockResponse().setResponseCode(400).setBody("bad request"))

        val outcome = newAdapter().flush()

        assertTrue(outcome is DeliveryOutcome.PermanentFailure)
        assertEquals(0L, eventCount())
        assertEquals(2L, dlqCount())
    }

    // ---- flush: 401 / 403 auth halt ----

    @Test
    fun `flush 401 halts the sender leaving events queued`() = runBlocking {
        seed("a")
        server.enqueue(MockResponse().setResponseCode(401))

        val outcome = newAdapter().flush()

        assertTrue(outcome is DeliveryOutcome.PermanentFailure)
        assertEquals(1L, eventCount()) // not deleted, not dead-lettered
        assertEquals(0L, dlqCount())
    }

    @Test
    fun `flush 403 is handled like 401`() = runBlocking {
        seed("a")
        server.enqueue(MockResponse().setResponseCode(403))
        val outcome = newAdapter().flush()
        assertTrue(outcome is DeliveryOutcome.PermanentFailure)
        assertEquals(1L, eventCount())
    }

    // ---- flush: 429 throttle ----

    @Test
    fun `flush 429 reschedules and honours Retry-After`() = runBlocking {
        seed("a")
        server.enqueue(
            MockResponse().setResponseCode(429).addHeader("Retry-After", "120")
        )

        val outcome = newAdapter().flush()

        assertTrue(outcome is DeliveryOutcome.RetryableFailure)
        assertEquals(120_000L, (outcome as DeliveryOutcome.RetryableFailure).retryAfterMs)
        assertEquals(1L, eventCount()) // rescheduled, still present
        assertEquals(0L, dlqCount())
    }

    // ---- flush: 5xx / 408 retryable ----

    @Test
    fun `flush 503 reschedules for retry`() = runBlocking {
        seed("a")
        server.enqueue(MockResponse().setResponseCode(503))
        val outcome = newAdapter().flush()
        assertTrue(outcome is DeliveryOutcome.RetryableFailure)
        assertEquals(1L, eventCount())
        val row = db.eventDao().nextBatch(Long.MAX_VALUE, 10).single()
        assertEquals(1, row.attemptCount)
    }

    @Test
    fun `flush 408 request timeout is retryable`() = runBlocking {
        seed("a")
        server.enqueue(MockResponse().setResponseCode(408))
        val outcome = newAdapter().flush()
        assertTrue(outcome is DeliveryOutcome.RetryableFailure)
        assertEquals(1L, eventCount())
    }

    // ---- flush: unexpected status ----

    @Test
    fun `flush unexpected status moves to DLQ as permanent`() = runBlocking {
        seed("a")
        server.enqueue(MockResponse().setResponseCode(418)) // I'm a teapot
        val outcome = newAdapter().flush()
        assertTrue(outcome is DeliveryOutcome.PermanentFailure)
        assertEquals(0L, eventCount())
        assertEquals(1L, dlqCount())
    }

    // ---- flush: network error ----

    @Test
    fun `flush network error reschedules for retry`() = runBlocking {
        seed("a")
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val outcome = newAdapter().flush()
        assertTrue(outcome is DeliveryOutcome.RetryableFailure)
        assertEquals(1L, eventCount())
        assertEquals(0L, dlqCount())
    }

    // ---- flush: exhausted retries -> DLQ ----

    @Test
    fun `retryable failure at the retry ceiling dead-letters the event`() = runBlocking {
        // maxRetries = 1 -> attemptCount(0)+1 >= 1 -> exhausted on first failure.
        seed("a", attemptCount = 0)
        server.enqueue(MockResponse().setResponseCode(503))

        val outcome = newAdapter(maxRetries = 1).flush()

        assertTrue(outcome is DeliveryOutcome.RetryableFailure)
        assertEquals(0L, eventCount())
        assertEquals(1L, dlqCount())
    }

    @Test
    fun `mixed batch splits exhausted to DLQ and retryable back to queue`() = runBlocking {
        // maxRetries = 3: attemptCount 2 -> exhausted; attemptCount 0 -> retryable.
        seed("old", attemptCount = 2, createdAt = 1L)
        seed("young", attemptCount = 0, createdAt = 2L)
        server.enqueue(MockResponse().setResponseCode(500))

        newAdapter(maxRetries = 3).flush()

        assertEquals(1L, dlqCount())     // "old"
        assertEquals(1L, eventCount())   // "young" rescheduled
        assertEquals("young", db.eventDao().nextBatch(Long.MAX_VALUE, 10).single().id)
    }

    // ---- Retry-After parsing (seconds + HTTP-date forms) ----

    @Test
    fun `429 with an HTTP-date Retry-After yields a positive delay`() = runBlocking {
        seed("a")
        val fmt = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("GMT") }
        val oneHourAhead = fmt.format(java.util.Date(System.currentTimeMillis() + 3_600_000L))
        server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", oneHourAhead))

        val outcome = newAdapter().flush()

        val retryAfter = (outcome as DeliveryOutcome.RetryableFailure).retryAfterMs!!
        // ~1h in the future, allowing for clock/parse slack.
        assertTrue("retryAfter=$retryAfter", retryAfter in 3_000_000L..3_600_000L)
    }

    @Test
    fun `429 with an unparseable Retry-After falls back to computed backoff`() = runBlocking {
        seed("a")
        server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "soon-ish"))
        val outcome = newAdapter().flush()
        assertTrue(outcome is DeliveryOutcome.RetryableFailure)
        assertNull((outcome as DeliveryOutcome.RetryableFailure).retryAfterMs)
        assertEquals(1L, eventCount()) // still rescheduled via jittered backoff
    }

    // ---- malformed JSON tolerance ----

    @Test
    fun `event with a non-JSON payload is still sent successfully`() = runBlocking {
        // safeParseJson must swallow the parse error and emit an empty properties object.
        seed("a", payloadJson = "this is not json")
        server.enqueue(MockResponse().setResponseCode(200))
        val outcome = newAdapter().flush()
        assertTrue(outcome is DeliveryOutcome.Success)
        assertEquals(0L, eventCount())
    }

    @Test
    fun `207 with a non-JSON body treats the whole batch as delivered`() = runBlocking {
        seed("a"); seed("b")
        server.enqueue(MockResponse().setResponseCode(207).setBody("not-json"))
        newAdapter().flush()
        // parseFailedIds returns empty -> everything deleted.
        assertEquals(0L, eventCount())
    }

    // ---- effectiveBatchSize behaviour across flushes ----

    @Test
    fun `batch size halves after 429 then resets after a later success`() = runBlocking {
        // First flush: 429 halves effectiveBatchSize from 4 to 2.
        seed("a", createdAt = 1L)
        server.enqueue(MockResponse().setResponseCode(429))
        val adapter = newAdapter(batchSize = 4)
        adapter.flush()
        // Event was rescheduled into the future; drop it and start clean for the success flush.
        db.eventDao().deleteByIds(listOf("a"))

        // Seed 3 fresh events. With halved size (2) the drain needs two requests.
        seed("b", createdAt = 10L); seed("c", createdAt = 11L); seed("d", createdAt = 12L)
        server.enqueue(MockResponse().setResponseCode(200)) // first sub-batch of 2
        server.enqueue(MockResponse().setResponseCode(200)) // remaining 1, size reset to 4

        val outcome = adapter.flush()
        assertTrue(outcome is DeliveryOutcome.Success)
        assertEquals(0L, eventCount())
    }
}
