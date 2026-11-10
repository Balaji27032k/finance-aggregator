package com.personal.financecollector.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.personal.financecollector.CheckpointKeys
import com.personal.financecollector.ConfigKeys
import com.personal.financecollector.FinanceApp
import com.personal.financecollector.StatusKeys
import com.personal.financecollector.collector.NotificationCollector
import com.personal.financecollector.collector.SmsPermission
import com.personal.financecollector.collector.SmsSender
import com.personal.financecollector.database.CollectionStatusEntity
import com.personal.financecollector.database.ConnectedSourceEntity
import com.personal.financecollector.database.RawFinancialEventEntity
import com.personal.financecollector.database.SourceCheckpointEntity
import com.personal.financecollector.database.SourceType
import com.personal.financecollector.database.set
import com.personal.financecollector.service.CollectionHealth
import com.personal.financecollector.service.SmsScanState
import com.personal.financecollector.service.SourceHealth
import com.personal.financecollector.sync.SmsScanScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime

data class InstalledApp(val packageName: String, val label: String)

data class SmsScanUi(
    val health: SourceHealth,
    val permissionGranted: Boolean,
    val state: SmsScanState,
    val scanned: Int,
    val captured: Int,
    val months: Int,
    val lastEventAt: Long?,
)

data class HealthUiState(
    val notification: SourceHealth,
    val sms: SmsScanUi,
    val unavailableSources: List<SourceHealth>,
    val enabledApps: List<String>,
    val lastEventAt: Long?,
    val lastUploadAt: Long?,
    val pending: Int,
    val failed: Int,
    val parserNeedsReview: Int,
    val droppedHidden: Int,
    val droppedFiltered: Int,
    val paused: Boolean,
    val warnings: List<String>,
)

private data class SystemState(val listenerGranted: Boolean, val smsGranted: Boolean, val batteryRestricted: Boolean)

private data class Counts(
    val pending: Int,
    val failed: Int,
    val needsReview: Int,
    val lastAck: Long?,
    val status: CollectionStatusEntity?,
)

private data class Core(
    val notification: SourceHealth,
    val enabledApps: List<String>,
    val notificationLastEvent: Long?,
    val lastUpload: Long?,
    val pending: Int,
    val failed: Int,
    val needsReview: Int,
    val droppedHidden: Int,
    val droppedFiltered: Int,
    val paused: Boolean,
    val warnings: List<String>,
)

