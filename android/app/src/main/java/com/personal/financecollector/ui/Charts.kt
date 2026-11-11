package com.personal.financecollector.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import java.math.BigDecimal
import java.time.LocalDate

/** Plain bars, one per day (or per week for long periods). The tallest bar is the maximum shown in the label. */
fun chartBars(daily: List<DayAmount>): List<Pair<LocalDate, BigDecimal>> {
    if (daily.size <= 45) return daily.map { it.date to it.spend }
    return daily.chunked(7).map { week -> week.first().date to week.sumOf { it.spend } }
}

@Composable
fun SpendBars(bars: List<Pair<LocalDate, BigDecimal>>, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    val faint = MaterialTheme.colorScheme.outlineVariant
    val max = bars.maxOfOrNull { it.second }?.takeIf { it.signum() > 0 } ?: BigDecimal.ONE
    Canvas(modifier.fillMaxWidth().height(140.dp)) {
        val count = bars.size.coerceAtLeast(1)
        val gap = size.width / count * 0.25f
        val barWidth = (size.width - gap * (count - 1)) / count
        drawLine(faint, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 2f)
        bars.forEachIndexed { i, (_, value) ->
            val h = (value.toFloat() / max.toFloat()) * (size.height - 6f)
            if (h > 0f) {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(i * (barWidth + gap), size.height - h),
                    size = Size(barWidth, h),
                    cornerRadius = CornerRadius(barWidth.coerceAtMost(12f) / 2),
                )
            }
        }
    }
}

/** One horizontal bar split by category, followed by the legend drawn by the caller. */
@Composable
fun CategoryStack(buckets: List<Bucket>, modifier: Modifier = Modifier) {
    val total = buckets.sumOf { it.total }
    if (total.signum() <= 0) return
    Row(modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp))) {
        buckets.forEach { b ->
            val share = (b.total.toFloat() / total.toFloat()).coerceAtLeast(0.01f)
            Box(Modifier.weight(share).height(12.dp).background(CategoryColors.of(b.key)))
        }
    }
}
