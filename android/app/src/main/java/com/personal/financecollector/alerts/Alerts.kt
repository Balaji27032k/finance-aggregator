package com.personal.financecollector.alerts

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.personal.financecollector.R
import com.personal.financecollector.ui.BillDetector
import com.personal.financecollector.ui.BudgetCalculator
import com.personal.financecollector.ui.LedgerBuilder
import com.personal.financecollector.ui.LedgerTxn
import com.personal.financecollector.ui.MainActivity
import com.personal.financecollector.ui.rupeesWhole
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/** Budget alerts and bill reminders. Each one is sent once; what was already sent is remembered on the phone. */
object Alerts {
    private const val PREFS = "finance_prefs"
    private const val SENT = "alerts_sent"
    const val KEY_BUDGET_ALERTS = "budget_alerts_on"
    const val KEY_BILL_REMINDERS = "bill_reminders_on"
    private const val BUDGET_CHANNEL = "budget_alerts"
    private const val BILL_CHANNEL = "bill_reminders"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun budgetAlertsOn(context: Context) = prefs(context).getBoolean(KEY_BUDGET_ALERTS, true)
    fun billRemindersOn(context: Context) = prefs(context).getBoolean(KEY_BILL_REMINDERS, false)
    fun setBudgetAlerts(context: Context, on: Boolean) = prefs(context).edit().putBoolean(KEY_BUDGET_ALERTS, on).apply()
    fun setBillReminders(context: Context, on: Boolean) = prefs(context).edit().putBoolean(KEY_BILL_REMINDERS, on).apply()
    fun notificationsAllowed(context: Context) = NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** Tells the user when a category reaches 80% and 100% of its monthly limit. */
    fun checkBudgets(context: Context, ledger: List<LedgerTxn>, limits: Map<String, Long>, today: LocalDate = LocalDate.now(LedgerBuilder.IST)) {
        if (!budgetAlertsOn(context) || limits.isEmpty()) return
        val month = YearMonth.from(today)
        val sent = prefs(context).getStringSet(SENT, emptySet()).orEmpty().toMutableSet()
        sent.removeAll { it.startsWith("b:") && !it.startsWith("b:$month:") }
        for (status in BudgetCalculator.statuses(ledger, limits, month)) {
            for (level in BudgetCalculator.levelsReached(status)) {
                val key = "b:$month:${status.category}:$level"
                if (key in sent) continue
                val text = if (level >= 100) {
                    "${status.category}: over the ${rupeesWhole(BigDecimal(status.limit))} budget, ${rupeesWhole(status.spent)} spent"
                } else {
                    "${status.category}: ${status.percent}% of the ${rupeesWhole(BigDecimal(status.limit))} budget used"
                }
                if (post(context, BUDGET_CHANNEL, "Budgets", key.hashCode(), "Budget alert", text)) sent += key
            }
        }
        prefs(context).edit().putStringSet(SENT, sent).apply()
    }

    /** Reminds the user a day before (and on the day) a payment that repeats monthly is expected. */
    fun checkBills(context: Context, ledger: List<LedgerTxn>, today: LocalDate = LocalDate.now(LedgerBuilder.IST)) {
        if (!billRemindersOn(context)) return
        val sent = prefs(context).getStringSet(SENT, emptySet()).orEmpty().toMutableSet()
        sent.removeAll { it.startsWith("bill:") && runCatching { LocalDate.parse(it.substringAfterLast(':')).isBefore(today.minusDays(30)) }.getOrDefault(true) }
        for (bill in BillDetector.detect(ledger, today)) {
            val days = ChronoUnit.DAYS.between(today, bill.nextDue)
            if (days !in 0..1) continue
            val key = "bill:${bill.key}:${bill.nextDue}"
            if (key in sent) continue
            val whenText = if (days == 0L) "due today" else "due tomorrow"
            if (post(context, BILL_CHANNEL, "Bill reminders", key.hashCode(), "${bill.name} is $whenText", "Usually about ${rupeesWhole(bill.amount)}")) sent += key
        }
        prefs(context).edit().putStringSet(SENT, sent).apply()
    }

    @SuppressLint("MissingPermission")
    private fun post(context: Context, channel: String, channelName: String, id: Int, title: String, text: String): Boolean {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return false
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(channel, channelName, NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        manager.notify(
            id,
            NotificationCompat.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
        return true
    }
}
