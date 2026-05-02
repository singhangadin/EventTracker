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
class DLQDaoTest {

    private lateinit var db: EventDatabase
    private lateinit var dao: DLQDao

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            EventDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.dlqDao()
    }

    @After
    fun teardown() {
        db.close()
    }

    // ---- helpers ---

    private fun dlqEntry(id: String, createdAt: Long = 1_000L) = DeadLetterEntity(
        id = id,
        name = "failed_event",
        payloadJson = "{}",
        attemptCount = 8,
        firstFailureAt = createdAt - 1_000,
        lastFailureAt = createdAt,
        lastError = "HTTP 400",
        httpStatus = 400,
        schemaVersion = 1,
        createdAt = createdAt,
    )

    // ---- tests ---

    @Test
    fun insert_and_count() = runBlocking {
        dao.insert(dlqEntry("d1"))
        dao.insert(dlqEntry("d2"))
        assertEquals(2L, dao.count())
    }

    @Test
    fun insertAll_batch_inserts_correctly() = runBlocking {
        val entries = (1..5).map { dlqEntry("d$it") }
        dao.insertAll(entries)
        assertEquals(5L, dao.count())
    }

    @Test
    fun peek_returns_oldest_first_with_limit() = runBlocking {
        dao.insert(dlqEntry("newer", createdAt = 300L))
        dao.insert(dlqEntry("older", createdAt = 100L))
        dao.insert(dlqEntry("middle", createdAt = 200L))
        val result = dao.peek(2)
        assertEquals(2, result.size)
        assertEquals("older", result[0].id)
        assertEquals("middle", result[1].id)
    }

    @Test
    fun deleteByIds_removes_only_specified_entries() = runBlocking {
        dao.insert(dlqEntry("keep"))
        dao.insert(dlqEntry("remove"))
        dao.deleteByIds(listOf("remove"))
        assertEquals(1L, dao.count())
        assertEquals("keep", dao.peek(10).first().id)
    }

    @Test
    fun deleteAll_clears_the_table() = runBlocking {
        dao.insertAll((1..3).map { dlqEntry("d$it") })
        dao.deleteAll()
        assertEquals(0L, dao.count())
    }

    @Test
    fun trimOldest_removes_n_oldest_entries() = runBlocking {
        dao.insert(dlqEntry("old1", createdAt = 100L))
        dao.insert(dlqEntry("old2", createdAt = 200L))
        dao.insert(dlqEntry("new1", createdAt = 300L))
        dao.trimOldest(2)
        assertEquals(1L, dao.count())
        assertEquals("new1", dao.peek(10).first().id)
    }

    @Test
    fun insert_with_replace_conflict_strategy_overwrites_existing() = runBlocking {
        dao.insert(dlqEntry("d1"))
        // Insert same id with different data
        dao.insert(DeadLetterEntity(
            id = "d1",
            name = "updated_event",
            payloadJson = "{}",
            attemptCount = 10,
            firstFailureAt = 0L,
            lastFailureAt = 2_000L,
            lastError = "HTTP 500",
            httpStatus = 500,
            schemaVersion = 1,
            createdAt = 1_000L,
        ))
        assertEquals(1L, dao.count())
        assertEquals("updated_event", dao.peek(1).first().name)
    }
}
