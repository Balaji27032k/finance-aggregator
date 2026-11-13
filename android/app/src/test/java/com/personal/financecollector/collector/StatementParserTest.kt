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

    // Same layout as a YONO statement: preamble, quoted descriptions wrapped mid-word onto lines that start with a space, a summary footer.
    private val sample = "\"Mr. A  Person\nperson@example.com\",\"State Bank of India  \nSOMEWHERE\",,,,\n" +
        "Date of Statement  :  13-11-2026,Branch Code  :  918,,,,\n" +
        "Limit  :  0.00,Account Number  :  40490535867,,,,\n" +
        "Date,Details,Ref No/Cheque No,Debit,Credit,Balance\n" +
        "05/10/2021,\" CASH DEPOSIT SELF  \n  AT 00918 SIVAGANGA\",,,1000.00,1000.00\n" +
        "10/11/2026,\" WDL TFR   UPI/DR/412345678901/ARCHANA /FDRL/arc\n hanaraj/tea   0097695162091 AT 00918 SIVAGANGA\",000000,20.00,,980.50\n" +
        "11/11/2026,\" DEP TFR   UPI/CR/412345678902/Mr SAMPL/IDIB/975\n 1015210/Paym   0097735162098 AT 00918 SIVAGANGA\",,,\"1,500.00\",\"2,480.50\"\n" +
        "12/11/2026,\" ATM WDL   ATM CASH 5847  SIV\n AGANGA OFFSITE     SIVAGANGA\",,1000.00,,1480.50\n" +
        ",,,,,\n" +
        "Statement Summary : 01-01-2021  To  13-11-2026,,,,,\n" +
        "Brought Forward (?),Dr Count,Cr Count,Total Debits (?),Total Credits (?),Closing Balance (?)\n" +
        "0.00,1229,337,\"27,61,583.34\",\"28,00,242.97\",\"1,480.50CR\"\n"

    private fun narrate(text: String) = StatementParser.narrate(StatementParser.description(text))

    @Test
    fun readsWrappedQuotedLinesAndIgnoresTheFooter() {
        val parsed = StatementParser.parse(sample)
        assertNull(parsed.problem)
        assertEquals("40490535867", parsed.accountNumber)
        assertEquals(4, parsed.rows.size)
        assertEquals(0, parsed.skipped)
        assertEquals(LocalDate.parse("2021-10-05"), parsed.rows[0].date)
        assertEquals(BigDecimal("1000.00"), parsed.rows[0].credit)
        assertEquals(BigDecimal("20.00"), parsed.rows[1].debit)
        assertEquals(BigDecimal("2480.50"), parsed.rows[2].balance)
        assertEquals(BigDecimal("1500.00"), parsed.rows[2].credit)
    }

    @Test
    fun aWrappedLineIsJoinedWithoutInventingASpace() {
        val d = StatementParser.parse(sample).rows[2].description
        assertEquals("DEP TFR UPI/CR/412345678902/Mr SAMPL/IDIB/9751015210/Paym 0097735162098 AT 00918 SIVAGANGA", d)
    }

    @Test
    fun aUpiLineGivesPersonReferenceAndTheTypedRemark() {
        val n = narrate(" WDL TFR   UPI/DR/412345678901/ARCHANA /FDRL/arc\n hanaraj/tea   0097695162091 AT 00918 SIVAGANGA")
        assertEquals("ARCHANA", n.counterparty)
        assertEquals("UPI", n.mode)
        assertEquals("412345678901", n.reference)
        assertEquals("tea", n.note)
    }

    @Test
    fun theAppsDefaultRemarkIsNotTreatedAsANote() {
        assertNull(narrate(" DEP TFR   UPI/CR/412345678902/Mr SAMPL/IDIB/975\n 1015210/Paym   0097735162098 AT 00918 SIVAGANGA").note)
        assertNull(narrate(" WDL TFR   UPI/DR/412345678902/Mr SAMPL/IDIB/ndevathana/UPI   0097735162098 AT 00918 SIVAGANGA").note)
    }

    @Test
    fun otherLineKindsAreRecognised() {
        assertEquals("ATM", narrate(" ATM WDL   ATM CASH 5847  SIV\n AGANGA OFFSITE     SIVAGANGA").mode)
        assertNull(narrate(" ATM WDL   ATM CASH 5847  SIV\n AGANGA OFFSITE     SIVAGANGA").counterparty)
        val pos = narrate(" POS ATM PURCH   OTHPOS4225172928\n 28EATGOOD TECHNOLOGIES PBangalore")
        assertEquals("CARD", pos.mode)
        assertEquals("EATGOOD TECHNOLOGIES PBangalore", pos.counterparty)
        val neft = narrate(" DEP TFR   NEFT*CITI0100000*CITIN21262511717*TAT\n A CONSULTANC   0099970044309 AT 00918 SIVAGANGA")
        assertEquals("TATA CONSULTANC", neft.counterparty)
        assertEquals("CITIN21262511717", neft.reference)
        assertEquals("IMPS", narrate(" DEP TFR   INB IMPS127816538132/4443325375/XX187\n 6/Send Money   0097936162097 AT 00918 SIVAGANGA").mode)
        assertEquals("UPI reversal", narrate(" DEP TFR   UPI/REV/416854878173  \n  0097696162090 AT 00918 SIVAGANGA").note)
        assertEquals("Bank charges", narrate(" DEBIT   ATMCard \n AMC  652294*2458").counterparty)
        assertEquals("Interest", narrate(" INTERES\n T CREDIT").counterparty)
    }

    @Test
    fun aStatementLineBecomesATransactionWithItsAccount() {
        val row = StatementParser.parse(sample).rows[1]
        val t = StatementParser.parseEvent(StatementParser.payload("SBI", "5867", row))!!
        assertEquals("DEBIT", t.direction)
        assertEquals("5867", t.accountLast4)
        assertEquals("412345678901", t.referenceNo)
        assertEquals(BigDecimal("980.50"), t.balanceAfter)
    }

    @Test
    fun aPasswordProtectedExcelStatementOpensWithTheRightPassword() {
        val bytes = resource("/statement_encrypted.xlsx")
        val parsed = StatementParser.parseFile(bytes, "test-password-1")
        assertNull(parsed.problem)
        assertEquals(3, parsed.rows.size)
        assertEquals(LocalDate.parse("2026-11-10"), parsed.rows[0].date)
        assertEquals(BigDecimal("20.00"), parsed.rows[0].debit)
        assertEquals(BigDecimal("500.00"), parsed.rows[1].credit)
        assertEquals("ARCHANA", StatementParser.narrate(parsed.rows[0].description).counterparty)
    }

    @Test
    fun aWrongOrMissingPasswordIsReportedNotGuessed() {
        val bytes = resource("/statement_encrypted.xlsx")
        assertTrue(StatementParser.parseFile(bytes, "nope").problem!!.contains("did not open"))
        assertTrue(StatementParser.parseFile(bytes, null).problem!!.contains("password"))
    }

    @Test
    fun anExcelStatementWithoutAPasswordWorksToo() {
        val parsed = StatementParser.parseFile(resource("/statement_plain.xlsx"), null)
        assertNull(parsed.problem)
        assertEquals(3, parsed.rows.size)
        val n = StatementParser.narrate(parsed.rows[0].description)
        assertEquals("ARCHANA", n.counterparty)
        assertEquals("tea", n.note)
        assertEquals("ATM", StatementParser.narrate(parsed.rows[2].description).mode)
    }

    @Test
    fun aFileWithoutTheExpectedColumnsIsNotGuessedAt() {
        val parsed = StatementParser.parse("Name,Age\nA,1")
        assertTrue(parsed.rows.isEmpty())
        assertNotNull(parsed.problem)
    }

    @Test
    fun excelDateNumbersAndTabSeparatedFilesAreUnderstood() {
        assertEquals(LocalDate.parse("2021-10-05"), StatementParser.parseDate("44474"))
        val tsv = "Txn Date\tDescription\tDebit\tCredit\tBalance\n01/11/26\tUPI/DR/999999999999/SHOP/YESB/x@ybl/UPI\t45.00\t\t100.00"
        assertEquals(LocalDate.parse("2026-11-01"), StatementParser.parse(tsv).rows.single().date)
    }

    private fun resource(name: String) = StatementParserTest::class.java.getResourceAsStream(name)!!.readBytes()

    private fun event(id: String, type: SourceType, ms: Long, payload: String, app: String) = RawFinancialEventEntity(
        localEventId = id, sourceType = type, sourceApplication = app, sourceSender = null, sourceEventId = id,
        eventTimestamp = ms, capturedTimestamp = ms, rawPayload = payload, payloadHash = id,
        parserStatus = ParserStatus.PARSED, parserVersion = "x", uploadStatus = UploadStatus.PENDING,
    )

    private fun statementEvent(id: String, line: String, offsetSeconds: Int = 0): RawFinancialEventEntity {
        val row = StatementParser.parse("Date,Details,Ref No/Cheque No,Debit,Credit,Balance\n$line").rows.single()
        return event(id, SourceType.MANUAL, StatementParser.timestampOf(row.date) + offsetSeconds * 1000L, StatementParser.payload("SBI", "5867", row), "SBI")
    }

    @Test
    fun paymentAppNotificationsAreNotTransactions() {
        val n = event("n", SourceType.NOTIFICATION, 1_000_000_000_000L, "You paid Rs 100.00 to Archana S", "com.google.android.apps.nbu.paisa.user")
        assertNull(TransactionParser.parse(n))
    }

    @Test
    fun aStatementLineAndTheBanksOwnSmsForTheSamePaymentAreOneTransaction() {
        val statement = statementEvent("s", "10/11/2026,UPI/DR/412345678901/SHOP/YESB/shop@ybl/UPI 0097695162091 AT 00918 SIVAGANGA,,100.00,,5400.50")
        val sms = event(
            "m", SourceType.SMS, StatementParser.timestampOf(LocalDate.parse("2026-11-10")) + 3 * 3_600_000L,
            "Rs.100.00 debited A/c XX5867 to SHOP on 10-11-26 UPI Ref 412345678901", "SBIUPI",
        )
        val ledger = LedgerBuilder.build(listOf(statement, sms))
        assertEquals(1, ledger.size)
        assertEquals(listOf("SMS", "Statement"), ledger[0].sources.sorted())
        assertTrue(ledger[0].timeKnown)
    }

    @Test
    fun twoStatementLinesStayTwoEvenWhenIdenticalAndHaveNoTimeOfDay() {
        val a = statementEvent("a", "10/11/2026,TO TRANSFER-RAMESH,,10.00,,500.00", 1)
        val b = statementEvent("b", "10/11/2026,TO TRANSFER-RAMESH,,10.00,,490.00", 2)
        val ledger = LedgerBuilder.build(listOf(a, b))
        assertEquals(2, ledger.size)
        assertTrue(ledger.none { it.timeKnown })
    }

    @Test
    fun aStatementLineIsNotMergedWithAnotherBanksSms() {
        val statement = statementEvent("s", "10/11/2026,TO TRANSFER-RAMESH,,100.00,,500.00")
        val sms = event(
            "m", SourceType.SMS, StatementParser.timestampOf(LocalDate.parse("2026-11-10")) + 1000,
            "Rs.100.00 debited A/c XX0001 to RAMESH on 10-11-26 UPI Ref 555555555555", "CSBBNK",
        )
        val ledger = LedgerBuilder.build(listOf(statement, sms))
        assertEquals(2, ledger.size)
        assertFalse(ledger.any { it.sources.size > 1 })
    }
}
