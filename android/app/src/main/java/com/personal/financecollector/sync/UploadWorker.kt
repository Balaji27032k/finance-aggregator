package com.personal.financecollector.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.personal.financecollector.FinanceApp
import java.util.concurrent.TimeUnit

class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as FinanceApp).container
        val runner = UploadRunner(
            store = RoomUploadStore(container.db.eventDao()),
            api = container.api,
            config = container.db.configDao(),
            credentials = container.credentials,
        )
        try {
            val result = runner.run()
            if (result is RunResult.RetryAt) {
                UploadScheduler.enqueueAfter(applicationContext, (result.atMillis - System.currentTimeMillis()).coerceAtLeast(0))
            }
            return Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Anything unexpected: retry with WorkManager's own backoff; queued events are untouched.
            return if (runAttemptCount < MAX_UNEXPECTED_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    private companion object {
        const val MAX_UNEXPECTED_ATTEMPTS = 5
    }
}

object UploadScheduler {
    private const val NOW = "upload_now"
    private const val DELAYED = "upload_delayed"
    private const val PERIODIC = "upload_periodic"

    private val needsNetwork = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Runs as soon as a connection is available. WorkManager is not an exact scheduler, so no time is promised. */
    fun enqueueNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<UploadWorker>().setConstraints(needsNetwork).build()
        // Appends if a run is in progress, so events captured during that run are not missed.
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    fun enqueueAfter(context: Context, delayMillis: Long) {
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(needsNetwork)
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(DELAYED, ExistingWorkPolicy.REPLACE, request)
    }

    /** Safety net: uploads anything missed (for example after a reboot), roughly every 15 minutes when online. */
    fun ensurePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<UploadWorker>(15, TimeUnit.MINUTES).setConstraints(needsNetwork).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
