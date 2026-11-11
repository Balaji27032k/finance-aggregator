package com.personal.financecollector.admin

import com.personal.financecollector.ui.Bucket
import com.personal.financecollector.ui.DayAmount
import com.personal.financecollector.ui.LedgerBuilder
import com.personal.financecollector.ui.LedgerTxn
import com.personal.financecollector.ui.MonthPace
import com.personal.financecollector.ui.Stats
import com.personal.financecollector.ui.Totals
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

data class MonthAmount(val month: String, val income: BigDecimal, val spend: BigDecimal)
data class RecurringItem(val name: String, val amount: BigDecimal, val times: Int, val next: LocalDate)
data class BalanceItem(val bank: String, val last4: String, val balance: BigDecimal, val asOf: Instant)
data class CountItem(val key: String, val count: Long, val latest: Instant?)

data class AdminSummary(
    val stats: Stats,
    val monthly: List<MonthAmount>,
    val recurring: List<RecurringItem>,
    val balances: List<BalanceItem>,
    val incomeSources: List<Bucket>,
    val pace: MonthPace,
)

data class AdminTxnPage(
    val total: Int,
    val totalDebit: BigDecimal,
    val totalCredit: BigDecimal,
    val categories: List<String>,
    val items: List<LedgerTxn>,
)

data class AdminHealth(
    val transactions: Long,
    val multiChannel: Long,
    val firstEvent: Instant?,
    val bySource: List<CountItem>,
    val byBank: List<CountItem>,
    val serverTime: Instant?,
)

/** Turns the server's JSON into the same shapes the phone's own screens already draw. */
object AdminJson {
    private fun JSONObject.str(key: String): String? = if (!has(key) || isNull(key)) null else get(key).toString()
    private fun JSONObject.dec(key: String): BigDecimal = BigDecimal(str(key) ?: "0")
    private fun JSONObject.decOrNull(key: String): BigDecimal? = str(key)?.let { BigDecimal(it) }
    private fun JSONObject.int(key: String): Int = (str(key)?.toDoubleOrNull() ?: 0.0).toInt()
    private fun JSONObject.long(key: String): Long = (str(key)?.toDoubleOrNull() ?: 0.0).toLong()
    private fun <T> JSONArray?.map(block: (JSONObject) -> T): List<T> =
        if (this == null) emptyList() else (0 until length()).map { block(getJSONObject(it)) }

    private fun totals(o: JSONObject) = Totals(o.dec("income"), o.dec("spend"), o.int("count"))
    private fun bucket(o: JSONObject) = Bucket(o.str("key") ?: "Unknown", o.dec("total"), o.int("count"))

    fun txn(o: JSONObject): LedgerTxn {
        val at = Instant.parse(o.getString("eventTimestamp"))
        val sources = o.optJSONArray("sources")
        val labels = sources.map { s ->
            when (s.str("type")) {
                "GMAIL" -> "Email"
                "SMS" -> "SMS"
                else -> "Notification"
            }
        }.distinct()
        val credit = o.str("direction") == "CREDIT"
        return LedgerTxn(
            key = "server-${o.str("groupId")}",
            credit = credit,
            amount = o.dec("amount"),
            counterparty = o.str("counterparty"),
            paymentMode = o.str("paymentMode"),
            accountLast4 = o.str("accountLast4"),
            counterpartyAccountLast4 = o.str("counterpartyAccountLast4"),
            referenceNo = o.str("referenceNo"),
            balanceAfter = o.decOrNull("balanceAfter"),
            note = o.str("note"),
            bankCode = sources.map { it.str("bankCode") }.firstNotNullOfOrNull { it } ?: "",
            date = o.str("transactionDate")?.let(LocalDate::parse) ?: at.atZone(LedgerBuilder.IST).toLocalDate(),
            timeMs = at.toEpochMilli(),
            category = o.str("category") ?: "Other",
            sources = labels,
            uploaded = true,
        )
    }

    fun summary(body: String): AdminSummary {
        val o = JSONObject(body)
        val range = o.getJSONObject("range")
        val projection = o.getJSONObject("projection")
        val stats = Stats(
            from = LocalDate.parse(range.getString("from")),
            to = LocalDate.parse(range.getString("to")),
            days = range.int("days"),
            totals = totals(o.getJSONObject("totals")),
            previous = totals(o.getJSONObject("previous")),
            savingsPercent = o.str("savingsRatePercent")?.toDoubleOrNull()?.let { Math.round(it).toInt() },
            daily = o.getJSONArray("daily").map { DayAmount(LocalDate.parse(it.getString("date")), it.dec("spend"), it.dec("income")) },
            byCategory = o.getJSONArray("byCategory").map(::bucket),
            topPayees = o.getJSONArray("topPayees").map(::bucket).take(5),
            biggest = o.getJSONArray("largestExpenses").map(::txn),
            highlights = emptyList(),
        )
        return AdminSummary(
            stats = stats,
            monthly = o.getJSONArray("monthly").map { MonthAmount(it.getString("month"), it.dec("income"), it.dec("spend")) },
            recurring = o.getJSONArray("recurring").map {
                RecurringItem(it.getString("name"), it.dec("typicalAmount"), it.int("occurrences"), LocalDate.parse(it.getString("nextExpected")))
            },
            balances = o.getJSONArray("balances").map {
                BalanceItem(it.getString("bankCode"), it.getString("accountLast4"), it.dec("balance"), Instant.parse(it.getString("asOf")))
            },
            incomeSources = o.getJSONArray("topIncomeSources").map(::bucket),
            pace = MonthPace(projection.dec("spentSoFar"), projection.dec("projected"), projection.int("daysElapsed"), projection.int("daysInMonth")),
        )
    }

    fun transactions(body: String): AdminTxnPage {
        val o = JSONObject(body)
        return AdminTxnPage(
            total = o.int("total"),
            totalDebit = o.dec("totalDebit"),
            totalCredit = o.dec("totalCredit"),
            categories = o.getJSONArray("categories").let { a -> (0 until a.length()).map { a.getString(it) } },
            items = o.getJSONArray("items").map(::txn),
        )
    }

    fun health(body: String): AdminHealth {
        val o = JSONObject(body)
        fun counts(key: String) = o.getJSONArray(key).map { CountItem(it.getString("key"), it.long("count"), it.str("latest")?.let(Instant::parse)) }
        return AdminHealth(
            transactions = o.long("transactions"),
            multiChannel = o.long("multiChannelTransactions"),
            firstEvent = o.str("firstEvent")?.let(Instant::parse),
            bySource = counts("bySource"),
            byBank = counts("byBank"),
            serverTime = o.str("serverTime")?.let(Instant::parse),
        )
    }
}
