package com.personal.financecollector.export

import android.content.Context
import android.provider.Telephony
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.personal.financecollector.ConfigKeys
import com.personal.financecollector.FinanceApp
import com.personal.financecollector.collector.SmsPermission
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class AndroidSmsReader(private val context: Context) : SmsReader {
    override suspend fun read(afterId: Long, onRow: suspend (SmsRow) -> Unit) {
        val cursor = withContext(Dispatchers.IO) {
            context.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
                "${Telephony.Sms._ID} > ?",
                arrayOf(afterId.toString()),
                "${Telephony.Sms._ID} ASC",
            )
        } ?: throw IllegalStateException("SMS provider returned no cursor")
        cursor.use { c ->
            val id = c.getColumnIndexOrThrow(Telephony.Sms._ID)
            val address = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val body = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val date = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
            while (c.moveToNext()) {
                onRow(SmsRow(c.getLong(id), c.getString(address), c.getString(body).orEmpty(), c.getLong(date)))
            }
        }
    }
}

private suspend fun pairedSink(context: Context, progress: ExportProgress): StagingSink? {
    val container = (context as FinanceApp).container
    val url = container.db.configDao().get(ConfigKeys.BACKEND_URL)
    val token = container.credentials.token()
    if (container.db.configDao().get(ConfigKeys.PAIRED) != "true" || url.isNullOrBlank() || token == null) {
        progress.put(ExportProgress.STATE, ExportState.FAILED)
        progress.put(ExportProgress.ERROR, "Pair this phone with the backend first")
        return null
    }
    return BackendStagingSink(container.http, url, token)
}

class SmsExportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as FinanceApp).container
        val progress = ExportProgress(container.db.configDao(), "sms")
        if (!SmsPermission.isGranted(applicationContext)) {
            progress.put(ExportProgress.STATE, ExportState.FAILED)
            progress.put(ExportProgress.ERROR, "SMS permission is not granted")
            return Result.success()
        }
        val sink = pairedSink(applicationContext, progress) ?: return Result.success()
        return try {
            when (SmsExporter(AndroidSmsReader(applicationContext), progress, sink).run()) {
                is ExportResult.Retry -> Result.retry()
                else -> Result.success()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            progress.put(ExportProgress.STATE, ExportState.FAILED)
            progress.put(ExportProgress.ERROR, e.javaClass.simpleName)
            Result.success()
        }
    }
}

class GmailExportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as FinanceApp).container
        val progress = ExportProgress(container.db.configDao(), "mail")
        val sink = pairedSink(applicationContext, progress) ?: return Result.success()
        val source = GmailRestSource(container.http, { GmailAuth.authorize(applicationContext) })
        return try {
            when (GmailExporter(source, progress, sink).run()) {
                is ExportResult.Retry -> Result.retry()
                else -> Result.success()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            progress.put(ExportProgress.STATE, ExportState.FAILED)
            progress.put(ExportProgress.ERROR, e.javaClass.simpleName)
            Result.success()
        }
    }
}

object ExportScheduler {
    private val needsNetwork = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun enqueueSms(context: Context) = enqueue<SmsExportWorker>(context, "export_sms")

    fun enqueueGmail(context: Context) = enqueue<GmailExportWorker>(context, "export_gmail")

    private inline fun <reified W : CoroutineWorker> enqueue(context: Context, name: String) {
        val request = OneTimeWorkRequestBuilder<W>()
            .setConstraints(needsNetwork)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, request)
    }
}
