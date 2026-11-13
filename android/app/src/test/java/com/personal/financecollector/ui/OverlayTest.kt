package com.personal.financecollector.ui

import com.personal.financecollector.database.TransactionLabelEntity
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayTest {

    private fun txn(
        id: String, credit: Boolean = false, amount: String = "100", who: String? = "Shop", account: String? = "1111",
        otherAccount: String? = null, timeMs: Long = 1_000_000_000_000L, note: String? = null,
    ) = LedgerTxn(
        id, credit, BigDecimal(amount), who, "UPI", account, otherAccount, null, null, note, "BANK", LocalDate.parse("2026-11-10"),
        timeMs, Categories.of(credit, who, "UPI", note), listOf("SMS"), true, eventIds = listOf(id),
    )

    private fun label(id: String, category: String?, text: String?) = TransactionLabelEntity(id, category, text, 1L)

    @Test
    fun theUsersCategoryAndNoteWinOverTheAutomaticOnes() {
        val out = LedgerOverlay.apply(
            listOf(txn("a", who = "Ravi Kumar")), mapOf("a" to label("a", "Groceries", "milk")), emptyMap(), OwnSettings(),
        ).single()
        assertEquals("Groceries", out.category)
        assertEquals("milk", out.label)
        assertTrue(out.edited)
    }

    @Test
    fun aNoteAloneCanDecideTheCategoryFromItsWords() {
        val out = LedgerOverlay.apply(
            listOf(txn("a", who = "Ravi Kumar")), mapOf("a" to label("a", null, "milk")), emptyMap(), OwnSettings(),
        ).single()
        assertEquals("Groceries", out.category)
    }

    @Test
    fun aPayeeRuleCoversEveryPaymentToThatPayeeAndAnExplicitEditBeatsIt() {
        val rules = mapOf(LedgerOverlay.payeeKey("Ravi Kumar")!! to "Education")
        val out = LedgerOverlay.apply(
            listOf(txn("a", who = "Ravi Kumar"), txn("b", who = "RAVI KUMAR"), txn("c", who = "Ravi Kumar")),
            mapOf("c" to label("c", "Gifts & donations", null)), rules, OwnSettings(),
        )
        assertEquals(listOf("Education", "Education", "Gifts & donations"), out.map { it.category })
    }

    @Test
    fun aTransferToAnAccountSeenInYourMessagesIsNotSpending() {
        val list = listOf(
            txn("a", amount = "5000", account = "1111", otherAccount = "2222"),
            txn("b", amount = "70", account = "2222"),
        )
        val out = LedgerOverlay.apply(list, emptyMap(), emptyMap(), OwnSettings())
        assertTrue(out[0].internalTransfer)
        assertEquals(Categories.OWN_TRANSFER, out[0].category)
        assertFalse(out[1].internalTransfer)
        val stats = StatsCalculator.compute(out, LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-30"))
        assertEquals(BigDecimal("70"), stats.totals.spend)
    }

    @Test
    fun aDebitAndAnEqualCreditInYourTwoAccountsWithinMinutesArePaired() {
        val t = 1_000_000_000_000L
        val list = listOf(
            txn("d", amount = "5000", account = "1111", who = null, timeMs = t),
            txn("c", credit = true, amount = "5000", account = "2222", who = null, timeMs = t + 120_000),
            txn("x", credit = true, amount = "5000", account = "1111", who = "Employer", timeMs = t + 30_000),
        )
        val out = LedgerOverlay.apply(list, emptyMap(), emptyMap(), OwnSettings())
        assertTrue(out[0].internalTransfer)
        assertTrue(out[1].internalTransfer)
        assertFalse(out[2].internalTransfer)
    }

    @Test
    fun yourOwnNameAndHandTypedAccountsAreRecognised() {
        val own = OwnSettings(accountLast4s = setOf("9999"), names = setOf("balaji kumar"))
        val out = LedgerOverlay.apply(
            listOf(
                txn("n", who = "BALAJI KUMAR S"),
                txn("m", who = "Other Balaji"),
                txn("h", otherAccount = "9999", who = null),
            ),
            emptyMap(), emptyMap(), own,
        )
        assertEquals(listOf(true, false, true), out.map { it.internalTransfer })
    }

    @Test
    fun yourChoiceOverridesTheOwnTransferGuess() {
        val out = LedgerOverlay.apply(
            listOf(txn("a", amount = "5000", account = "1111", otherAccount = "1111")),
            mapOf("a" to label("a", "Rent & housing", null)), emptyMap(), OwnSettings(accountLast4s = setOf("1111")),
        ).single()
        assertEquals("Rent & housing", out.category)
        assertFalse(out.internalTransfer)
    }

    @Test
    fun csvEscapesQuotesAndNeutralisesFormulas() {
        assertEquals("\"say \"\"hi\"\"\"", CsvExport.cell("say \"hi\""))
        assertEquals("\"'=SUM(A1)\"", CsvExport.cell("=SUM(A1)"))
        assertEquals("\"-250.00\"", CsvExport.cell("-250.00"))
        val csv = CsvExport.build(listOf(txn("a", who = "Ravi, Kumar")))
        assertTrue(csv.startsWith("\"Date\",\"Received (IST)\""))
        assertTrue(csv.contains("\"Ravi, Kumar\""))
        assertEquals(2, csv.trim().lines().size)
    }

    @Test
    fun aMobileNumberIsOnePayeeWhicheverUpiHandleItHas() {
        assertEquals(LedgerOverlay.payeeKey("9876543210@ybl"), LedgerOverlay.payeeKey("9876543210@oksbi"))
        assertEquals("9876543210", LedgerOverlay.payeeKey("9876543210@ybl"))
        assertEquals("archana kumar", LedgerOverlay.payeeKey("Archana Kumar"))
    }

}
