package `in`.singhangad.eventtracker.internal.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EventDatabaseTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        EventDatabase.clearTestInstance()
    }

    @After
    fun teardown() {
        EventDatabase.clearTestInstance()
    }

    @Test
    fun `get returns a usable database with both daos`() {
        val db = EventDatabase.get(context)
        assertNotNull(db.eventDao())
        assertNotNull(db.dlqDao())
    }

    @Test
    fun `get returns the same singleton instance on repeated calls`() {
        val a = EventDatabase.get(context)
        val b = EventDatabase.get(context)
        assertSame(a, b)
    }

    @Test
    fun `setInstanceForTesting overrides the singleton`() {
        val injected = Room.inMemoryDatabaseBuilder(context, EventDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        EventDatabase.setInstanceForTesting(injected)
        assertSame(injected, EventDatabase.get(context))
        injected.close()
    }

    @Test
    fun `clearTestInstance forces a fresh build on next get`() {
        val first = EventDatabase.get(context)
        EventDatabase.clearTestInstance()
        val second = EventDatabase.get(context)
        assertNotSame(first, second)
    }
}
