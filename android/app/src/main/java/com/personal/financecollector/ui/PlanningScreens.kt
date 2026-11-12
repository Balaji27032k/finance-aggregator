package com.personal.financecollector.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.personal.financecollector.alerts.Alerts
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/** A switch with a short explanation, used for the alert settings. */
@Composable
private fun AlertSwitch(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange)
    }
}

@Composable
private fun NotificationNote(allowed: Boolean, onAllow: () -> Unit) {
    if (!allowed) {
        Text(
            "Notifications are off for this app, so alerts cannot be shown.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 6.dp),
        )
        TextButton(onClick = onAllow) { Text("Allow notifications") }
    }
}

@Composable
fun BudgetsScreen(
    ledger: List<LedgerTxn>,
    limits: Map<String, Long>,
    onSetLimit: (String, Long?) -> Unit,
    onAllowNotifications: () -> Unit,
    notificationsVersion: Int,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val today = remember { LocalDate.now(LedgerBuilder.IST) }
    val month = YearMonth.from(today)
    val statuses = remember(ledger, limits) { BudgetCalculator.statuses(ledger, limits, month) }
    val lastMonth = remember(ledger) { StatsCalculator.categorySpend(ledger, month.minusMonths(1)) }
    var alertsOn by remember { mutableStateOf(Alerts.budgetAlertsOn(context)) }
    var editing by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    val allowed = remember(notificationsVersion) { Alerts.notificationsAllowed(context) }

    LazyColumn(
        modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            Text("Category budgets", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 8.dp))
            Text(
                "A monthly limit per category. You get an alert at 80% and again at 100%.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            InfoCard("Alerts") {
                AlertSwitch("Budget alerts", "Checked after each new bank message and a few times a day", alertsOn) {
                    alertsOn = it
                    Alerts.setBudgetAlerts(context, it)
                    if (it && !allowed) onAllowNotifications()
                }
                NotificationNote(allowed, onAllowNotifications)
            }
        }
        if (statuses.isEmpty()) {
            item { Text("No budgets yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(statuses, key = { it.category }) { s ->
            InfoCard(s.category) {
                Column(Modifier.clickable { editing = s.category }) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${rupeesWhole(s.spent)} of ${rupeesWhole(BigDecimal(s.limit))}", fontWeight = FontWeight.SemiBold)
                        Text("${s.percent}%", color = budgetColor(s), fontWeight = FontWeight.SemiBold)
                    }
                    LinearProgressIndicator(
                        progress = { (s.percent / 100f).coerceIn(0f, 1f) },
                        color = budgetColor(s),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).height(10.dp).clip(RoundedCornerShape(5.dp)),
                    )
                    val remaining = BigDecimal(s.limit) - s.spent
                    Text(
                        if (remaining.signum() >= 0) "${rupeesWhole(remaining)} left this month" else "${rupeesWhole(remaining.abs())} over",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text("Tap to change", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        item { Button(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) { Text("Add a budget") } }
        item {
            Text(
                "Money moved between your own accounts is never counted. A payment split across categories counts towards each part.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (adding) {
        BudgetDialog(
            category = null,
            current = null,
            lastMonthSpend = lastMonth,
            onSave = { c, l -> onSetLimit(c, l); adding = false },
            onRemove = null,
            onDismiss = { adding = false },
        )
    }
    editing?.let { c ->
        BudgetDialog(
            category = c,
            current = limits[c],
            lastMonthSpend = lastMonth,
            onSave = { cat, l -> onSetLimit(cat, l); editing = null },
            onRemove = { onSetLimit(c, null); editing = null },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun BudgetDialog(
    category: String?,
    current: Long?,
    lastMonthSpend: Map<String, BigDecimal>,
    onSave: (String, Long) -> Unit,
    onRemove: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var chosen by remember { mutableStateOf(category ?: "Food") }
    var text by remember { mutableStateOf(current?.toString().orEmpty()) }
    val limit = text.filter { it.isDigit() }.toLongOrNull()
    val previous = lastMonthSpend[chosen]
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (category == null) "New budget" else "Budget for $category") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (category == null) CategoryPicker(chosen, { chosen = it }, Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter { c -> c.isDigit() }.take(9) },
                    label = { Text("Monthly limit in rupees") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (previous != null && previous.signum() > 0) {
                    TextButton(onClick = { text = previous.setScale(0, java.math.RoundingMode.UP).toPlainString() }) {
                        Text("Use last month's spend (${rupeesWhole(previous)})")
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = limit != null && limit > 0, onClick = { onSave(chosen, limit!!) }) { Text("Save") } },
        dismissButton = {
            Row {
                if (onRemove != null) TextButton(onClick = onRemove) { Text("Remove") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
fun BillsScreen(
    ledger: List<LedgerTxn>,
    onAllowNotifications: () -> Unit,
    notificationsVersion: Int,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val today = remember { LocalDate.now(LedgerBuilder.IST) }
    val bills = remember(ledger) { BillDetector.detect(ledger, today) }
    var remindersOn by remember { mutableStateOf(Alerts.billRemindersOn(context)) }
    val allowed = remember(notificationsVersion) { Alerts.notificationsAllowed(context) }

    LazyColumn(
        modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            Text("Bills and reminders", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 8.dp))
            Text(
                "Payments to the same payee, for a similar amount, about once a month for at least three months.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            InfoCard("Reminders") {
                AlertSwitch("Remind me a day before", "A notification the day before and on the due day", remindersOn) {
                    remindersOn = it
                    Alerts.setBillReminders(context, it)
                    if (it && !allowed) onAllowNotifications()
                }
                NotificationNote(allowed, onAllowNotifications)
            }
        }
        if (bills.isEmpty()) {
            item {
                Text(
                    "No repeating payments found yet. They appear once a payment has been made in three different months.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(bills, key = { it.key }) { b ->
            val days = ChronoUnit.DAYS.between(today, b.nextDue)
            InfoCard(b.name) {
                StatRow("About", rupeesWhole(b.amount))
                StatRow(
                    "Next expected",
                    when {
                        days < 0 -> "${shortDate(b.nextDue)} (overdue?)"
                        days == 0L -> "Today"
                        days == 1L -> "Tomorrow"
                        else -> "${shortDate(b.nextDue)}, in $days days"
                    },
                )
                StatRow("Last paid", shortDate(b.lastDate))
                StatRow("Seen", "${b.times} times")
            }
        }
        item {
            Text(
                "These are guesses from your history, not bills from the bank. If you have already paid, the reminder stops once the next payment is read.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
