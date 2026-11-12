package com.personal.financecollector.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.personal.financecollector.FinanceApp
import com.personal.financecollector.alerts.Alerts
import com.personal.financecollector.ui.LedgerLoader
import com.personal.financecollector.widget.SpendWidget
import java.util.concurrent.TimeUnit

/** Everything that follows a change in the data: widget, budget alerts, bill reminders and sending corrections to the server. */
object BackgroundChecks {
    suspend fun run(context: Context) {
        val container = (context.applicationContext as FinanceApp).container
        val db = container.db
        val ledger = LedgerLoader.load(db)
        val prefs = context.getSharedPreferences("finance_prefs", Context.MODE_PRIVATE)
        SpendWidget.save(context, SpendWidget.numbers(ledger, prefs.getLong("monthly_budget", 0L).takeIf { it > 0 }))
        val limits = db.planDao().budgets().associate { it.category to it.limitRupees }
        Alerts.checkBudgets(context, ledger, limits)
        Alerts.checkBills(context, ledger)
        LabelSync(db, container.api, container.credentials).run(ledger)
    }
}

/** A safety net so reminders still arrive on days when no new message comes in. */
class ChecksWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        BackgroundChecks.run(applicationContext)
        Result.success()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Exception) {
        Result.retry()
    }
}

object ChecksScheduler {
    fun ensurePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<ChecksWorker>(6, TimeUnit.HOURS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("periodic_checks", ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
