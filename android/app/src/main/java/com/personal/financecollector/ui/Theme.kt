package com.personal.financecollector.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val BrandLight = lightColorScheme(
    primary = Color(0xFF4F46E5),
    secondary = Color(0xFF0891B2),
    tertiary = Color(0xFF7C3AED),
)
private val BrandDark = darkColorScheme(
    primary = Color(0xFF818CF8),
    secondary = Color(0xFF22D3EE),
    tertiary = Color(0xFFA78BFA),
)

@Composable
fun FinanceTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> BrandDark
        else -> BrandLight
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/** Money colours that stay readable on both light and dark backgrounds. */
object MoneyColors {
    @Composable
    @ReadOnlyComposable
    fun income(): Color = if (isSystemInDarkTheme()) Color(0xFF4ADE80) else Color(0xFF15803D)

    @Composable
    @ReadOnlyComposable
    fun spend(): Color = if (isSystemInDarkTheme()) Color(0xFFFB7185) else Color(0xFFBE123C)
}

object CategoryColors {
    private val colors = mapOf(
        "Food & drinks" to 0xFFF59E0B,
        "Transport" to 0xFF38BDF8,
        "Shopping" to 0xFFEC4899,
        "Bills & recharge" to 0xFFA78BFA,
        "Rent & housing" to 0xFFF472B6,
        "Health" to 0xFF34D399,
        "Investments" to 0xFF22C55E,
        "Entertainment & apps" to 0xFFFB7185,
        "Cash withdrawal" to 0xFF94A3B8,
        "Cash deposit" to 0xFF14B8A6,
        "Salary" to 0xFF22C55E,
        "Money received" to 0xFF2DD4BF,
        "Interest" to 0xFF84CC16,
    )

    fun of(category: String): Color = Color(colors[category] ?: 0xFF64748B)
}

private val dayFormat = DateTimeFormatter.ofPattern("EEE, d MMM", Locale.ENGLISH)
private val dayYearFormat = DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.ENGLISH)
private val shortFormat = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

fun rupeesWhole(amount: BigDecimal): String =
    EventPresenter.rupees(amount.setScale(0, RoundingMode.HALF_UP)).substringBefore('.')

fun dayLabel(date: LocalDate, today: LocalDate): String = when {
    date == today -> "Today"
    date == today.minusDays(1) -> "Yesterday"
    date.year == today.year -> date.format(dayFormat)
    else -> date.format(dayYearFormat)
}

fun shortDate(date: LocalDate): String = date.format(shortFormat)
