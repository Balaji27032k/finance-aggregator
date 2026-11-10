package com.personal.financecollector.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.personal.financecollector.database.ConnectedSourceEntity
import com.personal.financecollector.database.RawFinancialEventEntity

@Composable
fun EventsScreen(events: List<RawFinancialEventEntity>, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text("Captured events (${events.size})", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(vertical = 8.dp))
        if (events.isEmpty()) {
            Text("Nothing captured yet. Enable notification access or SMS, or send a test notification from the Health tab.")
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(events, key = { it.localEventId }) { EventRow(it) }
        }
    }
}

@Composable
private fun EventRow(event: RawFinancialEventEntity) {
    var expanded by remember(event.localEventId) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().clickable { expanded = !expanded }) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${event.sourceType.name} · ${event.sourceApplication}", style = MaterialTheme.typography.labelMedium)
                Text(event.uploadStatus.name, style = MaterialTheme.typography.labelMedium)
            }
            Text(
                event.rawPayload,
                maxLines = if (expanded) Int.MAX_VALUE else 2,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text("Captured ${formatTime(event.capturedTimestamp)}", style = MaterialTheme.typography.bodySmall)
            if (expanded) {
                Text("Parser: ${event.parserStatus.name} (${event.parserVersion})", style = MaterialTheme.typography.bodySmall)
                Text("Event time ${formatTime(event.eventTimestamp)}", style = MaterialTheme.typography.bodySmall)
                event.sourceSender?.let { Text("Sender: $it", style = MaterialTheme.typography.bodySmall) }
                Text("Retries: ${event.retryCount}  Last error: ${event.lastError ?: "none"}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private val financeHint = Regex("""sbi|yono|indian bank|indbank|csb|pay|bank|upi|wallet""", RegexOption.IGNORE_CASE)

@Composable
fun SourcesScreen(
    apps: List<InstalledApp>,
    appSources: List<ConnectedSourceEntity>,
    smsSources: List<ConnectedSourceEntity>,
    onToggleApp: (InstalledApp, Boolean) -> Unit,
    onToggleSms: (ConnectedSourceEntity, Boolean) -> Unit,
    onAddSms: (String) -> Boolean,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    var newSender by remember { mutableStateOf("") }
    var senderError by remember { mutableStateOf(false) }

    val enabledApps = appSources.filter { it.enabled }.map { it.identifier }.toSet()
    val shownApps = apps
        .filter { query.isBlank() || it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true) }
        .sortedWith(
            compareByDescending<InstalledApp> { it.packageName in enabledApps }
                .thenByDescending { financeHint.containsMatchIn(it.label) }
                .thenBy { it.label.lowercase() },
        )

    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item { Text("SMS sender IDs", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(vertical = 8.dp)) }
        item {
            Text(
                "Only SMS from these senders is read. The route prefix and category suffix are ignored, so VM-SBIUPI-S and AD-SBIUPI-T both match SBIUPI.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        items(smsSources, key = { "sms-${it.identifier}" }) { source ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(source.identifier, style = MaterialTheme.typography.bodyLarge)
                    if (source.displayName != source.identifier) Text(source.displayName, style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = source.enabled, onCheckedChange = { onToggleSms(source, it) })
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = newSender,
                    onValueChange = { newSender = it; senderError = false },
                    label = { Text("Add sender (e.g. VM-SBIINB-S)") },
                    isError = senderError,
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = {
                    if (onAddSms(newSender)) newSender = "" else senderError = true
                }) { Text("Add") }
            }
        }

        item { Text("Notification apps", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(vertical = 8.dp)) }
        item {
            Text("Only enabled apps are read. Turn on your banking apps and Google Pay.", style = MaterialTheme.typography.bodyMedium)
        }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search apps") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            )
        }
        items(shownApps, key = { it.packageName }) { app ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(app.label, style = MaterialTheme.typography.bodyLarge)
                    Text(app.packageName, style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = app.packageName in enabledApps, onCheckedChange = { onToggleApp(app, it) })
            }
        }
    }
}
