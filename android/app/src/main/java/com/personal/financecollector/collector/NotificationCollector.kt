package com.personal.financecollector.collector

import android.app.Notification
import android.content.ComponentName
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.personal.financecollector.ConfigKeys
import com.personal.financecollector.FinanceApp
import com.personal.financecollector.StatusKeys
import com.personal.financecollector.database.FinanceDatabase
import com.personal.financecollector.database.RawFinancialEventEntity
import com.personal.financecollector.database.SourceType
import com.personal.financecollector.database.UploadStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID

class NotificationCollector : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val db: FinanceDatabase get() = (application as FinanceApp).container.db

    override fun onListenerConnected() {
        connectedFlag.value = true
        scope.launch {
            db.statusDao().ensure(StatusKeys.NOTIFICATION_LISTENER)
            db.statusDao().setConnected(StatusKeys.NOTIFICATION_LISTENER, System.currentTimeMillis())
            captureActiveNotifications()
        }
    }

    override fun onListenerDisconnected() {
        connectedFlag.value = false
        scope.launch {
            db.statusDao().ensure(StatusKeys.NOTIFICATION_LISTENER)
            db.statusDao().setDisconnected(StatusKeys.NOTIFICATION_LISTENER, System.currentTimeMillis())
        }
        requestRebind(ComponentName(this, NotificationCollector::class.java))
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        scope.launch { process(sbn) }
    }

    // Stored evidence is intentionally kept when the user dismisses the notification.
    override fun onNotificationRemoved(sbn: StatusBarNotification) = Unit

    override fun onDestroy() {
        connectedFlag.value = false
        scope.cancel()
        super.onDestroy()
    }

    /** Picks up notifications that were posted while the listener was disconnected. */
    private suspend fun captureActiveNotifications() {
        val active = try {
            activeNotifications ?: return
        } catch (_: SecurityException) {
            return
        }
        active.forEach { process(it) }
    }

    private suspend fun process(sbn: StatusBarNotification) {
        if (db.configDao().get(ConfigKeys.COLLECTION_PAUSED) == "true") return
        if (sbn.packageName !in db.sourceDao().enabledIdentifiers(SourceType.NOTIFICATION)) return

        val notification = sbn.notification
        if (notification.flags and (Notification.FLAG_GROUP_SUMMARY or Notification.FLAG_ONGOING_EVENT) != 0) return

        val status = db.statusDao()
        status.ensure(StatusKeys.NOTIFICATION_LISTENER)

        val content = extract(notification).takeUnless { it.isEmpty() }
            ?: notification.publicVersion?.let { extract(it) }?.takeUnless { it.isEmpty() }
        if (content == null) {
            status.incrementHidden(StatusKeys.NOTIFICATION_LISTENER)
            return
        }

        when (val decision = NotificationFilter.evaluate(content)) {
            is FilterDecision.Capture -> store(sbn, decision)
            FilterDecision.DropOtp, FilterDecision.DropUnrelated ->
                status.incrementFiltered(StatusKeys.NOTIFICATION_LISTENER)
        }
    }

    private suspend fun store(sbn: StatusBarNotification, decision: FilterDecision.Capture) {
        val now = System.currentTimeMillis()
        val payloadHash = Hashing.sha256(decision.payload)
        // `when` separates two identical alerts; re-delivery of the same notification keeps the same id.
        val sourceEventId = Hashing.sha256("${sbn.packageName}|${sbn.key}|${sbn.notification.`when`}|$payloadHash")
        val inserted = db.eventDao().insertWithQueue(
            RawFinancialEventEntity(
                localEventId = UUID.randomUUID().toString(),
                sourceType = SourceType.NOTIFICATION,
                sourceApplication = sbn.packageName,
                sourceSender = null,
                sourceEventId = sourceEventId,
                eventTimestamp = sbn.postTime,
                capturedTimestamp = now,
                rawPayload = decision.payload,
                payloadHash = payloadHash,
                parserStatus = decision.parserStatus,
                parserVersion = PARSER_VERSION,
                uploadStatus = UploadStatus.PENDING,
            ),
        )
        if (inserted) db.statusDao().setLastEvent(StatusKeys.NOTIFICATION_LISTENER, now)
    }

    private fun extract(n: Notification): NotificationContent {
        val e: Bundle = n.extras ?: return NotificationContent(null, null, null, emptyList(), emptyList())
        val lines = e.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.map { it.toString() }.orEmpty()
        @Suppress("DEPRECATION")
        val messages = e.getParcelableArray(Notification.EXTRA_MESSAGES)
            ?.let { Notification.MessagingStyle.Message.getMessagesFromBundleArray(it) }
            ?.mapNotNull { it.text?.toString() }
            .orEmpty()
        return NotificationContent(
            title = e.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            text = e.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            bigText = e.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            lines = lines,
            messages = messages,
        )
    }

    companion object {
        const val PARSER_VERSION = "raw-1"

        private val connectedFlag = MutableStateFlow(false)

        /** True only while the system has this service bound in the current process. */
        val isConnected: StateFlow<Boolean> = connectedFlag
    }
}
