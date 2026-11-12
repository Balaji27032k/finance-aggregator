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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.math.BigDecimal
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.pow

private const val HINT = "Touch a chart to see the exact amount"
private val AXIS_WIDTH = 44.dp
private val X_LABELS_HEIGHT = 16.dp

/** A round upper limit for the scale, so the labels read 0, 1k, 2k rather than 0, 1,234, 2,468. */
internal fun niceCeiling(value: Float): Float {
    if (value <= 0f) return 1f
    val magnitude = 10.0.pow(floor(log10(value.toDouble()))).toFloat()
    return listOf(1f, 1.5f, 2f, 2.5f, 3f, 4f, 5f, 7.5f, 10f).map { it * magnitude }.first { it >= value }
}

private fun indexAt(x: Float, left: Float, width: Float, count: Int): Int =
    if (count <= 1 || width <= left) 0 else (((x - left) / (width - left)) * count).toInt().coerceIn(0, count - 1)

/** Faint lines at 0, half and the top of the scale, each labelled in rupees on the left. */
private fun DrawScope.yAxis(measurer: TextMeasurer, top: Float, bottom: Float, left: Float, ceiling: Float, grid: Color, label: Color) {
    val style = TextStyle(fontSize = 10.sp, color = label)
    for (step in 0..2) {
        val value = ceiling * step / 2f
        val y = bottom - (bottom - top) * step / 2f
        drawLine(grid, Offset(left, y), Offset(size.width, y), strokeWidth = if (step == 0) 2f else 1f)
        val text = if (step == 0) "0" else rupeesShort(BigDecimal(value.toDouble()))
        val laid = measurer.measure(text, style)
        drawText(laid, topLeft = Offset(left - laid.size.width - 6f, (y - laid.size.height / 2f).coerceIn(0f, size.height - laid.size.height)))
    }
}

private fun DrawScope.xLabels(measurer: TextMeasurer, left: Float, labels: List<String>, color: Color) {
    if (labels.isEmpty()) return
    val style = TextStyle(fontSize = 10.sp, color = color)
    val picks = if (labels.size <= 2) labels.indices.toList() else listOf(0, labels.size / 2, labels.size - 1)
    picks.forEach { i ->
        val laid = measurer.measure(labels[i], style)
        val slot = (size.width - left) / labels.size
        val x = (left + slot * (i + 0.5f) - laid.size.width / 2f).coerceIn(left, size.width - laid.size.width)
        drawText(laid, topLeft = Offset(x, size.height - laid.size.height))
    }
}

/** Weekly totals for long periods so the chart stays readable on a phone. */
fun weeklyIfLong(daily: List<DayAmount>): List<DayAmount> =
    if (daily.size <= 45) daily else daily.chunked(7).map { w ->
        DayAmount(w.first().date, w.sumOf { it.spend }, w.sumOf { it.income })
    }