private data class SmsConfig(val state: String?, val scanned: String?, val captured: String?, val months: String?, val error: String?)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val db = (app as FinanceApp).container.db
    private val tick = MutableStateFlow(0)

    val installedApps = MutableStateFlow<List<InstalledApp>>(emptyList())

    val sources: StateFlow<List<ConnectedSourceEntity>> = db.sourceDao().observeByType(SourceType.NOTIFICATION)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val smsSources: StateFlow<List<ConnectedSourceEntity>> = db.sourceDao().observeByType(SourceType.SMS)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val events: StateFlow<List<RawFinancialEventEntity>> = db.eventDao().observeRecent(200)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val systemState = tick.map { readSystemState() }
    private val paused = db.configDao().observe(ConfigKeys.COLLECTION_PAUSED).map { it == "true" }

    private val counts = combine(
        db.eventDao().observePendingCount(),
        db.eventDao().observeFailedCount(),
        db.eventDao().observeNeedsReviewCount(),
        db.eventDao().observeLastAckTimestamp(),
        db.statusDao().observe(StatusKeys.NOTIFICATION_LISTENER),
    ) { pending, failed, review, ack, status -> Counts(pending, failed, review, ack, status) }

    private val core = combine(paused, NotificationCollector.isConnected, sources, systemState, counts) { isPaused, connected, sourceList, system, c ->
        val enabled = sourceList.filter { it.enabled }
        val warnings = buildList {
            if (!system.listenerGranted) add("Notification access is off. Notifications cannot be captured until you enable it.")
            if (system.batteryRestricted) add("Battery optimization is on for this app. The system may stop collection in the background.")
            if (system.listenerGranted && enabled.isEmpty()) add("No notification apps are enabled. Choose apps in Sources.")
        }
        Core(
            notification = CollectionHealth.notificationListener(system.listenerGranted, isPaused, connected, enabled.size),
            enabledApps = enabled.map { it.displayName },
            notificationLastEvent = c.status?.lastEventAt,
            lastUpload = c.lastAck,
            pending = c.pending,
            failed = c.failed,
            needsReview = c.needsReview,
            droppedHidden = c.status?.droppedHiddenContent ?: 0,
            droppedFiltered = c.status?.droppedFiltered ?: 0,
            paused = isPaused,
            warnings = warnings,
        )
    }

    private val smsConfig = combine(
        db.configDao().observe(ConfigKeys.SMS_SCAN_STATE),
        db.configDao().observe(ConfigKeys.SMS_SCAN_SCANNED),
        db.configDao().observe(ConfigKeys.SMS_SCAN_CAPTURED),
        db.configDao().observe(ConfigKeys.SMS_SCAN_MONTHS),
        db.configDao().observe(ConfigKeys.SMS_SCAN_ERROR),
    ) { state, scanned, captured, months, error -> SmsConfig(state, scanned, captured, months, error) }

    private val smsUi = combine(smsConfig, smsSources, db.statusDao().observe(StatusKeys.SMS), systemState, paused) { cfg, senders, status, system, isPaused ->
        val state = SmsScanState.parse(cfg.state)
        SmsScanUi(
            health = CollectionHealth.sms(system.smsGranted, isPaused, state, senders.count { it.enabled }, cfg.error?.takeIf { it.isNotBlank() }),
            permissionGranted = system.smsGranted,
            state = state,
            scanned = cfg.scanned?.toIntOrNull() ?: 0,
            captured = cfg.captured?.toIntOrNull() ?: 0,
            months = cfg.months?.toIntOrNull() ?: ConfigKeys.DEFAULT_SMS_SCAN_MONTHS,
            lastEventAt = status?.lastEventAt,
        )
    }

    private val smsDroppedFiltered = db.statusDao().observe(StatusKeys.SMS).map { it?.droppedFiltered ?: 0 }

    val health: StateFlow<HealthUiState?> = combine(core, smsUi, smsDroppedFiltered) { c, sms, smsFiltered ->
        HealthUiState(
            notification = c.notification,
            sms = sms,
            unavailableSources = listOf(
                CollectionHealth.notImplemented("Gmail", 5),
                CollectionHealth.notImplemented("Cloud API", 3),
            ),
            enabledApps = c.enabledApps,
            lastEventAt = listOfNotNull(c.notificationLastEvent, sms.lastEventAt).maxOrNull(),
            lastUploadAt = c.lastUpload,
            pending = c.pending,
            failed = c.failed,
            parserNeedsReview = c.needsReview,
            droppedHidden = c.droppedHidden,
            droppedFiltered = c.droppedFiltered + smsFiltered,
            paused = c.paused,
            warnings = c.warnings,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    init {
        viewModelScope.launch { installedApps.value = loadInstalledApps() }
    }

    fun refreshSystemState() = tick.update { it + 1 }

    fun setPaused(paused: Boolean) {
        viewModelScope.launch { db.configDao().set(ConfigKeys.COLLECTION_PAUSED, paused.toString()) }
    }

    fun setAppEnabled(app: InstalledApp, enabled: Boolean) {
        viewModelScope.launch {
            db.sourceDao().upsert(SourceType.NOTIFICATION, app.packageName, app.label, enabled, System.currentTimeMillis())
        }
    }

    fun setSmsSenderEnabled(source: ConnectedSourceEntity, enabled: Boolean) {
        viewModelScope.launch {
            db.sourceDao().upsert(SourceType.SMS, source.identifier, source.displayName, enabled, System.currentTimeMillis())
        }
    }

    /** Accepts a raw header such as "VM-SBIUPI-S" or a bare code; returns false if it is not a usable sender id. */
    fun addSmsSender(input: String): Boolean {
        val code = SmsSender.entityCode(input) ?: return false
        viewModelScope.launch {
            db.sourceDao().upsert(SourceType.SMS, code, code, true, System.currentTimeMillis())
        }
        return true
    }

    /** Starts the history scan automatically only the first time permission is granted, or to resume an interrupted scan. */
    fun ensureSmsScan() {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            if (!SmsPermission.isGranted(ctx)) return@launch
            val raw = db.configDao().get(ConfigKeys.SMS_SCAN_STATE)
            if (raw == null || raw == SmsScanState.RUNNING.name) SmsScanScheduler.enqueue(ctx, restart = false)
        }
    }

    fun rescanSms(months: Int) {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            if (!SmsPermission.isGranted(ctx)) return@launch
            val now = System.currentTimeMillis()
            val since = ZonedDateTime.now().minusMonths(months.toLong()).toInstant().toEpochMilli()
            db.checkpointDao().put(SourceCheckpointEntity(CheckpointKeys.SMS_SINCE, since.toString(), now))
            db.checkpointDao().put(SourceCheckpointEntity(CheckpointKeys.SMS_LAST_ID, "0", now))
            db.configDao().set(ConfigKeys.SMS_SCAN_MONTHS, months.toString())
            db.configDao().set(ConfigKeys.SMS_SCAN_SCANNED, "0")
            db.configDao().set(ConfigKeys.SMS_SCAN_CAPTURED, "0")
            db.configDao().set(ConfigKeys.SMS_SCAN_ERROR, "")
            db.configDao().set(ConfigKeys.SMS_SCAN_STATE, SmsScanState.NOT_STARTED.name)
            SmsScanScheduler.enqueue(ctx, restart = true)
        }
    }

    fun deleteLocalData() {
        viewModelScope.launch {
            db.eventDao().deleteAllEvents()
            db.checkpointDao().deleteAll()
            db.statusDao().deleteAll()
            db.configDao().deleteKeys(ConfigKeys.SMS_SCAN_KEYS)
            // An explicit NOT_STARTED stops the history scan from silently re-importing what was just deleted.
            db.configDao().set(ConfigKeys.SMS_SCAN_STATE, SmsScanState.NOT_STARTED.name)
        }
    }

    /** Enables this app as a source so the debug test notification is captured, then runs [post]. */
    fun prepareTestSource(post: () -> Unit) {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            db.sourceDao().upsert(SourceType.NOTIFICATION, ctx.packageName, "Finance Collector (test)", true, System.currentTimeMillis())
            post()
        }
    }

    private fun readSystemState(): SystemState {
        val ctx = getApplication<Application>()
        val listener = NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        return SystemState(listener, SmsPermission.isGranted(ctx), !pm.isIgnoringBatteryOptimizations(ctx.packageName))
    }

    private suspend fun loadInstalledApps(): List<InstalledApp> = withContext(Dispatchers.IO) {
        val pm = getApplication<Application>().packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(launcher, 0)
            .map { InstalledApp(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }
}
