package com.personal.financecollector.ui

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.personal.financecollector.service.SmsScanState
import com.personal.financecollector.service.SourceHealth
import com.personal.financecollector.service.SourceState

data class HealthActions(
    val openNotificationAccess: () -> Unit,
    val openAppSettings: () -> Unit,
    val openBatterySettings: () -> Unit,
    val reconnectListener: () -> Unit,
    val setPaused: (Boolean) -> Unit,
    val deleteLocalData: () -> Unit,
    val requestSmsPermission: () -> Unit,
    val rescanSms: (Int) -> Unit,
    val pair: (String, String) -> Unit,
    val unpair: () -> Unit,
    val syncNow: () -> Unit,
    val retryFailed: () -> Unit,
    val resendAll: () -> Unit,
    val fetchAllSms: () -> Unit,
    val connectGmail: () -> Unit,
    val fetchGmail: () -> Unit,
    val fetchNotifications: () -> Unit,
    val checkGmail: () -> Unit,
    val openSources: () -> Unit,
    val openAccounts: () -> Unit,
    val openPayees: () -> Unit,
)

internal fun formatTime(millis: Long?): String =
    if (millis == null) "never" else DateFormat.format("dd MMM yyyy, HH:mm:ss", millis).toString()

private fun stateColor(state: SourceState): Color = when (state) {
    SourceState.ACTIVE -> Color(0xFF2E7D32)
    SourceState.DISCONNECTED, SourceState.ERROR -> Color(0xFFC62828)
    SourceState.PERMISSION_REQUIRED -> Color(0xFFEF6C00)
    SourceState.SYNC_PENDING -> Color(0xFF1565C0)
    SourceState.DISABLED -> Color(0xFF757575)
}

