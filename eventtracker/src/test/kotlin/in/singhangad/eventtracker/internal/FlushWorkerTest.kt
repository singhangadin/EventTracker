package `in`.singhangad.eventtracker.internal

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FlushWorkerTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `doWork returns success when the SDK is not initialised`() {
        // A fresh Robolectric class environment means EventTracker is un-initialised.
        val worker = TestListenableWorkerBuilder<FlushWorker>(context).build()
        val result = runBlocking { worker.doWork() }
        assertEquals(ListenableWorker.Result.success(), result)
    }

    @Test
    fun `work names are stable constants`() {
        assertEquals("eventtracker_flush_periodic", FlushWorker.WORK_NAME_PERIODIC)
        assertEquals("eventtracker_flush_oneshot", FlushWorker.WORK_NAME_ONESHOT)
    }
}
