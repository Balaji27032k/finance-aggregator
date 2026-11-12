package com.personal.financecollector.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import com.personal.financecollector.database.RawFinancialEventEntity
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

private enum class ActivityFilter(val label: String) {
    ALL("All"), FAILED("Failed"), WAITING("Waiting"), NOT_UNDERSTOOD("Not understood")
}

private sealed interface ActivityItem {
    val time: Long
    val id: String

    data class Txn(val txn: LedgerTxn) : ActivityItem {
        override val time get() = txn.timeMs
        override val id get() = "t-${txn.key}"
    }

    data class Raw(val event: RawFinancialEventEntity) : ActivityItem {
        override val time get() = event.eventTimestamp
        override val id get() = "e-${event.localEventId}"
    }
}

internal fun statusColor(kind: StatusKind): Color = when (kind) {
    StatusKind.DONE -> Color(0xFF2E7D32)
    StatusKind.WAITING -> Color(0xFF1565C0)
    StatusKind.RETRYING -> Color(0xFFEF6C00)
    StatusKind.FAILED -> Color(0xFFC62828)
    StatusKind.REVIEW -> Color(0xFF8D6E00)
}

private fun statusText(kind: StatusKind): String = when (kind) {
    StatusKind.DONE -> "Uploaded"
    StatusKind.WAITING -> "Waiting to upload"
    StatusKind.RETRYING -> "Upload failed, will retry"
    StatusKind.FAILED -> "Upload failed"
    StatusKind.REVIEW -> "Not uploaded"
}

private val receivedFormat = DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a", Locale.ENGLISH)
private val dateFormat = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

/** One entry per real transaction (the SMS, e-mail and notification for the same payment are shown together), newest first. */
@Composable
fun EventsScreen(
    ledger: List<LedgerTxn>,
    notUnderstood: List<RawFinancialEventEntity>,
    onEdit: (LedgerTxn) -> Unit,
    modifier: Modifier = Modifier,
) {
    var filter by rememberSaveable { mutableStateOf(ActivityFilter.ALL) }
    val failed = remember(ledger) { ledger.filter { it.status == StatusKind.FAILED || it.status == StatusKind.RETRYING } }
    val waiting = remember(ledger) { ledger.filter { it.status == StatusKind.WAITING } }
    val everything = remember(ledger, notUnderstood) {
        (ledger.map { ActivityItem.Txn(it) } + notUnderstood.map { ActivityItem.Raw(it) }).sortedByDescending { it.time }
    }
    val shown: List<ActivityItem> = when (filter) {
        ActivityFilter.ALL -> everything
        ActivityFilter.FAILED -> failed.map { ActivityItem.Txn(it) }
        ActivityFilter.WAITING -> waiting.map { ActivityItem.Txn(it) }
        ActivityFilter.NOT_UNDERSTOOD -> notUnderstood.map { ActivityItem.Raw(it) }
    }

    Column(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text("Activity", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 8.dp))
        Text(
            "Everything captured on this phone, newest first. The SMS and e-mail for one payment are shown as a single entry.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        if (failed.isNotEmpty()) {
            Panel(container = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.padding(bottom = 8.dp)) {
                Text("${failed.size} upload${if (failed.size == 1) "" else "s"} did not go through", style = MaterialTheme.typography.titleSmall)
                Text("Open Failed to see why. Health > Sync now retries them.", style = MaterialTheme.typography.bodySmall)
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
            items(ActivityFilter.entries) { f ->
                val count = when (f) {
                    ActivityFilter.ALL -> everything.size
                    ActivityFilter.FAILED -> failed.size
                    ActivityFilter.WAITING -> waiting.size
                    ActivityFilter.NOT_UNDERSTOOD -> notUnderstood.size
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
                    ActivityFilter.NOT_UNDERSTOOD -> "Every captured message was understood."
                },
                modifier = Modifier.padding(vertical = 16.dp),
            )
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
            items(shown, key = { it.id }) { item ->
                when (item) {
                    is ActivityItem.Txn -> TransactionCard(item.txn, onEdit)
                    is ActivityItem.Raw -> UnreadCard(item.event)
                }
            }
        }
    }
}

@Composable
private fun SourceChip(label: String) {
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
    }
}

@Composable
private fun TransactionCard(t: LedgerTxn, onEdit: (LedgerTxn) -> Unit) {
    var expanded by remember(t.key) { mutableStateOf(false) }
    Panel(Modifier.clickable { expanded = !expanded }) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(t.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(
                (if (t.credit) "+" else "-") + EventPresenter.rupees(t.amount),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = when {
                    t.internalTransfer -> MaterialTheme.colorScheme.onSurfaceVariant
                    t.credit -> MoneyColors.income()
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
        }
        Text(
            listOfNotNull(t.date.format(dateFormat), t.paymentMode, t.accountLast4?.let { "A/c ••$it" }, t.category).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Received " + Instant.ofEpochMilli(t.timeMs).atZone(LedgerBuilder.IST).format(receivedFormat),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        listOfNotNull(t.referenceNo?.let { "Ref $it" }, t.balanceAfter?.let { "Balance ${EventPresenter.rupees(it)}" }).joinToString(" · ")
            .takeIf { it.isNotEmpty() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        t.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        t.label?.let { Text("\u201c$it\u201d", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            t.sources.forEach { SourceChip(it) }
            StatusPill(statusText(t.status), statusColor(t.status))
        }
        t.problem?.let { Text("Why: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        TextButton(onClick = { onEdit(t) }) { Text(if (t.edited) "Edit category or note" else "Add a note or change category") }
        if (expanded && t.parts.isNotEmpty()) {
            Text("Seen as", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            t.parts.forEach { p ->
                StatRow("${p.source}, captured ${formatTime(p.capturedMs)}", statusText(p.kind))
                p.problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
private fun UnreadCard(event: RawFinancialEventEntity) {
    val card = remember(event.localEventId, event.lastError) { EventPresenter.present(event) }
    Panel {
        Text(card.title, style = MaterialTheme.typography.titleMedium)
        Text(card.details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Received ${card.received}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        card.extra?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        StatusPill(card.status, statusColor(card.statusKind))
    }
}
