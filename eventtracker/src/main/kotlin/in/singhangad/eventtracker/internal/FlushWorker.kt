package `in`.singhangad.eventtracker.internal

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import `in`.singhangad.eventtracker.EventTracker

/**
 * WorkManager [CoroutineWorker] that triggers a flush of all backend-queued events.
 *
 * Scheduled by [FlushScheduler] as both a periodic worker (every `batchIntervalMs / 2`) and a
 * one-shot worker on connectivity-available and app-foreground events. Process death between the
 * schedule and the execution is safe: WorkManager re-enqueues on restart.
 */
internal class FlushWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!EventTracker.isInitialized) {
            return Result.success() // SDK not yet initialised in this process; skip silently.
        }
        return try {
            EventTracker.flushInternal()
            Result.success()
        } catch (t: Throwable) {
            // Never crash the WorkManager process; retry on the next scheduled run.
            Result.retry()
        }
    }

    companion object {
        const val WORK_NAME_PERIODIC = "eventtracker_flush_periodic"
        const val WORK_NAME_ONESHOT = "eventtracker_flush_oneshot"
    }
}
