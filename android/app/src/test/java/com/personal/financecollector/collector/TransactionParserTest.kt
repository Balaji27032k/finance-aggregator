package com.personal.financecollector.collector

import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Fixtures keep the real bank formats but use made-up names, references and balances. */
class TransactionParserTest {

    private val now = 1_794_000_000_000L

    private fun parse(bank: String, body: String): ParsedTransaction {
        val t = TransactionParser.parseSms(bank, body, now)
        assertNotNull("expected a parse for: $body", t)
        return t!!
    }

    @Test
    fun csbUpiDebit() {
        val t = parse(
            "CSBBNK",
            "Your A/c XXXXXXXX0001 is debited for Rs 1.00 on 10-11-26 and credited to A/c XXXXXXXX1234 (UPI Ref no.100000000001). " +
                "Not you? SMS BLOCK LAST 4 DIGITS of A/c No. to 56161 or call 18002669090/+91 2269578500.- CSB Bank",
        )
        assertEquals("DEBIT", t.direction)
        assertEquals(BigDecimal("1.00"), t.amount)
        assertEquals("0001", t.accountLast4)
        assertEquals("1234", t.counterpartyAccountLast4)
        assertNull(t.counterparty)
        assertEquals("UPI", t.paymentMode)
        assertEquals("100000000001", t.referenceNo)
        assertEquals(LocalDate.of(2026, 11, 10), t.transactionDate)
        assertNull(t.balanceAfter)
        assertEquals("CSBBNK", t.bankCode)
    }

    @Test
    fun indianBankDebitWithBalance() {
        val t = parse(
            "INDBNK",
            "Sent Rs.6.00 from A/c *0000 on 09-11-26 to Sample Merchant.RRN 000000000001.Avl Bal Rs.100.00.Not you?SMS BLOCK to 9289592895-Indian Bank",
        )
        assertEquals("DEBIT", t.direction)
        assertEquals(BigDecimal("6.00"), t.amount)
        assertEquals("0000", t.accountLast4)
        assertEquals("Sample Merchant", t.counterparty)
        assertEquals("000000000001", t.referenceNo)
        assertEquals(BigDecimal("100.00"), t.balanceAfter)
        assertEquals(LocalDate.of(2026, 11, 9), t.transactionDate)
    }

    @Test
    fun indianBankCredit() {
        val t = parse(
            "INDBNK",
            "Your A/c *0000 is credited with Rs.15000.00 on 10-09-26 by Sample Person. RRN 000000000002. Available balance is Rs. 20000.00 - Indian Bank",
        )
        assertEquals("CREDIT", t.direction)
        assertEquals(BigDecimal("15000.00"), t.amount)
        assertEquals("Sample Person", t.counterparty)
        assertEquals("000000000002", t.referenceNo)
        assertEquals(BigDecimal("20000.00"), t.balanceAfter)
        assertEquals(LocalDate.of(2026, 9, 10), t.transactionDate)
    }

    @Test
    fun sbiUpiDebitWithoutACurrencyMarker() {
        val t = parse(
            "SBIUPI",
            "Dear UPI user A/C X0000 debited by 413.00 on date 30Oct26 trf to SAMPLE STORES Refno 000000000003 If not u? call-1800111109 for other services-18001234-SBI",
        )
        assertEquals("DEBIT", t.direction)
        assertEquals(BigDecimal("413.00"), t.amount)
        assertEquals("0000", t.accountLast4)
        assertEquals("SAMPLE STORES", t.counterparty)
        assertEquals("UPI", t.paymentMode)
        assertEquals("000000000003", t.referenceNo)
        assertEquals(LocalDate.of(2026, 10, 30), t.transactionDate)
    }

    @Test
    fun sbiNeftCreditKeepsTheRemittanceInfoAsANote() {
        val t = parse(
            "SBIPSG",
            "Dear Customer, INR 50 credited to your A/c No XX0000 on 29/08/2025 through NEFT with UTR TESTUTR000000001 by SAMPLE EMPLOYER LIMITED, INFO: BATCHID:0015 SALARY AUG 2025-SBI",
        )
        assertEquals("CREDIT", t.direction)
        assertEquals(BigDecimal("50.00"), t.amount)
        assertEquals("0000", t.accountLast4)
        assertEquals("SAMPLE EMPLOYER LIMITED", t.counterparty)
        assertEquals("NEFT", t.paymentMode)
        assertEquals("TESTUTR000000001", t.referenceNo)
        assertEquals("BATCHID:0015 SALARY AUG 2025", t.note)
        assertEquals(LocalDate.of(2025, 8, 29), t.transactionDate)
    }

    @Test
    fun thousandsSeparatorsAreHandled() {
        val t = parse("INDBNK", "Sent Rs.1,25,000.50 from A/c *0000 on 09-11-26 to Sample Merchant.RRN 000000000001.")
        assertEquals(BigDecimal("125000.50"), t.amount)
    }

    @Test
    fun theBalanceIsNeverMistakenForTheAmount() {
        val t = parse("INDBNK", "Avl Bal Rs.999.00. Debit alert: your A/c *0000 debited Rs.5.00 on 09-11-26 to Shop.RRN 000000000009.")
        assertEquals(BigDecimal("5.00"), t.amount)
        assertEquals(BigDecimal("999.00"), t.balanceAfter)
    }

    @Test
    fun aMissingDateFallsBackToTheMessageDate() {
        val t = parse("SBIUPI", "Rs 10 debited from A/c X0000")
        assertEquals(BigDecimal("10.00"), t.amount)
        assertNotNull(t.transactionDate)
    }

    @Test
    fun messagesWithoutAnAmountOrDirectionAreNotGuessedAt() {
        assertNull(TransactionParser.parseSms("SBIPSG", "Dear Customer, your account statement is ready.", now))
        assertNull(TransactionParser.parseSms("SBIPSG", "Rs 500 offer for you, apply now", now))
        assertNull(TransactionParser.parseSms("SBIPSG", "Your A/c XX0000 has been credited. Please check your balance.", now))
        assertNull(TransactionParser.parseSms("SBIPSG", "", now))
    }
}
