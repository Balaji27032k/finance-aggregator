package com.personal.financecollector.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import com.personal.financecollector.database.ConnectedSourceEntity
import com.personal.financecollector.database.RawFinancialEventEntity

private enum class ActivityFilter(val label: String) { ALL("All"), FAILED("Failed"), WAITING("Waiting") }

@Composable
fun EventsScreen(
    events: List<RawFinancialEventEntity>,
    problems: List<RawFinancialEventEntity>,
    ledger: List<LedgerTxn>,
    onEdit: (LedgerTxn) -> Unit,
    modifier: Modifier = Modifier,
) {
    var filter by rememberSaveable { mutableStateOf(ActivityFilter.ALL) }
    val byEvent = remember(ledger) { ledger.flatMap { t -> t.eventIds.map { it to t } }.toMap() }
    val waiting = remember(events) {
        events.filter { EventPresenter.statusKind(it.uploadStatus) == StatusKind.WAITING }
    }
    val shown = when (filter) {
        ActivityFilter.ALL -> events
        ActivityFilter.FAILED -> problems
        ActivityFilter.WAITING -> waiting
    }
    Column(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text("Activity", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 8.dp))
        Text(
            "Every message and notification captured on this phone, newest first.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        if (problems.isNotEmpty()) {
            Panel(container = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.padding(bottom = 8.dp)) {
                Text("${problems.size} upload${if (problems.size == 1) "" else "s"} did not go through", style = MaterialTheme.typography.titleSmall)
                Text("Open Failed to see why. Health > Sync now retries them.", style = MaterialTheme.typography.bodySmall)
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
            items(ActivityFilter.entries) { f ->
                val count = when (f) {
                    ActivityFilter.ALL -> events.size
                    ActivityFilter.FAILED -> problems.size
                    ActivityFilter.WAITING -> waiting.size
                }
                FilterChip(filter == f, { filter = f }, { Text("${f.label} ($count)") })
            }
        }
        if (shown.isEmpty()) {
            Text(
                when (filter) {
                    ActivityFilter.ALL -> "Nothing captured yet. New bank messages and payment notifications appear here automatically."
                    ActivityFilter.FAILED -> "No failed uploads."
                    ActivityFilter.WAITING -> "Nothing is waiting to upload."
                },
            )
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
            items(shown, key = { it.localEventId }) { EventRow(it, byEvent[it.localEventId], onEdit) }
        }
    }
}

private fun statusColor(kind: StatusKind): Color = when (kind) {
    StatusKind.DONE -> Color(0xFF2E7D32)
    StatusKind.WAITING -> Color(0xFF1565C0)
    StatusKind.RETRYING -> Color(0xFFEF6C00)
    StatusKind.FAILED -> Color(0xFFC62828)
    StatusKind.REVIEW -> Color(0xFF8D6E00)
}

@Composable
private fun EventRow(event: RawFinancialEventEntity, txn: LedgerTxn?, onEdit: (LedgerTxn) -> Unit) {
    var expanded by remember(event.localEventId) { mutableStateOf(false) }
    val card = remember(event.localEventId, event.uploadStatus, event.lastError) { EventPresenter.present(event) }
    Panel(Modifier.clickable { expanded = !expanded }) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(card.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            card.amount?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (card.credit == true) MoneyColors.income() else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        Text(card.details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Received ${card.received}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        card.extra?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        card.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        txn?.label?.let { Text("“$it”  ·  ${txn.category}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
        StatusPill(card.status, statusColor(card.statusKind))
        card.problem?.let {
            Text("Why: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (txn != null) {
            TextButton(onClick = { onEdit(txn) }) { Text(if (txn.edited) "Edit category or note" else "Add a note or change category") }
        }
        if (expanded) {
            Text("Captured ${formatTime(event.capturedTimestamp)}", style = MaterialTheme.typography.bodySmall)
            Text("Parser: ${event.parserVersion}  Retries: ${event.retryCount}  Last error: ${event.lastError ?: "none"}", style = MaterialTheme.typography.bodySmall)
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

    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
        item { Text("SMS sender IDs", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 8.dp)) }
        item {
            Text(
                "Only SMS from these senders is read. The route prefix and category suffix are ignored, so VM-SBIUPI-S and AD-SBIUPI-T both match SBIUPI.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(smsSources, key = { "sms-${it.identifier}" }) { source ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(source.identifier, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
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
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = {
                    if (onAddSms(newSender)) newSender = "" else senderError = true
                }) { Text("Add") }
            }
        }

        item { Text("Notification apps", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 16.dp)) }
        item {
            Text(
                "Only enabled apps are read. Turn on your banking apps and Google Pay.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search apps") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
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
                    Text(app.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = app.packageName in enabledApps, onCheckedChange = { onToggleApp(app, it) })
            }
        }
    }
}
