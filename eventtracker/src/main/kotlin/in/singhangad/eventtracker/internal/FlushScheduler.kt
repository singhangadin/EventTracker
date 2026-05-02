package `in`.singhangad.eventtracker.internal

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import `in`.singhangad.eventtracker.EventTracker
import java.util.concurrent.TimeUnit

/**
 * Wires up the three flush triggers defined in the design:
 *
 * 1. **Periodic worker** — runs every `batchIntervalMs / 2` with CONNECTED + BATTERY_NOT_LOW
 *    constraints. Provides the baseline "flush every N seconds" guarantee.
 *
 * 2. **App foreground** — schedules a one-shot worker when the process comes to the foreground
 *    (via [ProcessLifecycleOwner]). Ensures events tracked just before the user closes the app
 *    are sent as soon as connectivity is available on the next open.
 *
 * 3. **Connectivity available** — schedules a one-shot worker when the device regains network
 *    access, draining any events queued during an offline period.
 */
internal class FlushScheduler(
    private val context: Context,
    private val batchIntervalMs: Long,
    private val logger: EventLogger,
) : DefaultLifecycleObserver {

    private val workManager: WorkManager = WorkManager.getInstance(context)

    fun start() {
        schedulePeriodicWorker()
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        registerConnectivityCallback()
    }

    fun stop() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(this)
        workManager.cancelUniqueWork(FlushWorker.WORK_NAME_PERIODIC)
    }

    // ---- Lifecycle observer -----------------------------------------------------------------

    override fun onStart(owner: LifecycleOwner) {
        // App came to foreground: schedule an immediate one-shot flush
        enqueueOneshotFlush()
        EventTracker.newSessionInternal()
        logger.debug("ET/Scheduler", "App foregrounded — one-shot flush enqueued")
    }

    override fun onStop(owner: LifecycleOwner) {
        logger.debug("ET/Scheduler", "App backgrounded")
    }

    // ---- Worker scheduling ------------------------------------------------------------------

    private fun schedulePeriodicWorker() {
        val intervalMs = maxOf(batchIntervalMs / 2, 15 * 60 * 1000L) // WorkManager min: 15 min
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

        val request = PeriodicWorkRequestBuilder<FlushWorker>(intervalMs, TimeUnit.MILLISECONDS)
            .setConstraints(constraints)
            .build()

        workManager.enqueueUniquePeriodicWork(
            FlushWorker.WORK_NAME_PERIODIC,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
        logger.debug("ET/Scheduler", "Periodic flush worker scheduled every ${intervalMs}ms")
    }

    private fun enqueueOneshotFlush() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<FlushWorker>()
            .setConstraints(constraints)
            .build()
        workManager.enqueueUniqueWork(
            FlushWorker.WORK_NAME_ONESHOT,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    // ---- Connectivity callback --------------------------------------------------------------

    private fun registerConnectivityCallback() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                logger.debug("ET/Scheduler", "Network available — one-shot flush enqueued")
                enqueueOneshotFlush()
            }
        })
    }
}
