package com.personal.financecollector.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

@Composable
fun OverviewScreen(
    ledger: List<LedgerTxn>,
    budget: Long?,
    onSetBudget: (Long?) -> Unit,
    onOpen: (LedgerTxn) -> Unit,
    onOpenCategory: (String) -> Unit,
    onOpenPayee: (String) -> Unit,
    onSeeAll: () -> Unit,
    categoryLimits: Map<String, Long>,
    netWorthExcluded: Set<String>,
    balanceSnapshots: List<AccountBalance>,
    myAccounts: Set<String>,
    onOpenBudgets: () -> Unit,
    onOpenBills: () -> Unit,
    onOpenAccounts: () -> Unit,
    onAskAi: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var period by rememberSaveable { mutableStateOf(Period.MONTH) }
    val today = remember { LocalDate.now(LedgerBuilder.IST) }
    val (from, to) = period.range(today)
    val stats = remember(ledger, period) { StatsCalculator.compute(ledger, from, to, period.previous(today)) }
    val pace = remember(ledger) { StatsCalculator.monthPace(ledger, today) }
    val netWorth = remember(ledger, netWorthExcluded, myAccounts, balanceSnapshots) { NetWorthCalculator.compute(ledger, netWorthExcluded, today, myAccounts, balanceSnapshots) }
    val budgets = remember(ledger, categoryLimits) { BudgetCalculator.statuses(ledger, categoryLimits, java.time.YearMonth.from(today)) }
    val bills = remember(ledger) { BillDetector.detect(ledger, today) }

    LazyColumn(
        modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            Text(greeting(), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 8.dp))
            Text(
                "${shortDate(stats.from)} to ${shortDate(stats.to)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(Period.entries) { p -> FilterChip(period == p, { period = p }, { Text(p.label) }) }
            }
        }
        if (ledger.isEmpty()) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Text(
                        "Nothing to show yet. Bank SMS and e-mails are read automatically; open the Health tab to check they are connected.",
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            return@LazyColumn
        }
        item { HeroCard(stats) }
        item {
            InfoCard("AI analysis") {
                Text(
                    "A short plain-words summary of this period. It runs only when you tap.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { onAskAi("Summarise my spending from ${stats.from} to ${stats.to}") }) { Text("Analyse this view") }
            }
        }
        item {
            InfoCard("Spending per " + if (stats.days > 45) "week" else "day") {
                SpendingBars(stats.daily)
            }
        }
        if (stats.byCategory.isNotEmpty()) {
            item { CategoryCard(stats, onOpenCategory) }
        }
        item { PaceCard(pace, budget, onSetBudget) }
        item { BudgetsCard(budgets, onOpenBudgets) }
        item { BillsCard(bills, today, onOpenBills) }
        item { NetWorthCard(netWorth, onOpenAccounts) }
        if (stats.highlights.isNotEmpty()) {
            item {
                InfoCard("Insights") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        stats.highlights.forEach { text ->
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                                Box(Modifier.padding(top = 7.dp).size(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                                Text(text)
                            }
                        }
                    }
                }
            }
        }
        if (stats.topPayees.isNotEmpty()) {
            item { TopPayeesCard("Top payees", stats.topPayees, onOpenPayee) }
        }
        item {
            InfoCard("Recent") {
                Column {
                    ledger.take(5).forEach { t -> TxnRow(t, onClick = { onOpen(t) }) }
                    TextButton(onClick = onSeeAll, modifier = Modifier.align(Alignment.End)) { Text("See all") }
                }
            }
        }
        item {
            Text(
                "Amounts come straight from your bank messages. A transfer between your own accounts shows as money out of one and money in to the other.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun greeting(): String {
    val hour = java.time.LocalTime.now(LedgerBuilder.IST).hour
    return when {
        hour < 5 -> "Late night"
        hour < 12 -> "Good morning"
        hour < 17 -> "Good afternoon"
        else -> "Good evening"
    }
}

/** A rupee amount that counts up to its value. */
@Composable
private fun AnimatedRupees(value: BigDecimal, style: androidx.compose.ui.text.TextStyle, color: androidx.compose.ui.graphics.Color, bold: Boolean = false) {
    val shown by animateFloatAsState(value.toFloat(), animationSpec = tween(700), label = "amount")
    Text(
        rupeesWhole(BigDecimal(shown.toDouble())),
        style = style,
        color = color,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.SemiBold,
    )
}

@Composable
internal fun InfoCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 10.dp))
            content()
        }
    }
}

@Composable
internal fun TopPayeesCard(title: String, items: List<Bucket>, onOpen: ((String) -> Unit)? = null) {
    InfoCard(title) {
        val max = items.first().total.toFloat().coerceAtLeast(1f)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items.forEach { p ->
                Column(if (onOpen != null && p.key != "Unknown") Modifier.clickable { onOpen(p.key) } else Modifier) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(prettyName(p.key), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(rupeesWhole(p.total), fontWeight = FontWeight.SemiBold)
                    }
                    LinearProgressIndicator(
                        progress = { p.total.toFloat() / max },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(6.dp).clip(RoundedCornerShape(3.dp)),
                    )
                }
            }
        }
    }
}

private fun percentChange(current: BigDecimal, previous: BigDecimal): Int? =
    if (previous.signum() == 0) null
    else current.subtract(previous).multiply(BigDecimal(100)).divide(previous, 0, RoundingMode.HALF_UP).toInt()

