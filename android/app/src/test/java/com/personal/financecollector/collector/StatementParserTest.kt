package com.personal.financecollector.collector

import com.personal.financecollector.database.ParserStatus
import com.personal.financecollector.database.RawFinancialEventEntity
import com.personal.financecollector.database.SourceType
import com.personal.financecollector.database.UploadStatus
import com.personal.financecollector.ui.LedgerBuilder
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatementParserTest {

    private val sample = """
Account Name :,SOME PERSON
Account Number :,00000012345867
Txn Date,Value Date,Description,Ref No./Cheque No.,Debit,Credit,Balance
10 Nov 2026,10 Nov 2026,"UPI/DR/412345678901/ARCHANA S/YESB/archana@ybl/UPI",412345678901,100.00,,"5,400.50"
10 Nov 2026,10 Nov 2026,"UPI/DR/412345678902/AUTO DRIVER/YESB/9876543210@ybl/UPI",412345678902,10.00,,5390.50
11 Nov 2026,11 Nov 2026,"TO TRANSFER-ATM WDL ABC",,"2,000.00",,3390.50
12-11-2026,12-11-2026,"BY TRANSFER-NEFT*HDFC*ACME LTD SALARY",N123456789,,"50,000.00","53,390.50"
***,Closing balance,,,,,
""".trimIndent()

    @Test
    fun readsEachLineWithItsAmountDirectionAndBalance() {
        val parsed = StatementParser.parse(sample)
        assertNull(parsed.problem)
        assertEquals(4, parsed.rows.size)
        assertEquals(LocalDate.parse("2026-11-10"), parsed.rows[0].date)
        assertEquals(BigDecimal("100.00"), parsed.rows[0].debit)
        assertEquals(BigDecimal("5400.50"), parsed.rows[0].balance)
        assertEquals(BigDecimal("50000.00"), parsed.rows[3].credit)
        assertEquals(LocalDate.parse("2026-11-12"), parsed.rows[3].date)
    }

    @Test
    fun aUpiLineGivesTheReferenceAndThePersonsName() {
        val row = StatementParser.parse(sample).rows[0]
        val t = StatementParser.parseEvent(StatementParser.payload("SBI", "5867", row))!!
        assertEquals("DEBIT", t.direction)
        assertEquals("ARCHANA S", t.counterparty)
        assertEquals("412345678901", t.referenceNo)
        assertEquals("UPI", t.paymentMode)
        assertEquals("5867", t.accountLast4)
    }

    @Test
    fun anAtmLineHasNoPayeeAndIsCashWithdrawal() {
        val row = StatementParser.parse(sample).rows[2]
        val t = StatementParser.parseEvent(StatementParser.payload("SBI", "5867", row))!!
        assertNull(t.counterparty)
        assertEquals("ATM", t.paymentMode)
    }

    @Test
    fun anExcelFileIsRefusedWithAHelpfulMessage() {
        val parsed = StatementParser.parse("PK\u0003\u0004binary")
        assertTrue(parsed.rows.isEmpty())
        assertNotNull(parsed.problem)
        assertTrue(parsed.problem!!.contains("CSV"))
    }

    @Test
    fun aFileWithoutTheExpectedColumnsIsNotGuessedAt() {
        val parsed = StatementParser.parse("Name,Age\nA,1")
        assertTrue(parsed.rows.isEmpty())
        assertNotNull(parsed.problem)
    }

    @Test
    fun tabSeparatedStatementsAndTwoDigitYearsWork() {
        val tsv = "Txn Date\tDescription\tDebit\tCredit\tBalance\n01/11/26\tUPI/DR/999999999999/SHOP/YESB/x@ybl/UPI\t45.00\t\t100.00"
        val rows = StatementParser.parse(tsv).rows
        assertEquals(1, rows.size)
        assertEquals(LocalDate.parse("2026-11-01"), rows[0].date)
    }

    private fun event(id: String, type: SourceType, ms: Long, payload: String, app: String) = RawFinancialEventEntity(
        localEventId = id, sourceType = type, sourceApplication = app, sourceSender = null, sourceEventId = id,
        eventTimestamp = ms, capturedTimestamp = ms, rawPayload = payload, payloadHash = id,
        parserStatus = ParserStatus.PARSED, parserVersion = "x", uploadStatus = UploadStatus.PENDING,
    )

    private fun statementEvent(id: String, line: String, offsetSeconds: Int = 0): RawFinancialEventEntity {
        val row = StatementParser.parse(
            "Txn Date,Description,Ref No./Cheque No.,Debit,Credit,Balance\n$line",
        ).rows.single()
        return event(id, SourceType.MANUAL, StatementParser.timestampOf(row.date) + offsetSeconds * 1000L, StatementParser.payload("SBI", "5867", row), "SBI")
    }

    @Test
    fun aStatementLineJoinsThePaymentAppNotificationForTheSamePayment() {
        val statement = statementEvent("s", "10 Nov 2026,UPI/DR/412345678901/ARCHANA S/YESB/archana@ybl/UPI,412345678901,100.00,,5400.50")
        val note = event(
            "n", SourceType.NOTIFICATION, StatementParser.timestampOf(LocalDate.parse("2026-11-10")) + 3 * 3_600_000L,
            "Archana S paid you Rs 100", "com.google.android.apps.nbu.paisa.user",
        )
        // the notification text is a payment-app "you paid" form
        val paid = note.copy(rawPayload = "You paid Rs 100.00 to Archana S")
        val ledger = LedgerBuilder.build(listOf(statement, paid))
        assertEquals(1, ledger.size)
        assertEquals(listOf("Statement", "Notification").sorted(), ledger[0].sources.sorted())
        assertEquals("5867", ledger[0].accountLast4)
        assertTrue(ledger[0].timeKnown)
    }

    @Test
    fun twoStatementLinesStayTwoEvenWhenIdenticalAndAStatementOnlyPaymentHasNoTimeOfDay() {
        val a = statementEvent("a", "10 Nov 2026,TO TRANSFER-RAMESH,,10.00,,500.00", 1)
        val b = statementEvent("b", "10 Nov 2026,TO TRANSFER-RAMESH,,10.00,,490.00", 2)
        val ledger = LedgerBuilder.build(listOf(a, b))
        assertEquals(2, ledger.size)
        assertTrue(ledger.none { it.timeKnown })
    }

    @Test
    fun aStatementLineIsNotMergedWithAnotherBanksSms() {
        val statement = statementEvent("s", "10 Nov 2026,TO TRANSFER-RAMESH,,100.00,,500.00")
        val sms = event(
            "m", SourceType.SMS, StatementParser.timestampOf(LocalDate.parse("2026-11-10")) + 1000,
            "Rs.100.00 debited A/c XX0001 to RAMESH on 10-11-26 UPI Ref 555555555555", "CSBBNK",
        )
        val ledger = LedgerBuilder.build(listOf(statement, sms))
        assertEquals(2, ledger.size)
        assertFalse(ledger.any { it.sources.size > 1 })
    }
}
