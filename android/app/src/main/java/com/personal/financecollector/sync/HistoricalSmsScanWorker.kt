package com.personal.financecollector.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.personal.financecollector.ConfigKeys
import com.personal.financecollector.FinanceApp
import com.personal.financecollector.collector.SmsHistoryScanner
import com.personal.financecollector.collector.SmsPermission
import com.personal.financecollector.database.set
import com.personal.financecollector.service.SmsScanState
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

class HistoricalSmsScanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val db = (applicationContext as FinanceApp).container.db
        if (!SmsPermission.isGranted(applicationContext)) return Result.success()

        return try {
            SmsHistoryScanner(applicationContext, db).scan()
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            fail(db, "permission revoked")
            Result.success()
        } catch (e: Exception) {
            // Only the exception type is stored; messages could echo SMS content.
            fail(db, e.javaClass.simpleName)
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    private suspend fun fail(db: com.personal.financecollector.database.FinanceDatabase, reason: String) {
        db.configDao().set(ConfigKeys.SMS_SCAN_STATE, SmsScanState.FAILED.name)
        db.configDao().set(ConfigKeys.SMS_SCAN_ERROR, reason)
    }

    private companion object {
        const val MAX_ATTEMPTS = 5
    }
}

object SmsScanScheduler {
    private const val WORK_NAME = "sms_historical_scan"

    fun enqueue(context: Context, restart: Boolean) {
        val request = OneTimeWorkRequestBuilder<HistoricalSmsScanWorker>()
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME,
            if (restart) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request,
        )
    }
}
