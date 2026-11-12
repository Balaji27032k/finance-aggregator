package com.personal.financecollector.ui

import com.personal.financecollector.collector.ParsedTransaction
import com.personal.financecollector.database.ParserStatus
import com.personal.financecollector.database.RawFinancialEventEntity
import com.personal.financecollector.database.SourceType
import com.personal.financecollector.database.UploadStatus
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LedgerTest {

    private val ist = ZoneId.of("Asia/Kolkata")

    private fun at(date: String, time: String = "10:00") =
        LocalDate.parse(date).atTime(java.time.LocalTime.parse(time)).atZone(ist).toInstant().toEpochMilli()

    private fun event(id: String, type: SourceType, ms: Long, status: UploadStatus = UploadStatus.SYNCED) = RawFinancialEventEntity(
        localEventId = id, sourceType = type, sourceApplication = "BANK", sourceSender = null, sourceEventId = id,
        eventTimestamp = ms, capturedTimestamp = ms, rawPayload = "x", payloadHash = "h",
        parserStatus = ParserStatus.UNPARSED, parserVersion = "raw-1", uploadStatus = status,
    )

    private fun txn(
        direction: String = "DEBIT", amount: String = "100.00", counterparty: String? = null, ref: String? = null,
        note: String? = null, mode: String? = "UPI", date: LocalDate? = null,
    ) = ParsedTransaction(direction, BigDecimal(amount), "INR", "0000", counterparty, null, mode, ref, null, date, "BANK", note)

    private fun build(vararg pairs: Pair<RawFinancialEventEntity, ParsedTransaction?>): List<LedgerTxn> {
        val map = pairs.associate { it.first.localEventId to it.second }
        return LedgerBuilder.build(pairs.map { it.first }) { map[it.localEventId] }
    }

    @Test
    fun theSameReferenceSeenThroughSmsAndEmailIsOneTransactionWithTheEmailDetails() {
        val t = at("2026-11-10")
        val list = build(
            event("sms", SourceType.SMS, t) to txn(ref = "421960362907"),
            event("mail", SourceType.GMAIL, t + 20_000) to txn(ref = "421960362907", counterparty = "Hungerbox", note = "Lunch"),
        )
        assertEquals(1, list.size)
        assertEquals("Hungerbox", list[0].counterparty)
        assertEquals("Lunch", list[0].note)
        assertEquals(listOf("Email", "SMS"), list[0].sources)
        assertEquals("Food & drinks", list[0].category)
    }

    @Test
    fun withoutAReferenceTheSameAmountWithinThreeMinutesFromAnotherChannelIsMerged() {
        val t = at("2026-11-10")
        val merged = build(
            event("sms", SourceType.SMS, t) to txn(amount = "250.00"),
            event("note", SourceType.NOTIFICATION, t + 90_000) to txn(amount = "250.00"),
        )
        assertEquals(1, merged.size)
        val apart = build(
            event("sms", SourceType.SMS, t) to txn(amount = "250.00"),
            event("note", SourceType.NOTIFICATION, t + 10 * 60_000) to txn(amount = "250.00"),
        )
        assertEquals(2, apart.size)
    }

    @Test
    fun oppositeDirectionsAndDifferentAmountsAreNeverMerged() {
        val t = at("2026-11-10")
        val list = build(
            event("a", SourceType.SMS, t) to txn(direction = "DEBIT", amount = "500.00", ref = "123456789012"),
            event("b", SourceType.SMS, t + 5_000) to txn(direction = "CREDIT", amount = "500.00", ref = "123456789012"),
            event("c", SourceType.GMAIL, t + 8_000) to txn(direction = "DEBIT", amount = "499.00"),
        )
        assertEquals(3, list.size)
    }

    @Test
    fun twoSmsForSeparatePurchasesOfTheSameAmountStayApart() {
        val t = at("2026-11-10")
        val list = build(
            event("a", SourceType.SMS, t) to txn(amount = "60.00"),
            event("b", SourceType.SMS, t + 60_000) to txn(amount = "60.00"),
        )
        assertEquals(2, list.size)
    }

    @Test
    fun unreadableEventsAreLeftOutAndNewestComesFirst() {
        val list = build(
            event("old", SourceType.SMS, at("2026-11-01")) to txn(),
            event("junk", SourceType.SMS, at("2026-11-05")) to null,
            event("new", SourceType.SMS, at("2026-11-09")) to txn(amount = "7.00"),
        )
        assertEquals(listOf("new", "old"), list.map { it.key })
    }

    @Test
    fun theDateInTheMessageWinsOverTheArrivalTime() {
        val list = build(event("a", SourceType.SMS, at("2026-11-10", "00:30")) to txn(date = LocalDate.parse("2026-11-09")))
        assertEquals(LocalDate.parse("2026-11-09"), list[0].date)
        val noDate = build(event("b", SourceType.SMS, at("2026-11-10", "00:30")) to txn())
        assertEquals(LocalDate.parse("2026-11-10"), noDate[0].date)
    }

    @Test
    fun uploadedOnlyWhenEveryChannelIsUploaded() {
        val t = at("2026-11-10")
        val list = build(
            event("a", SourceType.SMS, t, UploadStatus.SYNCED) to txn(ref = "999999999999"),
            event("b", SourceType.GMAIL, t + 1000, UploadStatus.PENDING) to txn(ref = "999999999999"),
        )
        assertFalse(list[0].uploaded)
    }

    @Test
    fun categoriesFollowThePayee() {
        assertEquals("Food & drinks", Categories.of(false, "SWIGGY", "UPI", null))
        assertEquals("Salary", Categories.of(true, "ACME", "NEFT", "Salary"))
        assertEquals("Cash withdrawal", Categories.of(false, "ATM WDL", "ATM", null))
        assertEquals("Rent & housing", Categories.of(false, "Landlord Rao", "UPI", null))
        assertEquals("Other", Categories.of(false, "Ravi Kumar", "UPI", null))
        assertEquals("Money received", Categories.of(true, "Ravi Kumar", "UPI", null))
    }

    @Test
    fun statsAddUpAndCompareWithThePreviousPeriod() {
        val today = LocalDate.parse("2026-11-12")
        val list = listOf(
            ledger("1", false, "300", "SWIGGY", "2026-11-11"),
            ledger("2", false, "700", "Landlord Rao", "2026-11-05"),
            ledger("3", true, "5000", "ACME", "2026-11-01"),
            ledger("4", false, "500", "SWIGGY", "2026-10-30"),
        )
        val (from, to) = Period.WEEK.range(today)
        val stats = StatsCalculator.compute(list, from, to)
        assertEquals(7, stats.days)
        assertEquals(BigDecimal("300"), stats.totals.spend)
        val month = StatsCalculator.compute(list, LocalDate.parse("2026-11-01"), today)
        assertEquals(BigDecimal("1000"), month.totals.spend)
        assertEquals(BigDecimal("5000"), month.totals.income)
        assertEquals(80, month.savingsPercent)
        assertEquals("Rent & housing", month.byCategory.first().key)
        assertEquals(12, month.days)
        assertEquals(BigDecimal("300"), month.daily.last { it.date == LocalDate.parse("2026-11-11") }.spend)
        assertTrue(month.highlights.any { it.contains("kept 80%") })
    }

    @Test
    fun capitalNamesAreShownInNormalCaseButMixedCaseIsLeftAlone() {
        assertEquals("Hungerbox", prettyName("HUNGERBOX"))
        assertEquals("Sample Employer Limited", prettyName("SAMPLE EMPLOYER LIMITED"))
        assertEquals("Blue Tokai Coffee", prettyName("Blue Tokai Coffee"))
        assertEquals("UPI", prettyName("UPI"))
    }

    @Test
    fun oneTransactionSeenTwiceShowsTheWorstUploadStateAndEachChannel() {
        val t = at("2026-11-10")
        val list = build(
            event("sms", SourceType.SMS, t, UploadStatus.SYNCED) to txn(ref = "421960362907"),
            event("mail", SourceType.GMAIL, t + 20_000, UploadStatus.RETRY).copy(lastError = "timeout") to txn(ref = "421960362907"),
        )
        assertEquals(1, list.size)
        assertEquals(StatusKind.RETRYING, list[0].status)
        assertEquals("timeout", list[0].problem)
        assertEquals(listOf("Email", "SMS"), list[0].parts.map { it.source })
        assertEquals(listOf(StatusKind.RETRYING, StatusKind.DONE), list[0].parts.map { it.kind })
    }

    @Test
    fun aServerRefusalIsAFailureButNotUnderstoodIsOnlyForReview() {
        val refused = event("a", SourceType.SMS, 1L, UploadStatus.NEEDS_REVIEW).copy(lastError = "invalid fields: amount")
        val unread = event("b", SourceType.SMS, 1L, UploadStatus.NEEDS_REVIEW).copy(lastError = EventPresenter.NOT_UNDERSTOOD_REASON)
        assertEquals(StatusKind.FAILED, EventPresenter.statusKind(refused))
        assertEquals(StatusKind.REVIEW, EventPresenter.statusKind(unread))
    }

    @Test
    fun thisMonthIsComparedWithTheSameDaysOfLastMonth() {
        val today = LocalDate.parse("2026-11-12")
        val month = Period.MONTH.previous(today)
        assertEquals(LocalDate.parse("2026-10-01"), month.from)
        assertEquals(LocalDate.parse("2026-10-12"), month.to)
        assertEquals("the same days last month", month.label)
        val last = Period.LAST_MONTH.previous(today)
        assertEquals(LocalDate.parse("2026-09-01"), last.from)
        assertEquals(LocalDate.parse("2026-09-30"), last.to)
        assertEquals("the previous 7 days", Period.WEEK.previous(today).label)
    }

    @Test
    fun aBigOneOffPaymentIsNotRepeatedInTheMonthProjection() {
        val today = LocalDate.parse("2026-11-20")
        val list = (1..20).map { d -> ledger("d$d", false, "200", "Cafe", "2026-11-%02d".format(d)) } +
            ledger("rent", false, "24000", "Landlord", "2026-11-03")
        val pace = StatsCalculator.monthPace(list, today)
        assertEquals(BigDecimal("28000"), pace.spentSoFar)
        assertEquals(BigDecimal("30000.00"), pace.projected)
    }

    @Test
    fun chartScalesUseRoundNumbers() {
        assertEquals(1500f, niceCeiling(1234f), 0.001f)
        assertEquals(10000f, niceCeiling(9999f), 0.001f)
        assertEquals(1f, niceCeiling(0f), 0.001f)
        assertEquals("\u20b91.5k", rupeesShort(BigDecimal("1500")))
        assertEquals("\u20b91.2L", rupeesShort(BigDecimal("120000")))
        assertEquals("\u20b9800", rupeesShort(BigDecimal("800")))
    }

    @Test
    fun monthPaceProjectsTheMonthEnd() {
        val today = LocalDate.parse("2026-11-10")
        val pace = StatsCalculator.monthPace(listOf(ledger("1", false, "1000", "X", "2026-11-03")), today)
        assertEquals(10, pace.daysElapsed)
        assertEquals(30, pace.daysInMonth)
        assertEquals(BigDecimal("3500.00"), pace.projected)
    }

    private fun ledger(key: String, credit: Boolean, amount: String, who: String, date: String) = LedgerTxn(
        key, credit, BigDecimal(amount), who, "UPI", "0000", null, null, null, null, "BANK", LocalDate.parse(date),
        0L, Categories.of(credit, who, "UPI", null), listOf("SMS"), true,
    )
}
