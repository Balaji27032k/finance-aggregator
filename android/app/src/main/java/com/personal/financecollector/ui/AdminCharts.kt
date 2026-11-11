package com.personal.financecollector.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.math.BigDecimal

private fun indexAt(x: Float, width: Int, count: Int): Int =
    if (count <= 1 || width <= 0) 0 else ((x / width) * count).toInt().coerceIn(0, count - 1)

private const val HINT = "Touch a chart to see the exact amount"

/** Weekly totals for long periods so the chart stays readable on a phone. */
fun weeklyIfLong(daily: List<DayAmount>): List<DayAmount> =
    if (daily.size <= 45) daily else daily.chunked(7).map { w ->
        DayAmount(w.first().date, w.sumOf { it.spend }, w.sumOf { it.income })
    }

/** Money in and out over time as filled lines; touch or drag to read a day. */
@Composable
fun InOutChart(daily: List<DayAmount>, modifier: Modifier = Modifier) {
    val points = remember(daily) { weeklyIfLong(daily) }
    var selected by remember(daily) { mutableStateOf<Int?>(null) }
    val incomeColor = MoneyColors.income()
    val spendColor = MoneyColors.spend()
    val grid = MaterialTheme.colorScheme.outlineVariant
    val weekly = daily.size > 45
    Column(modifier) {
        Text(
            selected?.let { i ->
                val p = points[i]
                (if (weekly) "Week of " else "") + "${shortDate(p.date)}   In ${rupeesWhole(p.income)}   Out ${rupeesWhole(p.spend)}"
            } ?: HINT,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (selected == null) FontWeight.Normal else FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        Canvas(
            Modifier.fillMaxWidth().height(170.dp)
                .pointerInput(points) { detectTapGestures { selected = indexAt(it.x, size.width, points.size) } }
                .pointerInput(points) { detectHorizontalDragGestures { change, _ -> selected = indexAt(change.position.x, size.width, points.size) } },
        ) {
            val n = points.size
            if (n == 0) return@Canvas
            val max = points.maxOf { maxOf(it.income, it.spend) }.toFloat().coerceAtLeast(1f)
            fun x(i: Int) = if (n == 1) size.width / 2 else i * (size.width / (n - 1))
            fun y(v: BigDecimal) = size.height - (v.toFloat() / max) * (size.height - 10f) - 2f
            for (g in 1..3) {
                val gy = size.height * g / 4f
                drawLine(grid.copy(alpha = 0.5f), Offset(0f, gy), Offset(size.width, gy), strokeWidth = 1f)
            }
            listOf(points.map { it.income } to incomeColor, points.map { it.spend } to spendColor).forEach { (values, color) ->
                val line = Path().apply {
                    values.forEachIndexed { i, v -> if (i == 0) moveTo(x(i), y(v)) else lineTo(x(i), y(v)) }
                }
                val fill = Path().apply {
                    addPath(line)
                    lineTo(x(n - 1), size.height)
                    lineTo(x(0), size.height)
                    close()
                }
                drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.28f), Color.Transparent)))
                drawPath(line, color, style = Stroke(width = 4f, cap = StrokeCap.Round))
            }
            selected?.let { i ->
                drawLine(grid, Offset(x(i), 0f), Offset(x(i), size.height), strokeWidth = 2f)
                drawCircle(incomeColor, 8f, Offset(x(i), y(points[i].income)))
                drawCircle(spendColor, 8f, Offset(x(i), y(points[i].spend)))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(points.firstOrNull()?.let { shortDate(it.date) }.orEmpty(), style = MaterialTheme.typography.labelSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Dot(incomeColor); Text("In", style = MaterialTheme.typography.labelSmall)
                Dot(spendColor); Text("Out", style = MaterialTheme.typography.labelSmall)
            }
            Text(points.lastOrNull()?.let { shortDate(it.date) }.orEmpty(), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun Dot(color: Color) {
    Box(Modifier.size(8.dp).clip(CircleShape).background(color))
}

/**
 * Grouped bars with one or two series. Touching a group shows its exact values in the line above the chart.
 * [labelEvery] thins the labels under the bars so they never crowd on a narrow screen.
 */
@Composable
fun GroupedBars(
    labels: List<String>,
    series: List<Pair<Color, List<BigDecimal>>>,
    caption: (Int) -> String,
    modifier: Modifier = Modifier,
    labelEvery: Int = 1,
    height: Int = 130,
) {
    var selected by remember(labels, series) { mutableStateOf<Int?>(null) }
    val grid = MaterialTheme.colorScheme.outlineVariant
    val highlight = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
    val max = series.flatMap { it.second }.maxOrNull()?.toFloat()?.coerceAtLeast(1f) ?: 1f
    Column(modifier) {
        Text(
            selected?.let(caption) ?: HINT,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (selected == null) FontWeight.Normal else FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        Canvas(
            Modifier.fillMaxWidth().height(height.dp)
                .pointerInput(labels) { detectTapGestures { selected = indexAt(it.x, size.width, labels.size) } }
                .pointerInput(labels) { detectHorizontalDragGestures { change, _ -> selected = indexAt(change.position.x, size.width, labels.size) } },
        ) {
            val groups = labels.size.coerceAtLeast(1)
            val groupWidth = size.width / groups
            val barWidth = groupWidth * 0.72f / series.size.coerceAtLeast(1)
            drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 2f)
            selected?.let { drawRect(highlight, Offset(it * groupWidth, 0f), Size(groupWidth, size.height)) }
            for (i in labels.indices) {
                series.forEachIndexed { k, (color, values) ->
                    val h = (values[i].toFloat() / max) * (size.height - 6f)
                    if (h > 0f) {
                        drawRoundRect(
                            color,
                            Offset(i * groupWidth + groupWidth * 0.14f + k * barWidth, size.height - h),
                            Size(barWidth - 1f, h),
                            CornerRadius(barWidth.coerceAtMost(12f) / 2),
                        )
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            labels.forEachIndexed { i, l ->
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    if (i % labelEvery == 0) Text(l, fontSize = 10.sp, maxLines = 1, softWrap = false, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** One square per day in week columns (Monday on top); darker means more spent. Touch a square to read it. */
@Composable
fun SpendCalendar(daily: List<DayAmount>, modifier: Modifier = Modifier) {
    val lead = remember(daily) { daily.firstOrNull()?.date?.dayOfWeek?.value?.minus(1) ?: 0 }
    val cells = remember(daily) { List(lead) { null as DayAmount? } + daily }
    val weeks = (cells.size + 6) / 7
    val max = remember(daily) { daily.maxOfOrNull { it.spend }?.toFloat()?.takeIf { it > 0f } ?: 1f }
    val empty = MaterialTheme.colorScheme.surfaceContainerHighest
    val hot = MoneyColors.spend()
    var selected by remember(daily) { mutableStateOf<Int?>(null) }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val cell = (maxWidth / weeks.coerceAtLeast(1)).coerceAtMost(30.dp)
        Column {
            Text(
                selected?.let { cells[it] }?.let { "${shortDate(it.date)}   " + if (it.spend.signum() > 0) rupeesWhole(it.spend) + " spent" else "nothing spent" }
                    ?: "Each square is a day. Touch one to see what you spent.",
                style = MaterialTheme.typography.labelMedium,
                color = if (selected == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            val density = androidx.compose.ui.platform.LocalDensity.current
            val cellPx = with(density) { cell.toPx() }
            Canvas(
                Modifier.height(cell * 7).fillMaxWidth().pointerInput(cells, cellPx) {
                    detectTapGestures { o ->
                        val col = (o.x / cellPx).toInt()
                        val row = (o.y / cellPx).toInt()
                        val idx = col * 7 + row
                        selected = if (row in 0..6 && idx in cells.indices && cells[idx] != null) idx else null
                    }
                },
            ) {
                val pad = cellPx * 0.12f
                cells.forEachIndexed { i, d ->
                    if (d == null) return@forEachIndexed
                    val share = d.spend.toFloat() / max
                    val color = if (share <= 0f) empty else hot.copy(alpha = 0.25f + 0.75f * share)
                    drawRoundRect(
                        color,
                        Offset((i / 7) * cellPx + pad, (i % 7) * cellPx + pad),
                        Size(cellPx - 2 * pad, cellPx - 2 * pad),
                        CornerRadius(cellPx * 0.2f),
                    )
                    if (i == selected) {
                        drawRoundRect(
                            Color.White.copy(alpha = 0.9f),
                            Offset((i / 7) * cellPx + pad, (i % 7) * cellPx + pad),
                            Size(cellPx - 2 * pad, cellPx - 2 * pad),
                            CornerRadius(cellPx * 0.2f),
                            style = Stroke(width = 3f),
                        )
                    }
                }
            }
        }
    }
}

/** A ring split by category with the total in the middle. */
@Composable
fun CategoryDonut(buckets: List<Bucket>, modifier: Modifier = Modifier) {
    val total = buckets.sumOf { it.total }
    if (total.signum() <= 0) return
    Box(modifier.size(170.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(170.dp)) {
            val stroke = 26f
            val inset = stroke / 2
            var start = -90f
            buckets.forEach { b ->
                val sweep = (b.total.toFloat() / total.toFloat()) * 360f
                drawArc(
                    CategoryColors.of(b.key), start, (sweep - 1.5f).coerceAtLeast(0.5f), useCenter = false,
                    topLeft = Offset(inset, inset), size = Size(size.width - stroke, size.height - stroke), style = Stroke(stroke),
                )
                start += sweep
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Spent", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(rupeesWhole(total), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun HighlightsCard(highlights: List<String>) {
    if (highlights.isEmpty()) return
    InfoCard("Highlights") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            highlights.forEach { text ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                    Box(Modifier.padding(top = 7.dp).size(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                    Text(text)
                }
            }
        }
    }
}

fun monthShort(month: String): String {
    val parts = month.split('-')
    if (parts.size != 2) return month
    val names = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    val m = parts[1].toIntOrNull() ?: return month
    return names.getOrElse(m - 1) { month }
}
