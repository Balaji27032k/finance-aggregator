package com.personal.financecollector.ui

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
    val sendTestNotification: () -> Unit,
    val deleteLocalData: () -> Unit,
    val requestSmsPermission: () -> Unit,
    val rescanSms: (Int) -> Unit,
    val pair: (String, String) -> Unit,
    val unpair: () -> Unit,
    val syncNow: () -> Unit,
    val retryFailed: () -> Unit,
    val resendAll: () -> Unit,
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
fun HealthScreen(state: HealthUiState?, pairingMessage: String?, actions: HealthActions, modifier: Modifier = Modifier) {
    if (state == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    var confirmDelete by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("Collection health", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 8.dp)) }

        if (state.warnings.isNotEmpty()) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Warnings", style = MaterialTheme.typography.titleSmall)
                        state.warnings.forEach { Text("• $it") }
                    }
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

        items(state.unavailableSources) { SourceCard(it) {} }

        item {
            Card {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Capture", style = MaterialTheme.typography.titleMedium)
                    Text("Enabled apps: ${state.enabledApps.ifEmpty { listOf("none") }.joinToString()}")
                    Text("Last event captured: ${formatTime(state.lastEventAt)}")
                    Text("Last successful upload: ${formatTime(state.lastUploadAt)}")
                    Text("Pending upload: ${state.pending}")
                    Text("Failed upload: ${state.failed}")
                    Text("Needs review (parser): ${state.parserNeedsReview}")
                    Text("Ignored (filtered / OTP / unrelated): ${state.droppedFiltered}")
                    Text("Ignored (hidden notification content): ${state.droppedHidden}")
                }
            }
        }

        item {
            Card {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Controls", style = MaterialTheme.typography.titleMedium)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Pause collection")
                        Switch(checked = state.paused, onCheckedChange = actions.setPaused)
                    }
                    OutlinedButton(onClick = actions.openBatterySettings) { Text("Battery optimization settings") }
                    OutlinedButton(onClick = actions.sendTestNotification) { Text("Send test notification") }
                    OutlinedButton(onClick = { confirmDelete = true }) { Text("Delete local data") }
                }
            }
        }
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
            TextButton(onClick = actions.resendAll) { Text("Re-upload everything") }
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
    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(health.name, style = MaterialTheme.typography.titleMedium)
                Text(health.state.name, color = stateColor(health.state), style = MaterialTheme.typography.labelLarge)
            }
            Text(health.detail, style = MaterialTheme.typography.bodyMedium)
            extra()
        }
    }
}
