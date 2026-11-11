package com.personal.financecollector.ui

import androidx.compose.foundation.background
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
    onOpen: (LedgerTxn) -> Unit,
    onSeeAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var period by rememberSaveable { mutableStateOf(Period.MONTH) }
    val today = remember { LocalDate.now(LedgerBuilder.IST) }
    val (from, to) = period.range(today)
    val stats = remember(ledger, period) { StatsCalculator.compute(ledger, from, to) }
    val pace = remember(ledger) { StatsCalculator.monthPace(ledger, today) }

    LazyColumn(
        modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            Text("Overview", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 8.dp))
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
            InfoCard("Daily spending") {
                SpendBars(chartBars(stats.daily))
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(shortDate(stats.from), style = MaterialTheme.typography.labelSmall)
                    Text(if (stats.days > 45) "weekly totals" else "per day", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(shortDate(stats.to), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        if (stats.byCategory.isNotEmpty()) {
            item { CategoryCard(stats) }
        }
        item { PaceCard(pace) }
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
            item {
                InfoCard("Top payees") {
                    val max = stats.topPayees.first().total.toFloat().coerceAtLeast(1f)
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        stats.topPayees.forEach { p ->
                            Column {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(p.key, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
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
        }
        item {
            InfoCard("Recent") {
                Column {
                    ledger.take(5).forEach { t -> TxnRow(t, onClick = { onOpen(t) }) }
                    TextButton(onClick = onSeeAll, modifier = Modifier.align(Alignment.End)) { Text("See all") }
                }
            }
        }
    }
}

@Composable
private fun InfoCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 10.dp))
            content()
        }
    }
}

private fun percentChange(current: BigDecimal, previous: BigDecimal): Int? =
    if (previous.signum() == 0) null
    else current.subtract(previous).multiply(BigDecimal(100)).divide(previous, 0, RoundingMode.HALF_UP).toInt()

@Composable
private fun HeroCard(stats: Stats) {
    val gradient = Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary))
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(gradient).padding(20.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Spent", color = onPrimary.copy(alpha = 0.85f))
            Text(rupeesWhole(stats.totals.spend), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = onPrimary)
            val change = percentChange(stats.totals.spend, stats.previous.spend)
            Text(
                when {
                    change == null -> "No spending in the previous ${stats.days} days to compare"
                    change == 0 -> "Same as the previous ${stats.days} days"
                    change > 0 -> "Up $change% from the previous ${stats.days} days"
                    else -> "Down ${-change}% from the previous ${stats.days} days"
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
private fun CategoryCard(stats: Stats) {
    val total = stats.byCategory.sumOf { it.total }
    InfoCard("Where it went") {
        CategoryStack(stats.byCategory)
        Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            stats.byCategory.take(6).forEach { b ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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
private fun PaceCard(pace: MonthPace) {
    InfoCard("This month's pace") {
        Text(rupeesWhole(pace.spentSoFar), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "spent in ${pace.daysElapsed} of ${pace.daysInMonth} days",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LinearProgressIndicator(
            progress = { pace.daysElapsed.toFloat() / pace.daysInMonth },
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp).height(8.dp).clip(RoundedCornerShape(4.dp)),
        )
        Text("At this pace the month ends near ${rupeesWhole(pace.projected)}.")
    }
}
