package com.personal.financecollector.collector

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import androidx.core.content.ContextCompat
import com.personal.financecollector.CheckpointKeys
import com.personal.financecollector.ConfigKeys
import com.personal.financecollector.database.FinanceDatabase
import com.personal.financecollector.database.SourceCheckpointEntity
import com.personal.financecollector.database.set
import com.personal.financecollector.service.SmsScanState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime

object SmsPermission {
    fun isGranted(context: Context): Boolean =
        listOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS).all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
}

/** Reads the inbox in id order and checkpoints progress, so an interrupted scan resumes without re-reading. */
class SmsHistoryScanner(private val context: Context, private val db: FinanceDatabase) {
    private val ingestor = SmsIngestor(db)

    /** Returns true when the whole window was scanned; false if collection is paused. */
    suspend fun scan(): Boolean {
        val config = db.configDao()
        val checkpoints = db.checkpointDao()

        val rules = ingestor.loadRules()
        if (rules.paused) {
            config.set(ConfigKeys.SMS_SCAN_STATE, SmsScanState.NOT_STARTED.name)
            return false
        }

        val since = checkpoints.get(CheckpointKeys.SMS_SINCE)?.checkpointValue?.toLongOrNull() ?: run {
            val months = config.get(ConfigKeys.SMS_SCAN_MONTHS)?.toIntOrNull() ?: ConfigKeys.DEFAULT_SMS_SCAN_MONTHS
            ZonedDateTime.now().minusMonths(months.toLong()).toInstant().toEpochMilli()
                .also { putCheckpoint(CheckpointKeys.SMS_SINCE, it.toString()) }
        }
        var lastId = checkpoints.get(CheckpointKeys.SMS_LAST_ID)?.checkpointValue?.toLongOrNull() ?: 0L
        var scanned = config.get(ConfigKeys.SMS_SCAN_SCANNED)?.toIntOrNull() ?: 0
        var captured = config.get(ConfigKeys.SMS_SCAN_CAPTURED)?.toIntOrNull() ?: 0

        config.set(ConfigKeys.SMS_SCAN_STATE, SmsScanState.RUNNING.name)
        config.set(ConfigKeys.SMS_SCAN_ERROR, "")

        suspend fun saveProgress() {
            putCheckpoint(CheckpointKeys.SMS_LAST_ID, lastId.toString())
            config.set(ConfigKeys.SMS_SCAN_SCANNED, scanned.toString())
            config.set(ConfigKeys.SMS_SCAN_CAPTURED, captured.toString())
        }

        withContext(Dispatchers.IO) {
            val projection = arrayOf(
                Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.DATE_SENT,
            )
            val cursor = context.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                projection,
                "${Telephony.Sms.DATE} >= ? AND ${Telephony.Sms._ID} > ?",
                arrayOf(since.toString(), lastId.toString()),
                "${Telephony.Sms._ID} ASC",
            ) ?: throw IllegalStateException("SMS provider returned no cursor")

            cursor.use { c ->
                val idCol = c.getColumnIndexOrThrow(Telephony.Sms._ID)
                val addressCol = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val bodyCol = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val dateCol = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
                val sentCol = c.getColumnIndexOrThrow(Telephony.Sms.DATE_SENT)
                var sinceSave = 0

                while (c.moveToNext()) {
                    ensureActive()
                    val id = c.getLong(idCol)
                    val sent = c.getLong(sentCol)
                    val timestamp = if (sent > 0) sent else c.getLong(dateCol)
                    val result = ingestor.ingest(rules, c.getString(addressCol), c.getString(bodyCol).orEmpty(), timestamp)
                    scanned++
                    if (result == SmsIngestResult.CAPTURED) captured++
                    lastId = id
                    if (++sinceSave >= BATCH_SIZE) {
                        saveProgress()
                        sinceSave = 0
                    }
                }
            }
        }

        saveProgress()
        config.set(ConfigKeys.SMS_SCAN_STATE, SmsScanState.COMPLETE.name)
        return true
    }

    private suspend fun putCheckpoint(key: String, value: String) =
        db.checkpointDao().put(SourceCheckpointEntity(key, value, System.currentTimeMillis()))

    private companion object {
        const val BATCH_SIZE = 100
    }
}
