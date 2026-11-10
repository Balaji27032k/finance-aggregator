package com.personal.financecollector.service

enum class SourceState { ACTIVE, DISCONNECTED, PERMISSION_REQUIRED, SYNC_PENDING, ERROR, DISABLED }

data class SourceHealth(val name: String, val state: SourceState, val detail: String)

object CollectionHealth {
    fun notificationListener(
        permissionGranted: Boolean,
        paused: Boolean,
        connected: Boolean,
        enabledAppCount: Int,
    ): SourceHealth {
        val name = "Notification listener"
        return when {
            paused -> SourceHealth(name, SourceState.DISABLED, "Collection is paused")
            !permissionGranted -> SourceHealth(name, SourceState.PERMISSION_REQUIRED, "Notification access is not granted")
            !connected -> SourceHealth(name, SourceState.DISCONNECTED, "Access is granted but the listener is not connected")
            enabledAppCount == 0 -> SourceHealth(name, SourceState.ACTIVE, "Listening, but no apps are enabled")
            else -> SourceHealth(name, SourceState.ACTIVE, "Listening to $enabledAppCount app(s)")
        }
    }

    fun sms(
        permissionGranted: Boolean,
        paused: Boolean,
        scanState: SmsScanState,
        enabledSenderCount: Int,
        scanError: String?,
    ): SourceHealth {
        val name = "SMS collector"
        return when {
            paused -> SourceHealth(name, SourceState.DISABLED, "Collection is paused")
            !permissionGranted -> SourceHealth(name, SourceState.PERMISSION_REQUIRED, "SMS access is not granted (optional source)")
            enabledSenderCount == 0 -> SourceHealth(name, SourceState.DISABLED, "No sender IDs are enabled")
            scanState == SmsScanState.FAILED -> SourceHealth(name, SourceState.ERROR, "History scan failed: ${scanError ?: "unknown"}")
            scanState == SmsScanState.RUNNING -> SourceHealth(name, SourceState.SYNC_PENDING, "History scan in progress")
            scanState == SmsScanState.NOT_STARTED -> SourceHealth(name, SourceState.SYNC_PENDING, "History scan not run yet")
            else -> SourceHealth(name, SourceState.ACTIVE, "Permission granted, history scanned, $enabledSenderCount sender ID(s) enabled")
        }
    }

    fun notImplemented(name: String, phase: Int) =
        SourceHealth(name, SourceState.DISABLED, "Not implemented yet (Phase $phase)")
}
