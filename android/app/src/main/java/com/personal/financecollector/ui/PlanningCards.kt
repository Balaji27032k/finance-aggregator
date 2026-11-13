package com.personal.financecollector.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val monthYear = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH)

/** Total of the latest bank balances, how it changed this month, and its path over the last year. Touch the line to read a point. */
@Composable
fun NetWorthCard(netWorth: NetWorth, onOpenAccounts: () -> Unit) {
    InfoCard("Net worth") {
        if (netWorth.accounts.isEmpty()) {
            Text(
                "Bank messages that show your balance are used to add this up. None have been read yet.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@InfoCard
        }
        Text(EventPresenter.rupees(netWorth.total), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        netWorth.change?.let { change ->
            val up = change.signum() >= 0
            Text(
                (if (up) "Up " else "Down ") + EventPresenter.rupees(change.abs()) + " in the last 30 days",
                style = MaterialTheme.typography.bodySmall,
                color = if (up) MoneyColors.income() else MoneyColors.spend(),
            )
        }
        if (netWorth.series.size >= 2) TrendLine(netWorth.series, Modifier.padding(top = 10.dp))
        Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            netWorth.accounts.take(5).forEach { a -> StatRow("${a.bankCode} ending ${a.last4} \u00b7 ${shortDate(java.time.Instant.ofEpochMilli(a.asOfMs).atZone(LedgerBuilder.IST).toLocalDate())}", EventPresenter.rupees(a.balance)) }
        }
        Text(
            "Balances are as of each account's latest message. Credit cards and other accounts can be left out under My accounts.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
        TextButton(onClick = onOpenAccounts) { Text("Choose accounts") }
    }
}

@Composable
private fun TrendLine(points: List<NetWorthPoint>, modifier: Modifier = Modifier) {
    var selected by remember(points) { mutableStateOf<Int?>(null) }
    val measurer = rememberTextMeasurer()
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val lo = points.minOf { it.total.toFloat() }
    val hi = points.maxOf { it.total.toFloat() }
    val pad = ((hi - lo) * 0.08f).coerceAtLeast(1f)
    val bottomValue = lo - pad
    val topValue = hi + pad
    Column(modifier) {
        Text(
            selected?.let { points[it] }?.let { "${it.date.format(monthYear)}   ${EventPresenter.rupees(it.total)}" } ?: "Touch the line to see a month",
            style = MaterialTheme.typography.labelMedium,
            color = if (selected == null) muted else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        Canvas(
            Modifier.fillMaxWidth().height(150.dp)
                .pointerInput(points) { detectTapGestures { selected = nearest(it.x, size.width.toFloat(), 44.dp.toPx(), points.size) } }
                .pointerInput(points) { detectHorizontalDragGestures { c, _ -> selected = nearest(c.position.x, size.width.toFloat(), 44.dp.toPx(), points.size) } },
        ) {
            val left = 44.dp.toPx()
            val top = 6f
            val bottom = size.height - 16.dp.toPx()
            val style = TextStyle(fontSize = 10.sp, color = muted)
            for (step in 0..2) {
                val value = bottomValue + (topValue - bottomValue) * step / 2f
                val y = bottom - (bottom - top) * step / 2f
                drawLine(grid, Offset(left, y), Offset(size.width, y), strokeWidth = 1f)
                val laid = measurer.measure(rupeesShort(BigDecimal(value.toDouble())), style)
                drawText(laid, topLeft = Offset(left - laid.size.width - 6f, (y - laid.size.height / 2f).coerceIn(0f, size.height - laid.size.height)))
            }
            fun x(i: Int) = left + i * ((size.width - left) / (points.size - 1))
            fun y(v: BigDecimal) = bottom - ((v.toFloat() - bottomValue) / (topValue - bottomValue)) * (bottom - top)
            val path = Path().apply { points.forEachIndexed { i, p -> if (i == 0) moveTo(x(i), y(p.total)) else lineTo(x(i), y(p.total)) } }
            val fill = Path().apply {
                addPath(path)
                lineTo(x(points.size - 1), bottom)
                lineTo(x(0), bottom)
                close()
            }
            drawPath(fill, Brush.verticalGradient(listOf(line.copy(alpha = 0.25f), Color.Transparent), startY = top, endY = bottom))
            drawPath(path, line, style = Stroke(width = 4f, cap = StrokeCap.Round))
            listOf(0, points.size - 1).forEach { i ->
                val laid = measurer.measure(points[i].date.format(DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH)), style)
                val tx = (x(i) - laid.size.width / 2f).coerceIn(left, size.width - laid.size.width)
                drawText(laid, topLeft = Offset(tx, size.height - laid.size.height))
            }
            selected?.let { i ->
                drawLine(grid, Offset(x(i), top), Offset(x(i), bottom), strokeWidth = 2f)
                drawCircle(line, 9f, Offset(x(i), y(points[i].total)))
            }
        }
    }
}

private fun nearest(x: Float, width: Float, left: Float, count: Int): Int =
    if (count <= 1) 0 else (((x - left) / (width - left)) * (count - 1) + 0.5f).toInt().coerceIn(0, count - 1)

/** The categories closest to their monthly limits. */
@Composable
fun BudgetsCard(statuses: List<BudgetStatus>, onOpen: () -> Unit) {
    InfoCard("Category budgets this month") {
        if (statuses.isEmpty()) {
            Text(
                "Set a monthly limit for Food, Transport or any category and get an alert at 80% and 100%.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            statuses.take(4).forEach { s ->
                Column {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(s.category, modifier = Modifier.weight(1f))
                        Text("${rupeesWhole(s.spent)} of ${rupeesWhole(BigDecimal(s.limit))}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    LinearProgressIndicator(
                        progress = { (s.percent / 100f).coerceIn(0f, 1f) },
                        color = budgetColor(s),
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(8.dp).clip(RoundedCornerShape(4.dp)),
                    )
                }
            }
        }
        TextButton(onClick = onOpen) { Text(if (statuses.isEmpty()) "Set budgets" else "All budgets") }
    }
}

@Composable
fun budgetColor(s: BudgetStatus): Color = when {
    s.percent >= 100 -> MoneyColors.spend()
    s.percent >= 80 -> Color(0xFFF59E0B)
    else -> MoneyColors.income()
}

/** Repeating payments coming up in the next two weeks. */
@Composable
fun BillsCard(bills: List<Bill>, today: LocalDate, onOpen: () -> Unit) {
    val soon = bills.filter { !it.nextDue.isAfter(today.plusDays(14)) }
    InfoCard("Coming up") {
        if (soon.isEmpty()) {
            Text(
                if (bills.isEmpty()) "Payments that repeat about every month (rent, subscriptions, recharges) are spotted automatically."
                else "No repeating payment is due in the next two weeks.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            soon.take(4).forEach { b ->
                val days = java.time.temporal.ChronoUnit.DAYS.between(today, b.nextDue)
                StatRow(
                    b.name + "\n" + when {
                        days < 0 -> "was due ${shortDate(b.nextDue)}"
                        days == 0L -> "due today"
                        days == 1L -> "due tomorrow"
                        else -> "due ${shortDate(b.nextDue)}, in $days days"
                    },
                    "about " + rupeesWhole(b.amount),
                )
            }
        }
        TextButton(onClick = onOpen) { Text("Bills and reminders") }
    }
}