@Composable
fun HealthScreen(
    state: HealthUiState?,
    pairingMessage: String?,
    export: ExportUi,
    actions: HealthActions,
    modifier: Modifier = Modifier,
) {
    if (state == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    var confirmDelete by remember { mutableStateOf(false) }
    // Seven taps on the title reveal the analysis-export tools, which are only needed when a new bank is added.
    var titleTaps by remember { mutableStateOf(0) }
    var showExportTools by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                "Health",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(top = 8.dp).clickable {
                    titleTaps++
                    if (titleTaps >= 7) {
                        showExportTools = !showExportTools
                        titleTaps = 0
                    }
                },
            )
        }

        item {
            val needAttention = listOf(state.notification, state.sms.health, state.cloud.health).count {
                it.state == SourceState.DISCONNECTED || it.state == SourceState.ERROR || it.state == SourceState.PERMISSION_REQUIRED
            }
            val ok = needAttention == 0 && !state.paused
            Panel(container = if (ok) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.tertiaryContainer) {
                Text(
                    when {
                        state.paused -> "Collection is paused"
                        needAttention == 0 -> "Everything is being collected"
                        else -> "$needAttention source${if (needAttention == 1) " needs" else "s need"} attention"
                    },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text("Last message captured ${formatTime(state.lastEventAt)}", style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (state.pending == 0 && state.failed == 0) "Everything is uploaded"
                    else "${state.pending} waiting to upload, ${state.failed} failed",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        if (state.warnings.isNotEmpty()) {
            item {
                Panel(container = MaterialTheme.colorScheme.errorContainer) {
                    Text("Warnings", style = MaterialTheme.typography.titleSmall)
                    state.warnings.forEach { Text("• $it") }
                }
            }
        }

        item {
            SourceCard(state.notification) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.notification.state == SourceState.PERMISSION_REQUIRED) {
                        Button(onClick = actions.openNotificationAccess) { Text("Enable access") }
                        OutlinedButton(onClick = actions.openAppSettings) { Text("App settings") }
                    }
                    if (state.notification.state == SourceState.DISCONNECTED) {
                        Button(onClick = actions.reconnectListener) { Text("Reconnect") }
                    }
                }
                if (state.notification.state == SourceState.PERMISSION_REQUIRED) {
                    Text(
                        "If the switch is greyed out: App settings > ⋮ > Allow restricted settings, then try again.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        item { SmsCard(state.sms, actions) }

        item { CloudCard(state.cloud, pairingMessage, actions) }

        item { GmailCard(state.gmail, actions) }

        if (showExportTools) item { ExportCard(export, actions) }

        items(state.unavailableSources) { SourceCard(it) {} }

        item {
            Panel {
                Text("Capture", style = MaterialTheme.typography.titleMedium)
                Text("Enabled apps", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(state.enabledApps.ifEmpty { listOf("none") }.joinToString(", "), fontWeight = FontWeight.Medium)
                StatRow("Last event captured", formatTime(state.lastEventAt))
                StatRow("Last successful upload", formatTime(state.lastUploadAt))
                StatRow("Pending upload", state.pending.toString())
                StatRow("Failed upload", state.failed.toString())
                StatRow("Needs review (parser)", state.parserNeedsReview.toString())
                StatRow("Ignored: filtered, OTP, unrelated", state.droppedFiltered.toString())
                StatRow("Ignored: hidden content", state.droppedHidden.toString())
            }
        }

        item {
            Panel {
                Text("Controls", style = MaterialTheme.typography.titleMedium)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Pause collection")
                    Switch(checked = state.paused, onCheckedChange = actions.setPaused)
                }
                Button(onClick = actions.openSources, modifier = Modifier.fillMaxWidth()) { Text("Choose SMS senders and apps") }
                OutlinedButton(onClick = actions.openPayees, modifier = Modifier.fillMaxWidth()) { Text("Payees and categories") }
                OutlinedButton(onClick = actions.openAccounts, modifier = Modifier.fillMaxWidth()) { Text("My accounts (own transfers)") }
                OutlinedButton(onClick = actions.openBatterySettings, modifier = Modifier.fillMaxWidth()) { Text("Battery optimization settings") }
                OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) { Text("Delete local data") }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete local data?") },
            text = { Text("All captured events stored on this phone will be removed, including events not yet uploaded. SMS history will not be re-imported until you rescan.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; actions.deleteLocalData() }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun GmailCard(gmail: GmailUi, actions: HealthActions) {
    Panel {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Gmail (bank alert e-mails)", style = MaterialTheme.typography.titleMedium)
            when {
                !gmail.connected -> StatusPill("Not connected", Color(0xFF757575))
                gmail.status == "NEEDS_CONNECT" -> StatusPill("Sign in again", Color(0xFFEF6C00))
                else -> StatusPill("Connected", Color(0xFF2E7D32))
            }
        }
        when {
            !gmail.connected -> Text("Not connected. Connect once so bank e-mails add the payee name and your remark to each transaction.", style = MaterialTheme.typography.bodySmall)
            gmail.status == "NEEDS_CONNECT" -> Text("Google asked you to sign in again. Reconnect to keep reading bank e-mails.", style = MaterialTheme.typography.bodySmall)
            else -> Text("Last check: ${formatTime(gmail.lastCheckAt)}. Bank e-mails captured: ${gmail.captured}." + (gmail.status?.takeIf { it != "OK" }?.let { " $it" } ?: ""), style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!gmail.connected || gmail.status == "NEEDS_CONNECT") {
                Button(onClick = actions.connectGmail) { Text(if (gmail.connected) "Reconnect Gmail" else "Connect Gmail") }
            } else {
                OutlinedButton(onClick = actions.checkGmail) { Text("Check now") }
            }
        }
    }
}

@Composable
private fun CloudCard(cloud: CloudUi, pairingMessage: String?, actions: HealthActions) {
    var url by remember { mutableStateOf(cloud.backendUrl.orEmpty()) }
    var code by remember { mutableStateOf("") }
    SourceCard(cloud.health) {
        if (!cloud.paired) {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Backend address (https://...)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = code,
                onValueChange = { code = it },
                label = { Text("Pairing code") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = { actions.pair(url, code); code = "" }) { Text("Pair") }
            pairingMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        } else {
            Text("Backend: ${cloud.backendUrl}  (device ${cloud.deviceId})", style = MaterialTheme.typography.bodySmall)
            Text("Last successful upload: ${formatTime(cloud.lastOkAt)}", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = actions.syncNow) { Text("Sync now") }
                if (cloud.needsAttention > 0) {
                    OutlinedButton(onClick = actions.retryFailed) { Text("Retry ${cloud.needsAttention} failed") }
                }
            }
            TextButton(onClick = actions.resendAll, enabled = cloud.pending == 0) {
                Text(if (cloud.pending == 0) "Re-upload everything" else "${cloud.pending} events waiting to upload")
            }
            TextButton(onClick = actions.unpair) { Text("Unpair") }
        }
    }
}

@Composable
private fun SmsCard(sms: SmsScanUi, actions: HealthActions) {
    SourceCard(sms.health) {
        if (!sms.permissionGranted) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = actions.requestSmsPermission) { Text("Enable SMS") }
                OutlinedButton(onClick = actions.openAppSettings) { Text("App settings") }
            }
            Text(
                "Only messages from the sender IDs you enable are read. If the permission is blocked: App settings > ⋮ > Allow restricted settings.",
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            Text("Scanned ${sms.scanned} messages, captured ${sms.captured}. Last SMS captured: ${formatTime(sms.lastEventAt)}", style = MaterialTheme.typography.bodySmall)
            Text("History period (tap to rescan):", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(3, 6, 12, 24).forEach { months ->
                    FilterChip(
                        selected = sms.months == months,
                        onClick = { actions.rescanSms(months) },
                        label = { Text("$months mo") },
                    )
                }
            }
            if (sms.state == SmsScanState.NOT_STARTED || sms.state == SmsScanState.FAILED) {
                OutlinedButton(onClick = { actions.rescanSms(sms.months) }) { Text("Scan SMS history") }
            }
        }
    }
}

@Composable
private fun SourceCard(health: SourceHealth, extra: @Composable () -> Unit) {
    Panel {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(health.name, style = MaterialTheme.typography.titleMedium)
            StatusPill(stateLabel(health.state), stateColor(health.state))
        }
        Text(health.detail, style = MaterialTheme.typography.bodyMedium)
        extra()
    }
}
