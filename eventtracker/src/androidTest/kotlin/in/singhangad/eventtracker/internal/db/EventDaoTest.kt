package `in`.singhangad.eventtracker.internal.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EventDaoTest {

    private lateinit var db: EventDatabase
    private lateinit var dao: EventDao

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            EventDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.eventDao()
    }

    @After
    fun teardown() {
        db.close()
    }

    // ---- helpers ---

    private fun event(
        id: String,
        state: String = EventState.QUEUED,
        nextAttemptAt: Long = 0L,
        attemptCount: Int = 0,
        createdAt: Long = 1_000L,
    ) = EventEntity(
        id = id,
        name = "test_event",
        payloadJson = "{}",
        userId = null,
        sessionId = "s1",
        clientTs = createdAt,
        clientUptimeMs = 0L,
        schemaVersion = 1,
        destinationsCsv = null,
        state = state,
        attemptCount = attemptCount,
        nextAttemptAt = nextAttemptAt,
        lastError = null,
        createdAt = createdAt,
    )

    // ---- tests ---

    @Test
    fun insert_and_count() = runBlocking {
        dao.insert(event("e1"))
        dao.insert(event("e2"))
        assertEquals(2L, dao.count())
    }

    @Test
    fun nextBatch_returns_queued_events_whose_next_attempt_at_is_in_the_past() = runBlocking {
        dao.insert(event("past", nextAttemptAt = 500L))   // eligible
        dao.insert(event("future", nextAttemptAt = 9_999L)) // not yet eligible
        val batch = dao.nextBatch(now = 1_000L, limit = 10)
        assertEquals(1, batch.size)
        assertEquals("past", batch[0].id)
    }

    @Test
    fun nextBatch_respects_limit() = runBlocking {
        repeat(5) { i -> dao.insert(event("e$i", createdAt = i.toLong())) }
        val batch = dao.nextBatch(now = Long.MAX_VALUE, limit = 3)
        assertEquals(3, batch.size)
    }

    @Test
    fun nextBatch_returns_oldest_first() = runBlocking {
        dao.insert(event("newer", createdAt = 200L))
        dao.insert(event("older", createdAt = 100L))
        val batch = dao.nextBatch(now = Long.MAX_VALUE, limit = 2)
        assertEquals("older", batch[0].id)
        assertEquals("newer", batch[1].id)
    }

    @Test
    fun nextBatch_excludes_sending_state() = runBlocking {
        dao.insert(event("queued", state = EventState.QUEUED))
        dao.insert(event("sending", state = EventState.SENDING))
        val batch = dao.nextBatch(now = Long.MAX_VALUE, limit = 10)
        assertEquals(1, batch.size)
        assertEquals("queued", batch[0].id)
    }

    @Test
    fun markSending_changes_state_to_SENDING() = runBlocking {
        dao.insert(event("e1"))
        dao.insert(event("e2"))
        dao.markSending(listOf("e1"))
        val batch = dao.nextBatch(now = Long.MAX_VALUE, limit = 10)
        // Only e2 should be QUEUED now
        assertEquals(1, batch.size)
        assertEquals("e2", batch[0].id)
    }

    @Test
    fun resetSendingToQueued_makes_all_SENDING_rows_retryable() = runBlocking {
        dao.insert(event("e1", state = EventState.SENDING))
        dao.insert(event("e2", state = EventState.SENDING))
        dao.insert(event("e3", state = EventState.QUEUED))
        dao.resetSendingToQueued()
        val batch = dao.nextBatch(now = Long.MAX_VALUE, limit = 10)
        assertEquals(3, batch.size)
    }

    @Test
    fun deleteByIds_removes_only_specified_rows() = runBlocking {
        dao.insert(event("keep"))
        dao.insert(event("delete"))
        dao.deleteByIds(listOf("delete"))
        assertEquals(1L, dao.count())
        val batch = dao.nextBatch(now = Long.MAX_VALUE, limit = 10)
        assertEquals("keep", batch[0].id)
    }

    @Test
    fun rescheduleFailed_increments_attemptCount_and_sets_nextAttemptAt() = runBlocking {
        dao.insert(event("e1", attemptCount = 2))
        dao.rescheduleFailed(listOf("e1"), nextAt = 5_000L, err = "timeout")
        val row = dao.nextBatch(now = Long.MAX_VALUE, limit = 1).first()
        assertEquals(3, row.attemptCount)
        assertEquals(5_000L, row.nextAttemptAt)
        assertEquals("timeout", row.lastError)
    }

    @Test
    fun queueDepth_counts_all_rows() = runBlocking {
        repeat(4) { i -> dao.insert(event("e$i")) }
        assertEquals(4L, dao.queueDepth())
    }

    @Test
    fun trimOldest_removes_n_oldest_rows() = runBlocking {
        dao.insert(event("old1", createdAt = 100L))
        dao.insert(event("old2", createdAt = 200L))
        dao.insert(event("new1", createdAt = 300L))
        dao.trimOldest(2)
        assertEquals(1L, dao.count())
        val remaining = dao.nextBatch(now = Long.MAX_VALUE, limit = 10)
        assertEquals("new1", remaining[0].id)
    }
}
