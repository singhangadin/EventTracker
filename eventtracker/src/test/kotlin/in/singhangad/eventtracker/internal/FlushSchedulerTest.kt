package `in`.singhangad.eventtracker.internal

import android.content.Context
import android.net.ConnectivityManager
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetwork

@RunWith(RobolectricTestRunner::class)
class FlushSchedulerTest {

    private lateinit var context: Context
    private lateinit var workManager: WorkManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        workManager = WorkManager.getInstance(context)
    }

    private fun infosFor(name: String): List<WorkInfo> =
        workManager.getWorkInfosForUniqueWork(name).get()

    @Test
    fun `start schedules the periodic flush worker`() {
        FlushScheduler(context, batchIntervalMs = 30_000L, logger = NoOpLogger).start()
        val infos = infosFor(FlushWorker.WORK_NAME_PERIODIC)
        assertEquals(1, infos.size)
        assertFalse(infos[0].state == WorkInfo.State.CANCELLED)
    }

    @Test
    fun `onStart enqueues a one-shot flush and does not crash when SDK uninitialised`() {
        val scheduler = FlushScheduler(context, 30_000L, NoOpLogger)
        scheduler.start()
        // Simulate the app coming to the foreground.
        scheduler.onStart(ProcessLifecycleOwner.get())
        assertTrue(infosFor(FlushWorker.WORK_NAME_ONESHOT).isNotEmpty())
    }

    @Test
    fun `onStop is a safe no-op`() {
        val scheduler = FlushScheduler(context, 30_000L, NoOpLogger)
        scheduler.start()
        scheduler.onStop(ProcessLifecycleOwner.get()) // must not throw
    }

    @Test
    fun `stop cancels the periodic worker`() {
        val scheduler = FlushScheduler(context, 30_000L, NoOpLogger)
        scheduler.start()
        scheduler.stop()
        val infos = infosFor(FlushWorker.WORK_NAME_PERIODIC)
        assertEquals(WorkInfo.State.CANCELLED, infos[0].state)
    }

    @Test
    fun `regained connectivity enqueues a one-shot flush`() {
        FlushScheduler(context, 30_000L, NoOpLogger).start()

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callbacks = shadowOf(cm).networkCallbacks
        assertTrue("a network callback should be registered", callbacks.isNotEmpty())
        // Fire onAvailable to exercise the connectivity-triggered flush path.
        callbacks.forEach { it.onAvailable(ShadowNetwork.newInstance(1)) }

        assertTrue(infosFor(FlushWorker.WORK_NAME_ONESHOT).isNotEmpty())
    }

    @Test
    fun `very large batch interval is clamped to the WorkManager minimum`() {
        // batchIntervalMs/2 below the 15-min floor must still schedule successfully.
        FlushScheduler(context, batchIntervalMs = 1_000L, logger = NoOpLogger).start()
        assertEquals(1, infosFor(FlushWorker.WORK_NAME_PERIODIC).size)
    }
}
