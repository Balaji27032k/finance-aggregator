package com.personal.financecollector.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.personal.financecollector.FinanceApp
import com.personal.financecollector.R
import com.personal.financecollector.ui.LedgerBuilder
import com.personal.financecollector.ui.LedgerLoader
import com.personal.financecollector.ui.LedgerTxn
import com.personal.financecollector.ui.MainActivity
import com.personal.financecollector.ui.StatsCalculator
import com.personal.financecollector.ui.rupeesWhole
import java.math.BigDecimal
import java.time.LocalDate

/** The numbers the widget shows. They are saved by the app so the widget can draw itself without opening the database. */
data class WidgetNumbers(
    val month: BigDecimal,
    val today: BigDecimal,
    val projected: BigDecimal,
    val budget: Long?,
    val daysElapsed: Int,
    val daysInMonth: Int,
)

object SpendWidget {
    private const val PREFS = "finance_prefs"

    fun numbers(ledger: List<LedgerTxn>, budget: Long?, today: LocalDate = LocalDate.now(LedgerBuilder.IST)): WidgetNumbers {
        val pace = StatsCalculator.monthPace(ledger, today)
        val spentToday = ledger.filter { !it.credit && !it.internalTransfer && it.date == today }.sumOf { it.amount }
        return WidgetNumbers(pace.spentSoFar, spentToday, pace.projected, budget, pace.daysElapsed, pace.daysInMonth)
    }

    /** Called by the app whenever its own numbers change. */
    fun save(context: Context, n: WidgetNumbers) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("w_month", n.month.toPlainString())
            .putString("w_today", n.today.toPlainString())
            .putString("w_projected", n.projected.toPlainString())
            .putInt("w_elapsed", n.daysElapsed)
            .putInt("w_length", n.daysInMonth)
            .putString("w_date", LocalDate.now(LedgerBuilder.IST).toString())
            .apply()
        refresh(context)
    }

    /** Called from background work, for example after a new bank message arrives while the app is closed. */
    suspend fun recompute(context: Context) {
        if (!hasWidgets(context)) return
        val db = (context.applicationContext as FinanceApp).container.db
        val budget = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("monthly_budget", 0L).takeIf { it > 0 }
        save(context, numbers(LedgerLoader.load(db), budget))
    }

    private fun hasWidgets(context: Context): Boolean {
        val manager = AppWidgetManager.getInstance(context)
        return manager.getAppWidgetIds(ComponentName(context, SpendWidgetProvider::class.java)).isNotEmpty()
    }

    fun refresh(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, SpendWidgetProvider::class.java))
        if (ids.isNotEmpty()) render(context, manager, ids)
    }

    fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val views = RemoteViews(context.packageName, R.layout.widget_spend)
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        views.setOnClickPendingIntent(R.id.w_root, open)

        val savedOn = prefs.getString("w_date", null)
        if (savedOn == null) {
            views.setTextViewText(R.id.w_amount, "No data yet")
            views.setTextViewText(R.id.w_sub, "Open the app once")
            views.setViewVisibility(R.id.w_progress, View.GONE)
            ids.forEach { manager.updateAppWidget(it, views) }
            return
        }
        val month = BigDecimal(prefs.getString("w_month", "0") ?: "0")
        // After midnight the saved "today" belongs to yesterday.
        val today = if (savedOn == LocalDate.now(LedgerBuilder.IST).toString()) BigDecimal(prefs.getString("w_today", "0") ?: "0") else BigDecimal.ZERO
        val projected = BigDecimal(prefs.getString("w_projected", "0") ?: "0")
        val budget = prefs.getLong("monthly_budget", 0L).takeIf { it > 0 }
        val elapsed = prefs.getInt("w_elapsed", 1).coerceAtLeast(1)
        val length = prefs.getInt("w_length", 30).coerceAtLeast(1)

        views.setTextViewText(R.id.w_amount, rupeesWhole(month))
        views.setViewVisibility(R.id.w_progress, View.VISIBLE)
        if (budget != null) {
            val used = (month.toDouble() / budget * 100).toInt().coerceIn(0, 100)
            views.setProgressBar(R.id.w_progress, 100, used, false)
            views.setTextViewText(R.id.w_sub, "Today ${rupeesWhole(today)}  \u00b7  $used% of ${rupeesWhole(BigDecimal(budget))}")
        } else {
            views.setProgressBar(R.id.w_progress, 100, (elapsed * 100 / length).coerceIn(0, 100), false)
            views.setTextViewText(R.id.w_sub, "Today ${rupeesWhole(today)}  \u00b7  month ends near ${rupeesWhole(projected)}")
        }
        ids.forEach { manager.updateAppWidget(it, views) }
    }
}

class SpendWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        SpendWidget.render(context, appWidgetManager, appWidgetIds)
    }
}
