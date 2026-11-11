package com.personal.financecollector

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.personal.financecollector.database.FinanceDatabase
import com.personal.financecollector.database.SourceType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object ConfigKeys {
    const val COLLECTION_PAUSED = "collection_paused"
    const val SMS_SCAN_MONTHS = "sms_scan_months"
    const val SMS_SCAN_STATE = "sms_scan_state"
    const val SMS_SCAN_SCANNED = "sms_scan_scanned"
    const val SMS_SCAN_CAPTURED = "sms_scan_captured"
    const val SMS_SCAN_ERROR = "sms_scan_error"
    const val BACKEND_URL = "backend_url"
    const val PAIRED = "paired"
    const val DEVICE_ID = "device_id"
    const val DEVICE_TOKEN_ENC = "device_token_enc"
    const val SYNC_LAST_OK_AT = "sync_last_ok_at"
    const val SYNC_LAST_ATTEMPT_AT = "sync_last_attempt_at"
    const val SYNC_LAST_ERROR = "sync_last_error"
    const val GMAIL_CONNECTED = "gmail_connected"
    val SMS_SCAN_KEYS = listOf(SMS_SCAN_STATE, SMS_SCAN_SCANNED, SMS_SCAN_CAPTURED, SMS_SCAN_ERROR)
    const val DEFAULT_SMS_SCAN_MONTHS = 12
}

object StatusKeys {
    const val NOTIFICATION_LISTENER = "NOTIFICATION_LISTENER"
    const val SMS = "SMS"
}

object CheckpointKeys {
    const val SMS_LAST_ID = "SMS_INBOX_LAST_ID"
    const val SMS_SINCE = "SMS_SCAN_SINCE"
}

/** Sender entity codes taken from real alert headers (e.g. VA-CSBBNK-T, VM-SBIUPI-S). */
object DefaultSmsSenders {
    val all = listOf(
        "CSBBNK" to "CSB Bank",
        "INDBNK" to "Indian Bank",
        "SBIUPI" to "SBI (UPI)",
        "SBIPSG" to "SBI",
        "ATMSBI" to "SBI (ATM and card)",
        "CBSSBI" to "SBI (core banking)",
        "SBYONO" to "SBI YONO",
    )
}

object KnownApps {
    const val GOOGLE_PAY = "com.google.android.apps.nbu.paisa.user"
}

class AppContainer(context: Context) {
    val db: FinanceDatabase =
        Room.databaseBuilder(context, FinanceDatabase::class.java, FinanceDatabase.NAME).build()
    val http: okhttp3.OkHttpClient = okhttp3.OkHttpClient.Builder()
        .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        // Render's free tier needs up to ~90s to wake from idle.
        .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    val api = com.personal.financecollector.sync.BackendApi(http)
    val credentials = com.personal.financecollector.security.SecureCredentialStore(db.configDao())
}

class FinanceApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        com.personal.financecollector.sync.UploadScheduler.ensurePeriodic(this)
        // Only package names that are certain are seeded; other banks are chosen in Manage Sources.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            container.db.sourceDao().insertIgnore(
                com.personal.financecollector.database.ConnectedSourceEntity(
                    sourceType = SourceType.NOTIFICATION,
                    identifier = KnownApps.GOOGLE_PAY,
                    displayName = "Google Pay",
                    enabled = true,
                    addedAt = System.currentTimeMillis(),
                ),
            )
            DefaultSmsSenders.all.forEach { (code, name) ->
                container.db.sourceDao().insertIgnore(
                    com.personal.financecollector.database.ConnectedSourceEntity(
                        sourceType = SourceType.SMS,
                        identifier = code,
                        displayName = name,
                        enabled = true,
                        addedAt = System.currentTimeMillis(),
                    ),
                )
            }
        }
    }
}
