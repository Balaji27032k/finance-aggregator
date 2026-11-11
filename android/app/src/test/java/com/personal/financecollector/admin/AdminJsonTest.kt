package com.personal.financecollector.admin

import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdminJsonTest {

    private val txn = """
        {"groupId":7,"direction":"DEBIT","amount":611.00,"currency":"INR","transactionDate":"2026-11-10",
         "eventTimestamp":"2026-11-10T08:15:00Z","counterparty":"ZZ Marker Cafe","counterpartyAccountLast4":null,
         "accountLast4":"0000","paymentMode":"UPI","referenceNo":"000000000003","balanceAfter":1200.50,"note":"sample",
         "category":"Food & drinks","sources":[{"type":"SMS","bankCode":"SBIUPI"},{"type":"GMAIL","bankCode":"SBIUPI"}]}
    """.trimIndent()

    @Test
    fun aServerTransactionBecomesTheSameShapeAsALocalOne() {
        val page = AdminJson.transactions(
            """{"total":1,"page":0,"size":50,"totalDebit":611.00,"totalCredit":0,"categories":["Food & drinks"],"items":[$txn]}""",
        )
        assertEquals(1, page.total)
        val t = page.items.single()
        assertEquals(BigDecimal("611.00"), t.amount)
        assertEquals(false, t.credit)
        assertEquals("ZZ Marker Cafe", t.counterparty)
        assertNull(t.counterpartyAccountLast4)
        assertEquals(LocalDate.parse("2026-11-10"), t.date)
        assertEquals("Food & drinks", t.category)
        assertEquals(listOf("SMS", "Email"), t.sources)
        assertEquals("SBIUPI", t.bankCode)
        assertEquals(BigDecimal("1200.50"), t.balanceAfter)
    }

    @Test
    fun aSummaryIsReadIntoTheStatsTheOverviewAlreadyDraws() {
        val body = """
            {"range":{"from":"2026-11-01","to":"2026-11-02","days":2},
             "totals":{"income":5000,"spend":1000.5,"net":3999.5,"count":3,"avgDailySpend":500.25,"largestExpense":700},
             "previous":{"income":0,"spend":0,"net":0,"count":0,"avgDailySpend":0,"largestExpense":0},
             "savingsRatePercent":80.0,
             "daily":[{"date":"2026-11-01","income":5000,"spend":300},{"date":"2026-11-02","income":0,"spend":700.5}],
             "monthly":[{"month":"2026-10","income":0,"spend":10},{"month":"2026-11","income":5000,"spend":1000.5}],
             "topPayees":[{"key":"Landlord","total":700,"count":1}],"topIncomeSources":[{"key":"ACME","total":5000,"count":1}],
             "byCategory":[{"key":"Rent & housing","total":700,"count":1}],"byMode":[],"byBank":[],"byWeekday":[],"byHour":[],
             "largestExpenses":[$txn],
             "recurring":[{"name":"Netflix","typicalAmount":649,"occurrences":4,"lastDate":"2026-10-11","nextExpected":"2026-11-11"}],
             "spikes":[],
             "balances":[{"bankCode":"HDFCBK","accountLast4":"4821","balance":167145.0,"asOf":"2026-11-11T14:00:00Z"}],
             "projection":{"month":"2026-11","spentSoFar":1000.5,"projected":15000,"daysElapsed":2,"daysInMonth":30}}
        """.trimIndent()
        val s = AdminJson.summary(body)
        assertEquals(2, s.stats.days)
        assertEquals(BigDecimal("1000.5"), s.stats.totals.spend)
        assertEquals(80, s.stats.savingsPercent)
        assertEquals(2, s.stats.daily.size)
        assertEquals("Rent & housing", s.stats.byCategory.single().key)
        assertEquals(1, s.stats.biggest.size)
        assertEquals(2, s.monthly.size)
        assertEquals("Netflix", s.recurring.single().name)
        assertEquals(LocalDate.parse("2026-11-11"), s.recurring.single().next)
        assertEquals("HDFCBK", s.balances.single().bank)
        assertEquals(30, s.pace.daysInMonth)
        assertEquals("ACME", s.incomeSources.single().key)
        assertTrue(s.stats.highlights.any { it.contains("kept 80%") })
        assertTrue(s.stats.highlights.any { it.contains("Rent & housing is your biggest category") })
    }

    @Test
    fun aSummaryWithNoIncomeHasNoSavingsRate() {
        val empty = """
            {"range":{"from":"2026-11-01","to":"2026-11-01","days":1},
             "totals":{"income":0,"spend":0,"net":0,"count":0,"avgDailySpend":0,"largestExpense":0},
             "previous":{"income":0,"spend":0,"net":0,"count":0,"avgDailySpend":0,"largestExpense":0},
             "savingsRatePercent":null,"daily":[],"monthly":[],"topPayees":[],"topIncomeSources":[],"byCategory":[],
             "byMode":[],"byBank":[],"byWeekday":[],"byHour":[],"largestExpenses":[],"recurring":[],"spikes":[],"balances":[],
             "projection":{"month":"2026-11","spentSoFar":0,"projected":0,"daysElapsed":1,"daysInMonth":30}}
        """.trimIndent()
        assertNull(AdminJson.summary(empty).stats.savingsPercent)
    }

    @Test
    fun healthCountsAreRead() {
        val h = AdminJson.health(
            """{"transactions":558,"multiChannelTransactions":66,"firstEvent":"2026-04-26T03:00:00Z",
                "bySource":[{"key":"SMS","count":558,"latest":"2026-11-11T14:00:00Z"}],
                "byBank":[{"key":"SBIUPI","count":516,"latest":null}],"serverTime":"2026-11-12T00:00:00Z"}""",
        )
        assertEquals(558L, h.transactions)
        assertEquals(66L, h.multiChannel)
        assertEquals("SMS", h.bySource.single().key)
        assertNull(h.byBank.single().latest)
    }

    @Test
    fun theSessionTokenIsPickedOutOfTheSetCookieHeader() {
        assertEquals("abc123", AdminApi.cookieValue("fin_session=abc123; Path=/; HttpOnly; SameSite=Strict"))
        assertNull(AdminApi.cookieValue("other=1; Path=/"))
        assertNull(AdminApi.cookieValue("fin_session=; Max-Age=0"))
        assertTrue(AdminApi.cookieValue("fin_session=x") == "x")
    }
}
