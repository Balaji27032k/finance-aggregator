package com.personal.financecollector.profile

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RatesJsonTest {

    @Test
    fun theServersRatesAreReadWithTheirSources() {
        val rates = RatesJson.parse(
            """{"fetchedAt":"2026-11-12T10:00:00Z","status":"PARTIAL","message":"Found 3 of 17 rates.",
            "items":[{"key":"ppf","label":"PPF","ratePercent":7.1,"asOf":"2026-Q3","note":"current quarter"},
                     {"key":"fd1y","label":"FD 1y","ratePercent":6.4,"asOf":"","note":""},
                     {"key":"fd5y","label":"FD 5y","ratePercent":6.3,"asOf":"2026-10-19","note":null}],
            "sources":[{"title":"India Post","url":"https://www.indiapost.gov.in/x"}]}""",
        )!!
        assertEquals("PARTIAL", rates.status)
        assertEquals(3, rates.items.size)
        assertEquals(BigDecimal("7.1"), rates.items[0].percent)
        assertNull(rates.items[1].asOf)
        assertEquals("India Post", rates.sources.single().title)
    }

    @Test
    fun foundRatesReplaceTheAssumptionsAndLeaveTheRestAlone() {
        val rates = RatesJson.parse(
            """{"status":"OK","items":[{"key":"ppf","label":"PPF","ratePercent":7.1},{"key":"fd1y","label":"FD","ratePercent":6.4},
            {"key":"fd5y","label":"FD5","ratePercent":6.3},{"key":"inflation","label":"CPI","ratePercent":4.2}],"sources":[]}""",
        )!!
        val mine = ProfileRates(inflation = 6.0, equity = 11.0, debt = 7.5, fd = 7.0, ppf = 7.0, epf = 8.0, savings = 3.0)
        val applied = rates.toAssumptions(mine)
        assertEquals(7.1, applied.ppf!!, 0.0)
        assertEquals(6.3, applied.fd!!, 0.0)
        assertEquals(4.2, applied.inflation!!, 0.0)
        // Not found, so untouched.
        assertEquals(11.0, applied.equity!!, 0.0)
        assertEquals(8.0, applied.epf!!, 0.0)
        assertEquals(7.5, applied.debt!!, 0.0)
    }

    @Test
    fun anUnavailableAnswerIsStillReadable() {
        val rates = RatesJson.parse("""{"fetchedAt":null,"status":"UNAVAILABLE","message":"No AI key is set.","items":[],"sources":[]}""")!!
        assertTrue(rates.items.isEmpty())
        assertNull(rates.fetchedAt)
        assertNull(RatesJson.parse("nope"))
    }
}