@Composable
internal fun HeroCard(stats: Stats) {
    val gradient = Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary))
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(gradient).padding(20.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Spent", color = onPrimary.copy(alpha = 0.85f))
            AnimatedRupees(stats.totals.spend, MaterialTheme.typography.displaySmall, onPrimary, bold = true)
            val change = percentChange(stats.totals.spend, stats.previous.spend)
            Text(
                when {
                    change == null -> "Nothing spent in ${stats.previousLabel} to compare with"
                    change == 0 -> "About the same as ${stats.previousLabel}"
                    change > 0 -> "Up $change% from ${stats.previousLabel}"
                    else -> "Down ${-change}% from ${stats.previousLabel}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = onPrimary.copy(alpha = 0.85f),
            )
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column {
                    Text("Received", style = MaterialTheme.typography.labelMedium, color = onPrimary.copy(alpha = 0.8f))
                    Text(rupeesWhole(stats.totals.income), fontWeight = FontWeight.SemiBold, color = onPrimary)
                }
                Column {
                    Text("Kept", style = MaterialTheme.typography.labelMedium, color = onPrimary.copy(alpha = 0.8f))
                    val net = stats.totals.net
                    Text((if (net.signum() < 0) "-" else "") + rupeesWhole(net.abs()), fontWeight = FontWeight.SemiBold, color = onPrimary)
                }
                Column {
                    Text("Per day", style = MaterialTheme.typography.labelMedium, color = onPrimary.copy(alpha = 0.8f))
                    Text(
                        rupeesWhole(stats.totals.spend.divide(BigDecimal(stats.days.coerceAtLeast(1)), 0, RoundingMode.HALF_UP)),
                        fontWeight = FontWeight.SemiBold,
                        color = onPrimary,
                    )
                }
            }
        }
    }
}

@Composable
internal fun CategoryCard(stats: Stats, onOpenCategory: (String) -> Unit) {
    val total = stats.byCategory.sumOf { it.total }
    InfoCard("Where it went") {
        Text(
            "Touch a slice for its total. Tap a name to see those transactions.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CategoryDonut(stats.byCategory) }
        Column(Modifier.padding(top = 8.dp)) {
            stats.byCategory.take(6).forEach { b ->
                Row(
                    Modifier.fillMaxWidth().clickable { onOpenCategory(b.key) }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(CategoryColors.of(b.key)))
                    Text(b.key, modifier = Modifier.weight(1f))
                    Text(
                        "${b.total.multiply(BigDecimal(100)).divide(total, 0, RoundingMode.HALF_UP)}%",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(rupeesWhole(b.total), fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
internal fun PaceCard(pace: MonthPace, budget: Long?, onSetBudget: (Long?) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    InfoCard("This month's pace") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Column {
                Text(rupeesWhole(pace.spentSoFar), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    "spent in ${pace.daysElapsed} of ${pace.daysInMonth} days",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { editing = true }) { Text(if (budget == null) "Set budget" else "Change") }
        }
        val dayShare = pace.daysElapsed.toFloat() / pace.daysInMonth
        if (budget == null) {
            LinearProgressIndicator(
                progress = { dayShare },
                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp).height(8.dp).clip(RoundedCornerShape(4.dp)),
            )
            Text("At this pace the month ends near ${rupeesWhole(pace.projected)}.")
            Text(
                "Based on what you have spent so far plus your usual ${rupeesWhole(pace.dailyRate)} a day for the days left. One-off big payments are not repeated.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val budgetValue = BigDecimal(budget)
            val used = (pace.spentSoFar.toFloat() / budget.toFloat()).coerceIn(0f, 1f)
            val over = pace.spentSoFar > budgetValue
            val risk = pace.projected > budgetValue
            LinearProgressIndicator(
                progress = { used },
                color = when {
                    over -> MoneyColors.spend()
                    risk -> androidx.compose.ui.graphics.Color(0xFFF59E0B)
                    else -> MoneyColors.income()
                },
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(10.dp).clip(RoundedCornerShape(5.dp)),
            )
            Text(
                "${(used * 100).toInt()}% of ${rupeesWhole(budgetValue)} used, ${(dayShare * 100).toInt()}% of the month gone",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 6.dp),
            )
            val daysLeft = (pace.daysInMonth - pace.daysElapsed).coerceAtLeast(1)
            val perDay = (budgetValue - pace.spentSoFar).max(BigDecimal.ZERO).divide(BigDecimal(daysLeft), 0, RoundingMode.HALF_UP)
            Text(
                when {
                    over -> "You are ${rupeesWhole(pace.spentSoFar - budgetValue)} over budget."
                    risk -> "At this pace you will pass the budget by about ${rupeesWhole(pace.projected - budgetValue)}. Keep to about ${rupeesWhole(perDay)} a day to stay within it."
                    else -> "On track. You can spend about ${rupeesWhole(perDay)} a day for the rest of the month."
                },
            )
        }
    }
    if (editing) BudgetDialog(budget, onDismiss = { editing = false }, onSave = { onSetBudget(it); editing = false })
}

@Composable
private fun BudgetDialog(current: Long?, onDismiss: () -> Unit, onSave: (Long?) -> Unit) {
    var text by remember { mutableStateOf(current?.toString().orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Monthly budget") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.filter(Char::isDigit).take(9) },
                label = { Text("Amount in rupees") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        },
        confirmButton = { TextButton(onClick = { onSave(text.toLongOrNull()?.takeIf { it > 0 }) }) { Text("Save") } },
        dismissButton = {
            TextButton(onClick = { if (current != null) onSave(null) else onDismiss() }) { Text(if (current != null) "Remove" else "Cancel") }
        },
    )
}
