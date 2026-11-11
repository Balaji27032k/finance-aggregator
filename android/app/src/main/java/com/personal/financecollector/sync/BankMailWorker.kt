package com.personal.financecollector.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.personal.financecollector.ConfigKeys
import com.personal.financecollector.FinanceApp
import com.personal.financecollector.collector.BankMailIngestor
import com.personal.financecollector.collector.BankMailResult
import com.personal.financecollector.collector.BankMailStore
import com.personal.financecollector.export.GmailAuth
import com.personal.financecollector.export.GmailRestSource
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/** Checks Gmail for new bank alert e-mails and queues them for upload. */
class BankMailWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as FinanceApp).container
        val config = container.db.configDao()
        if (config.get(ConfigKeys.GMAIL_CONNECTED) != "true") return Result.success()
        val source = GmailRestSource(container.http, { GmailAuth.authorize(applicationContext) })
        val store = BankMailStore { container.db.eventDao().insertWithQueue(it) }
        return try {
            when (val r = BankMailIngestor(source, store, config).run()) {
                is BankMailResult.Done -> {
                    if (r.captured > 0) UploadScheduler.enqueueNow(applicationContext)
                    Result.success()
                }
                BankMailResult.NeedsConnect -> Result.success()
                is BankMailResult.Failed -> Result.retry()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.retry()
        }
    }
}

object BankMailScheduler {
    private const val NOW = "bank_mail_now"
    private const val PERIODIC = "bank_mail_periodic"
    private val needsNetwork = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun enqueueNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<BankMailWorker>()
            .setConstraints(needsNetwork)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, request)
    }

    fun ensurePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<BankMailWorker>(30, TimeUnit.MINUTES).setConstraints(needsNetwork).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