@Composable
private fun CaptionLine(text: String?, hint: String) {
    Text(
        text ?: hint,
        style = MaterialTheme.typography.labelMedium,
        color = if (text == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        fontWeight = if (text == null) FontWeight.Normal else FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

/** Money out per day (or per week for long periods), with a rupee scale. Touch a bar to read it. */
@Composable
fun SpendingBars(daily: List<DayAmount>, modifier: Modifier = Modifier) {
    val bars = remember(daily) { weeklyIfLong(daily) }
    val weekly = daily.size > 45
    var selected by remember(daily) { mutableStateOf<Int?>(null) }
    val measurer = rememberTextMeasurer()
    val bar = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val highlight = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    val ceiling = remember(bars) { niceCeiling(bars.maxOfOrNull { it.spend.toFloat() } ?: 0f) }
    val labels = remember(bars) { bars.map { shortDate(it.date) } }

    Column(modifier) {
        CaptionLine(
            selected?.let { i ->
                val b = bars[i]
                val end = if (weekly) " to " + shortDate(b.date.plusDays(6).coerceAtMost(daily.last().date)) else ""
                shortDate(b.date) + end + "   Spent " + rupeesWhole(b.spend)
            },
            "Each bar is the money you spent that " + (if (weekly) "week" else "day") + ". Touch a bar to see it.",
        )
        Canvas(
            Modifier.fillMaxWidth().height(190.dp)
                .pointerInput(bars) { detectTapGestures { selected = indexAt(it.x, AXIS_WIDTH.toPx(), size.width.toFloat(), bars.size) } }
                .pointerInput(bars) { detectHorizontalDragGestures { c, _ -> selected = indexAt(c.position.x, AXIS_WIDTH.toPx(), size.width.toFloat(), bars.size) } },
        ) {
            val left = AXIS_WIDTH.toPx()
            val top = 6f
            val bottom = size.height - X_LABELS_HEIGHT.toPx()
            yAxis(measurer, top, bottom, left, ceiling, grid, muted)
            xLabels(measurer, left, labels, muted)
            val n = bars.size.coerceAtLeast(1)
            val slot = (size.width - left) / n
            val barWidth = slot * 0.7f
            selected?.let { drawRect(highlight, Offset(left + it * slot, top), Size(slot, bottom - top)) }
            bars.forEachIndexed { i, b ->
                val h = (b.spend.toFloat() / ceiling) * (bottom - top)
                if (h > 0f) {
                    drawRoundRect(
                        if (selected == null || selected == i) bar else bar.copy(alpha = 0.45f),
                        Offset(left + i * slot + (slot - barWidth) / 2f, bottom - h),
                        Size(barWidth, h),
                        CornerRadius(barWidth.coerceAtMost(12f) / 2),
                    )
                }
            }
        }
    }
}

private fun java.time.LocalDate.coerceAtMost(limit: java.time.LocalDate): java.time.LocalDate = if (isAfter(limit)) limit else this

/** Money in and out over time as filled lines with a rupee scale; touch or drag to read a day. */
@Composable
fun InOutChart(daily: List<DayAmount>, modifier: Modifier = Modifier) {
    val points = remember(daily) { weeklyIfLong(daily) }
    var selected by remember(daily) { mutableStateOf<Int?>(null) }
    val incomeColor = MoneyColors.income()
    val spendColor = MoneyColors.spend()
    val grid = MaterialTheme.colorScheme.outlineVariant
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    val weekly = daily.size > 45
    val ceiling = remember(points) { niceCeiling(points.maxOfOrNull { maxOf(it.income, it.spend).toFloat() } ?: 0f) }
    val labels = remember(points) { points.map { shortDate(it.date) } }
    Column(modifier) {
        CaptionLine(
            selected?.let { i ->
                val p = points[i]
                (if (weekly) "Week of " else "") + "${shortDate(p.date)}   In ${rupeesWhole(p.income)}   Out ${rupeesWhole(p.spend)}"
            },
            HINT,
        )
        Canvas(
            Modifier.fillMaxWidth().height(190.dp)
                .pointerInput(points) { detectTapGestures { selected = indexAt(it.x, AXIS_WIDTH.toPx(), size.width.toFloat(), points.size) } }
                .pointerInput(points) { detectHorizontalDragGestures { c, _ -> selected = indexAt(c.position.x, AXIS_WIDTH.toPx(), size.width.toFloat(), points.size) } },
        ) {
            val n = points.size
            if (n == 0) return@Canvas
            val left = AXIS_WIDTH.toPx()
            val top = 6f
            val bottom = size.height - X_LABELS_HEIGHT.toPx()
            yAxis(measurer, top, bottom, left, ceiling, grid, muted)
            fun x(i: Int) = if (n == 1) (left + size.width) / 2f else left + i * ((size.width - left) / (n - 1))
            fun y(v: BigDecimal) = bottom - (v.toFloat() / ceiling) * (bottom - top)
            listOf(points.map { it.income } to incomeColor, points.map { it.spend } to spendColor).forEach { (values, color) ->
                val line = Path().apply {
                    values.forEachIndexed { i, v -> if (i == 0) moveTo(x(i), y(v)) else lineTo(x(i), y(v)) }
                }
                val fill = Path().apply {
                    addPath(line)
                    lineTo(x(n - 1), bottom)
                    lineTo(x(0), bottom)
                    close()
                }
                drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.28f), Color.Transparent), startY = top, endY = bottom))
                drawPath(line, color, style = Stroke(width = 4f, cap = StrokeCap.Round))
            }
            xLabels(measurer, left, labels, muted)
            selected?.let { i ->
                drawLine(grid, Offset(x(i), top), Offset(x(i), bottom), strokeWidth = 2f)
                drawCircle(incomeColor, 8f, Offset(x(i), y(points[i].income)))
                drawCircle(spendColor, 8f, Offset(x(i), y(points[i].spend)))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Dot(incomeColor); Text(" In   ", style = MaterialTheme.typography.labelSmall)
            Dot(spendColor); Text(" Out", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun Dot(color: Color) {
    Box(Modifier.size(8.dp).clip(CircleShape).background(color))
}

/** Grouped bars with one or two series and a rupee scale. Touching a group shows its exact values above the chart. */
@Composable
fun GroupedBars(
    labels: List<String>,
    series: List<Pair<Color, List<BigDecimal>>>,
    caption: (Int) -> String,
    modifier: Modifier = Modifier,
    labelEvery: Int = 1,
    height: Int = 150,
) {
    var selected by remember(labels, series) { mutableStateOf<Int?>(null) }
    val grid = MaterialTheme.colorScheme.outlineVariant
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val highlight = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
    val measurer = rememberTextMeasurer()
    val ceiling = niceCeiling(series.flatMap { it.second }.maxOrNull()?.toFloat() ?: 0f)
    Column(modifier) {
        CaptionLine(selected?.let(caption), HINT)
        Canvas(
            Modifier.fillMaxWidth().height(height.dp)
                .pointerInput(labels) { detectTapGestures { selected = indexAt(it.x, AXIS_WIDTH.toPx(), size.width.toFloat(), labels.size) } }
                .pointerInput(labels) { detectHorizontalDragGestures { c, _ -> selected = indexAt(c.position.x, AXIS_WIDTH.toPx(), size.width.toFloat(), labels.size) } },
        ) {
            val left = AXIS_WIDTH.toPx()
            val top = 6f
            val bottom = size.height
            yAxis(measurer, top, bottom, left, ceiling, grid, muted)
            val groups = labels.size.coerceAtLeast(1)
            val groupWidth = (size.width - left) / groups
            val barWidth = groupWidth * 0.72f / series.size.coerceAtLeast(1)
            selected?.let { drawRect(highlight, Offset(left + it * groupWidth, top), Size(groupWidth, bottom - top)) }
            for (i in labels.indices) {
                series.forEachIndexed { k, (color, values) ->
                    val h = (values[i].toFloat() / ceiling) * (bottom - top)
                    if (h > 0f) {
                        drawRoundRect(
                            color,
                            Offset(left + i * groupWidth + groupWidth * 0.14f + k * barWidth, bottom - h),
                            Size(barWidth - 1f, h),
                            CornerRadius(barWidth.coerceAtMost(12f) / 2),
                        )
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = AXIS_WIDTH, top = 4.dp)) {
            labels.forEachIndexed { i, l ->
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    if (i % labelEvery == 0) Text(l, fontSize = 10.sp, maxLines = 1, softWrap = false, textAlign = TextAlign.Center, color = muted)
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
            CaptionLine(
                selected?.let { cells[it] }?.let { "${shortDate(it.date)}   " + if (it.spend.signum() > 0) rupeesWhole(it.spend) + " spent" else "nothing spent" },
                "Each square is a day. Touch one to see what you spent.",
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
                    val topLeft = Offset((i / 7) * cellPx + pad, (i % 7) * cellPx + pad)
                    val box = Size(cellPx - 2 * pad, cellPx - 2 * pad)
                    drawRoundRect(color, topLeft, box, CornerRadius(cellPx * 0.2f))
                    if (i == selected) drawRoundRect(Color.White.copy(alpha = 0.9f), topLeft, box, CornerRadius(cellPx * 0.2f), style = Stroke(width = 3f))
                }
            }
        }
    }
}

/** A ring split by category. Touch a slice to see that category's total in the middle. */
@Composable
fun CategoryDonut(buckets: List<Bucket>, modifier: Modifier = Modifier) {
    val total = buckets.sumOf { it.total }
    if (total.signum() <= 0) return
    var selected by remember(buckets) { mutableStateOf<Int?>(null) }
    val sweeps = remember(buckets) { buckets.map { (it.total.toFloat() / total.toFloat()) * 360f } }
    Box(modifier.size(200.dp), contentAlignment = Alignment.Center) {
        Canvas(
            Modifier.size(200.dp).pointerInput(buckets) {
                detectTapGestures { o ->
                    val dx = o.x - size.width / 2f
                    val dy = o.y - size.height / 2f
                    val radius = min(size.width, size.height) / 2f
                    val dist = hypot(dx, dy)
                    if (dist < radius * 0.5f || dist > radius) {
                        selected = null
                        return@detectTapGestures
                    }
                    // Zero degrees is at the top, running clockwise, the same as the ring is drawn.
                    val angle = ((Math.toDegrees(atan2(dy, dx).toDouble()) + 450.0) % 360.0).toFloat()
                    var start = 0f
                    sweeps.forEachIndexed { i, s ->
                        if (angle >= start && angle < start + s) selected = if (selected == i) null else i
                        start += s
                    }
                }
            },
        ) {
            var start = -90f
            buckets.forEachIndexed { i, b ->
                val picked = selected == i
                val stroke = if (picked) 38f else 28f
                val inset = 19f
                drawArc(
                    CategoryColors.of(b.key).copy(alpha = if (selected == null || picked) 1f else 0.35f),
                    start, (sweeps[i] - 1.5f).coerceAtLeast(0.5f), useCenter = false,
                    topLeft = Offset(inset, inset), size = Size(size.width - 2 * inset, size.height - 2 * inset), style = Stroke(stroke),
                )
                start += sweeps[i]
            }
        }
        val pick = selected?.let { buckets[it] }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 40.dp)) {
            if (pick == null) {
                Text("Spent", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(rupeesWhole(total), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Touch a slice", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(pick.key, style = MaterialTheme.typography.labelMedium, color = CategoryColors.of(pick.key), maxLines = 2, textAlign = TextAlign.Center)
                Text(rupeesWhole(pick.total), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "${pick.total.multiply(BigDecimal(100)).divide(total, 0, java.math.RoundingMode.HALF_UP)}% \u00b7 ${pick.count} payment${if (pick.count == 1) "" else "s"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
