package com.personal.financecollector.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.personal.financecollector.CheckpointKeys
import com.personal.financecollector.ConfigKeys
import com.personal.financecollector.FinanceApp
import com.personal.financecollector.StatusKeys
import com.personal.financecollector.collector.NotificationCollector
import com.personal.financecollector.collector.ParsedTransaction
import com.personal.financecollector.collector.TransactionParser
import com.personal.financecollector.collector.SmsPermission
import com.personal.financecollector.collector.SmsSender
import com.personal.financecollector.database.CollectionStatusEntity
import com.personal.financecollector.database.ConnectedSourceEntity
import com.personal.financecollector.database.RawFinancialEventEntity
import com.personal.financecollector.database.SourceCheckpointEntity
import com.personal.financecollector.database.SourceType
import com.personal.financecollector.database.set
import com.personal.financecollector.export.ExportProgress
import com.personal.financecollector.export.ExportScheduler
import com.personal.financecollector.export.ExportState
import com.personal.financecollector.service.CollectionHealth
import com.personal.financecollector.service.SmsScanState
import com.personal.financecollector.service.SourceHealth
import com.personal.financecollector.sync.BackendApi
import com.personal.financecollector.sync.BankMailScheduler
import com.personal.financecollector.sync.RegisterOutcome
import com.personal.financecollector.sync.SmsScanScheduler
import com.personal.financecollector.sync.UploadScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime

data class InstalledApp(val packageName: String, val label: String)

private const val LEDGER_EVENTS = 6000

data class SmsScanUi(
    val health: SourceHealth,
    val permissionGranted: Boolean,
    val state: SmsScanState,
    val scanned: Int,
    val captured: Int,
    val months: Int,
    val lastEventAt: Long?,
)

data class CloudUi(
    val health: SourceHealth,
    val paired: Boolean,
    val backendUrl: String?,
    val deviceId: String?,
    val lastOkAt: Long?,
    val pending: Int,
    val needsAttention: Int,
)

data class GmailUi(val connected: Boolean, val status: String?, val lastCheckAt: Long?, val captured: Int)

