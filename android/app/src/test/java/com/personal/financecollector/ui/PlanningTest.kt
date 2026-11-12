package com.personal.financecollector.ui

import com.personal.financecollector.sync.LabelPush
import com.personal.financecollector.sync.LabelSync
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanningTest {

    private val today = LocalDate.parse("2026-11-20")

    private fun txn(
        id: String, date: String, amount: String = "100", credit: Boolean = false, who: String? = "Shop", account: String? = "1111",
        bank: String = "HDFC", balance: String? = null, category: String = "Other", splits: List<SplitPart> = emptyList(),
    ): LedgerTxn {
        val d = LocalDate.parse(date)
        return LedgerTxn(
            id, credit, BigDecimal(amount), who, "UPI", account, null, null, balance?.let { BigDecimal(it) }, null, bank, d,
            d.atTime(12, 0).atZone(LedgerBuilder.IST).toInstant().toEpochMilli(), category, listOf("SMS"), true,
            eventIds = listOf(id), splits = splits,
        )
    }

    @Test
    fun netWorthIsTheLatestBalanceOfEachAccountAndExcludedAccountsAreLeftOut() {
        val ledger = listOf(
            txn("a1", "2026-10-01", balance = "1000", account = "1111"),
            txn("a2", "2026-11-15", balance = "1500", account = "1111"),
            txn("b1", "2026-11-10", balance = "300", account = "2222", bank = "SBI"),
        )
        val all = NetWorthCalculator.compute(ledger, emptySet(), today)
        assertEquals(BigDecimal("1800"), all.total)
        assertEquals(2, all.accounts.size)
        val withoutSbi = NetWorthCalculator.compute(ledger, setOf("SBI|2222"), today)
        assertEquals(BigDecimal("1500"), withoutSbi.total)
    }

    @Test
    fun netWorthChangeComparesWithAboutAMonthAgo() {
        val ledger = listOf(
            txn("a1", "2026-10-10", balance = "1000"),
            txn("a2", "2026-11-18", balance = "1400"),
        )
        val nw = NetWorthCalculator.compute(ledger, emptySet(), today)
        assertEquals(BigDecimal("400"), nw.change)
        assertTrue(nw.series.size >= 2)
    }

    @Test
    fun noBalancesMeansNoNetWorth() {
        val nw = NetWorthCalculator.compute(listOf(txn("a", "2026-11-01")), emptySet(), today)
        assertEquals(BigDecimal.ZERO, nw.total)
        assertTrue(nw.accounts.isEmpty())
        assertNull(nw.change)
    }

    @Test
    fun aPaymentRepeatingMonthlyIsABillAndTheNextDueDateIsAMonthAfterTheLast() {
        val ledger = listOf("2026-08-05", "2026-09-05", "2026-10-05", "2026-11-05").mapIndexed { i, d -> txn("r$i", d, "799", who = "Netflix") }
        val bills = BillDetector.detect(ledger, today)
        assertEquals(1, bills.size)
        assertEquals("Netflix", bills[0].name)
        assertEquals(BigDecimal("799"), bills[0].amount)
        assertEquals(LocalDate.parse("2026-12-05"), bills[0].nextDue)
    }

    @Test
    fun irregularOrTooFewPaymentsAreNotBills() {
        val twice = listOf(txn("x1", "2026-10-05", who = "Cafe"), txn("x2", "2026-11-05", who = "Cafe"))
        assertTrue(BillDetector.detect(twice, today).isEmpty())
        val varying = listOf(txn("y1", "2026-09-05", "100", who = "Fuel"), txn("y2", "2026-10-05", "900", who = "Fuel"), txn("y3", "2026-11-05", "50", who = "Fuel"))
        assertTrue(BillDetector.detect(varying, today).isEmpty())
    }

    @Test
    fun aBillThatHasStoppedIsDropped() {
        val old = listOf("2026-04-05", "2026-05-05", "2026-06-05").mapIndexed { i, d -> txn("o$i", d, "500", who = "Gym") }
        assertTrue(BillDetector.detect(old, today).isEmpty())
    }

    @Test
    fun budgetsCountOnlyThisMonthsSpendAndReachTheAlertLevels() {
        val ledger = listOf(
            txn("f1", "2026-11-02", "800", category = "Food"),
            txn("f2", "2026-11-09", "100", category = "Food"),
            txn("f3", "2026-10-30", "5000", category = "Food"),
            txn("t1", "2026-11-03", "400", category = "Transport"),
        )
        val statuses = BudgetCalculator.statuses(ledger, mapOf("Food" to 1000L, "Transport" to 5000L), YearMonth.of(2026, 11))
        val food = statuses.first { it.category == "Food" }
        assertEquals(BigDecimal("900"), food.spent)
        assertEquals(90, food.percent)
        assertEquals(listOf(80), BudgetCalculator.levelsReached(food))
        assertEquals(listOf(80, 100), BudgetCalculator.levelsReached(BudgetStatus("Food", 1000, BigDecimal("1200"))))
        assertTrue(BudgetCalculator.levelsReached(statuses.first { it.category == "Transport" }).isEmpty())
    }

    @Test
    fun aSplitPaymentCountsEachPartInItsOwnCategory() {
        val split = txn("s", "2026-11-04", "1000", category = "Shopping", splits = listOf(SplitPart("Groceries", BigDecimal("700")), SplitPart("Household", BigDecimal("300"))))
        val spend = StatsCalculator.categorySpend(listOf(split), YearMonth.of(2026, 11))
        assertEquals(BigDecimal("700"), spend["Groceries"])
        assertEquals(BigDecimal("300"), spend["Household"])
        assertNull(spend["Shopping"])
    }

    @Test
    fun aSplitOnlyAppliesWhileItsPartsAddUpToThePayment() {
        val base = listOf(txn("p", "2026-11-04", "1000"))
        val parts = listOf(SplitPart("Groceries", BigDecimal("700")), SplitPart("Household", BigDecimal("300")))
        val own = OwnSettings()
        val good = LedgerOverlay.apply(base, emptyMap(), emptyMap(), own, mapOf("p" to parts))
        assertEquals(2, good[0].splits.size)
        val bad = LedgerOverlay.apply(base, emptyMap(), emptyMap(), own, mapOf("p" to listOf(SplitPart("Groceries", BigDecimal("700")), SplitPart("Household", BigDecimal("200")))))
        assertTrue(bad[0].splits.isEmpty())
    }

    @Test
    fun onlyChangedTransactionsAreSentAndRevertedOnesAreCleared() {
        val edited = txn("e1", "2026-11-04").copy(edited = true, userCategory = "Food", category = "Food", label = "milk")
        val untouched = txn("u1", "2026-11-05")
        val sent = mapOf(
            "e1" to LabelSync.signature("Food", "milk"),
            "gone" to LabelSync.signature("Fuel", null),
        )
        val plan = LabelSync.plan(listOf(edited, untouched), sent)
        assertTrue(plan.push.isEmpty())
        assertEquals(listOf("gone"), plan.clear)

        val changed = LabelSync.plan(listOf(edited), emptyMap())
        assertEquals(listOf(LabelPush("e1", "Food", "milk")), changed.push)
    }
}