data class HealthUiState(
    val notification: SourceHealth,
    val sms: SmsScanUi,
    val cloud: CloudUi,
    val gmail: GmailUi,
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

private data class CloudConfig(val url: String?, val paired: String?, val deviceId: String?, val lastOk: String?, val error: String?)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val db = (app as FinanceApp).container.db
    private val tick = MutableStateFlow(0)

    /** Monthly spending budget in whole rupees; kept on this phone only. */
    private val prefs = app.getSharedPreferences("finance_prefs", Context.MODE_PRIVATE)
    val budget = MutableStateFlow(prefs.getLong("monthly_budget", 0L).takeIf { it > 0 })

    fun setBudget(value: Long?) {
        prefs.edit().apply { if (value == null) remove("monthly_budget") else putLong("monthly_budget", value) }.apply()
        budget.value = value
    }

    val installedApps = MutableStateFlow<List<InstalledApp>>(emptyList())

    val sources: StateFlow<List<ConnectedSourceEntity>> = db.sourceDao().observeByType(SourceType.NOTIFICATION)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val smsSources: StateFlow<List<ConnectedSourceEntity>> = db.sourceDao().observeByType(SourceType.SMS)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val events: StateFlow<List<RawFinancialEventEntity>> = db.eventDao().observeRecent(200)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Every understood transaction on the phone, merged across SMS, e-mail and notifications. Parsed off the main thread. */
    val uploadProblems: StateFlow<List<RawFinancialEventEntity>> = db.eventDao().observeUploadProblems(300)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val parsedCache = HashMap<String, ParsedTransaction?>()
    private val _ledgerLoaded = MutableStateFlow(false)
    val ledgerLoaded: StateFlow<Boolean> = _ledgerLoaded
    val ledger: StateFlow<List<LedgerTxn>> = db.eventDao().observeRecent(LEDGER_EVENTS)
        .map { all ->
            LedgerBuilder.build(all) { e ->
                if (parsedCache.containsKey(e.localEventId)) parsedCache[e.localEventId]
                else TransactionParser.parse(e).also { parsedCache[e.localEventId] = it }
            }
        }
        .flowOn(Dispatchers.Default)
        .onEach { _ledgerLoaded.value = true }
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

    val pairingMessage = MutableStateFlow<String?>(null)

    private val cloudConfig = combine(
        db.configDao().observe(ConfigKeys.BACKEND_URL),
        db.configDao().observe(ConfigKeys.PAIRED),
        db.configDao().observe(ConfigKeys.DEVICE_ID),
        db.configDao().observe(ConfigKeys.SYNC_LAST_OK_AT),
        db.configDao().observe(ConfigKeys.SYNC_LAST_ERROR),
    ) { url, paired, deviceId, lastOk, error -> CloudConfig(url, paired, deviceId, lastOk, error) }

    private val cloudUi = combine(cloudConfig, counts) { cfg, c ->
        val isPaired = cfg.paired == "true"
        val lastOk = cfg.lastOk?.toLongOrNull()
        CloudUi(
            health = CollectionHealth.cloud(isPaired, c.pending, lastOk, cfg.error?.takeIf { it.isNotBlank() }),
            paired = isPaired,
            backendUrl = cfg.url,
            deviceId = cfg.deviceId,
            lastOkAt = lastOk,
            pending = c.pending,
            needsAttention = c.failed,
        )
    }

    private val gmailUi = combine(
        db.configDao().observe(ConfigKeys.GMAIL_CONNECTED),
        db.configDao().observe(ConfigKeys.GMAIL_BANK_STATUS),
        db.configDao().observe(ConfigKeys.GMAIL_BANK_LAST_CHECK),
        db.configDao().observe(ConfigKeys.GMAIL_BANK_CAPTURED),
    ) { connected, status, last, captured ->
        GmailUi(connected == "true", status?.takeIf { it.isNotBlank() }, last?.toLongOrNull(), captured?.toIntOrNull() ?: 0)
    }

    val health: StateFlow<HealthUiState?> = combine(core, smsUi, smsDroppedFiltered, cloudUi, gmailUi) { c, sms, smsFiltered, cloud, gmail ->
        HealthUiState(
            notification = c.notification,
            sms = sms,
            cloud = cloud,
            gmail = gmail,
            unavailableSources = emptyList(),
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

    /** The pairing code is used once for registration and never stored. */
    fun pair(backendUrl: String, pairingCode: String) {
        viewModelScope.launch {
            val container = (getApplication<Application>() as FinanceApp).container
            val normalized = BackendApi.normalizeBaseUrl(backendUrl)
            if (normalized == null) {
                pairingMessage.value = "Enter an https:// address"
                return@launch
            }
            if (pairingCode.isBlank()) {
                pairingMessage.value = "Enter the pairing code"
                return@launch
            }
            pairingMessage.value = "Pairing..."
            val ctx = getApplication<Application>()
            val version = ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "unknown"
            when (val result = container.api.register(normalized, pairingCode.trim(), "${Build.MANUFACTURER} ${Build.MODEL}", version)) {
                is RegisterOutcome.Registered -> {
                    container.credentials.saveToken(result.token)
                    db.configDao().set(ConfigKeys.BACKEND_URL, normalized)
                    db.configDao().set(ConfigKeys.DEVICE_ID, result.deviceId.toString())
                    db.configDao().set(ConfigKeys.PAIRED, "true")
                    db.configDao().set(ConfigKeys.SYNC_LAST_ERROR, "")
                    pairingMessage.value = "Paired. Uploading queued events."
                    UploadScheduler.enqueueNow(ctx)
                }
                RegisterOutcome.BadPairingCode -> pairingMessage.value = "The pairing code was not accepted"
                is RegisterOutcome.Failed -> pairingMessage.value = result.reason
            }
        }
    }

    /** Forgets the credentials; captured events stay on the phone and upload after the next pairing. */
    fun unpair() {
        viewModelScope.launch {
            val container = (getApplication<Application>() as FinanceApp).container
            container.credentials.clear()
            db.configDao().set(ConfigKeys.PAIRED, "false")
            pairingMessage.value = null
        }
    }

    fun syncNow() {
        viewModelScope.launch {
            db.eventDao().resetBackoff(System.currentTimeMillis())
            UploadScheduler.enqueueNow(getApplication())
        }
    }

    fun retryFailed() {
        viewModelScope.launch {
            db.eventDao().requeueFailed(System.currentTimeMillis())
            UploadScheduler.enqueueNow(getApplication())
        }
    }

    fun resendAll() {
        viewModelScope.launch {
            db.eventDao().resendAll(System.currentTimeMillis())
            UploadScheduler.enqueueNow(getApplication())
        }
    }

    private fun exportProgress(name: String) = combine(
        listOf("state", "seen", "uploaded", "skipped", "total", "error").map { db.configDao().observe("export_${name}_$it") },
    ) { v ->
        ExportProgressUi(
            state = v[0], seen = v[1]?.toLongOrNull() ?: 0, uploaded = v[2]?.toLongOrNull() ?: 0,
            skipped = v[3]?.toLongOrNull() ?: 0, total = v[4]?.toLongOrNull() ?: 0, error = v[5],
        )
    }

    private val exportMessage = MutableStateFlow<String?>(null)

    private val exportProgresses = combine(exportProgress("sms"), exportProgress("mail"), exportProgress("notif")) { sms, mail, notif ->
        Triple(sms, mail, notif)
    }

    val export: StateFlow<ExportUi> = combine(
        db.configDao().observe(ConfigKeys.PAIRED),
        exportProgresses,
        db.configDao().observe(ConfigKeys.GMAIL_CONNECTED),
        exportMessage,
    ) { paired, progresses, gmail, message ->
        ExportUi(paired == "true", progresses.first, progresses.second, progresses.third, gmail == "true", message)
    }.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000),
        ExportUi(false, ExportProgressUi(null, 0, 0, 0, 0, null), ExportProgressUi(null, 0, 0, 0, 0, null), ExportProgressUi(null, 0, 0, 0, 0, null), false, null),
    )

    fun fetchNotifications() {
        exportMessage.value = null
        ExportScheduler.enqueueNotifications(getApplication())
    }

    fun fetchAllSms() {
        exportMessage.value = null
        ExportScheduler.enqueueSms(getApplication())
    }

    /** A finished export starts again from the newest mail; an interrupted one resumes where it stopped. */
    fun fetchGmail() {
        viewModelScope.launch {
            val progress = ExportProgress(db.configDao(), "mail")
            if (progress.text(ExportProgress.STATE) in listOf(null, "", ExportState.COMPLETE)) progress.reset()
            exportMessage.value = null
            ExportScheduler.enqueueGmail(getApplication())
        }
    }

    fun gmailConnected(connected: Boolean, message: String? = null) {
        viewModelScope.launch { db.configDao().set(ConfigKeys.GMAIL_CONNECTED, connected.toString()) }
        exportMessage.value = message
        if (connected) BankMailScheduler.enqueueNow(getApplication())
    }

    fun checkGmailNow() = BankMailScheduler.enqueueNow(getApplication())

    fun showExportMessage(message: String) {
        exportMessage.value = message
    }

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
