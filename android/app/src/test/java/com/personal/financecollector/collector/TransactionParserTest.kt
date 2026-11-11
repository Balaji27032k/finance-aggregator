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

    // --- formats seen in the real inbox (values replaced) ---

    @Test
    fun indianBankUpiDebitWithAReferenceAfterAColon() {
        val t = parse("INDBNK", "A/c *0000 debited Rs. 30.00 on 06-12-25 to Chennai Unif. UPI:570639294703. Not you? SMS BLOCK to 9289592895, Dial 1930 for Cyber Fraud - Indian Bank")
        assertEquals("DEBIT", t.direction)
        assertEquals(BigDecimal("30.00"), t.amount)
        assertEquals("Chennai Unif", t.counterparty)
        assertEquals("570639294703", t.referenceNo)
        assertEquals("UPI", t.paymentMode)
        assertEquals(LocalDate.of(2025, 12, 6), t.transactionDate)
    }

    @Test
    fun indianBankCreditFromAVpaKeepsTheVpaAsCounterparty() {
        val t = parse("INDBNK", "Rs.500.00 credited to a/c *0000 on 23/12/2025 by a/c linked to VPA 8056521496@ibl (UPI Ref no 065315010967).Indian Bank")
        assertEquals("CREDIT", t.direction)
        assertEquals(BigDecimal("500.00"), t.amount)
        assertEquals("8056521496@ibl", t.counterparty)
        assertEquals("065315010967", t.referenceNo)
        assertEquals(LocalDate.of(2025, 12, 23), t.transactionDate)
    }

    @Test
    fun debitCardSpendAndAtmWithdrawalCarryTheCardOrAccount() {
        val card = parse("INDBNK", "Rs.100.00 spent using Indian Bank Debit Card XX4219 at EATGOOD TECHNOL on 03-02-26 19:39 hr. Avl Bal Rs.545.83.Not you? SMS BLOCK to 9231000001")
        assertEquals("4219", card.accountLast4)
        assertEquals("EATGOOD TECHNOL", card.counterparty)
        assertEquals("CARD", card.paymentMode)
        assertEquals(BigDecimal("545.83"), card.balanceAfter)
        val atm = parse("INDBNK", "Rs.9000.00 withdrawn from A/c XX1899 at ATM:S1C014641 on 10-01-26 20:02 hr. Avl Bal Rs.5635.83.Not you? SMS BLOCK to 9231000001")
        assertEquals("DEBIT", atm.direction)
        assertEquals("ATM", atm.paymentMode)
        assertEquals("1899", atm.accountLast4)
    }

    @Test
    fun csbStructuredUpiDebitGivesPayeeReferenceAndRemark() {
        val t = parse(
            "CSBBNK",
            "INR 10,000.00 debited from A/c XXXXXXXXX0000 towards UPI/DR/750935411102/SAMPLE PERSON/SBIN/sample98@oksb/Payment fr on 12-AUG-2025.Avail. Bal. is Rs.INR 8,157.79-CSB",
        )
        assertEquals(BigDecimal("10000.00"), t.amount)
        assertEquals("SAMPLE PERSON", t.counterparty)
        assertEquals("750935411102", t.referenceNo)
        assertEquals("Payment fr", t.note)
        assertEquals(BigDecimal("8157.79"), t.balanceAfter)
        assertEquals(LocalDate.of(2025, 8, 12), t.transactionDate)
    }

    @Test
    fun csbDepositAndInterestCredits() {
        val dep = parse("CSBBNK", "INR 100.00 deposited to A/c XXXXXXXXX0000 on 13-AUG-2025.Clear Bal is INR 8,257.79-CSB Bank")
        assertEquals("CREDIT", dep.direction)
        assertEquals(BigDecimal("8257.79"), dep.balanceAfter)
        val interest = parse("CSBBNK", "Your SB A/c no. XXXXXXXXX0000 has been credited with quarterly int. of Rs. INR 48.00. Avl. Bal INR 2,411.75 - CSB Bank")
        assertEquals(BigDecimal("48.00"), interest.amount)
        assertEquals("Interest", interest.note)
    }

    @Test
    fun sbiYonoTransferNamesTheOwnAccountAsCounterpartyAccount() {
        val t = parse("SBYONO", "Your Ac x0000 debited Rs.1,000.00 for transfer to Sample Ac x1111 dt 02.07.26 Ref 618311524982. If not done by you, call 1800111109. YONO SBI")
        assertEquals("DEBIT", t.direction)
        assertEquals("Sample", t.counterparty)
        assertEquals("1111", t.counterpartyAccountLast4)
        assertEquals(LocalDate.of(2026, 7, 2), t.transactionDate)
    }

    @Test
    fun sbiCardPurchaseAtmAndReversal() {
        val pos = parse("ATMSBI", "Dear Customer, transaction number 521218950948 for Rs.110.00 by SBI Debit Card X0000 done at EP099558 on 31Jul25 at 18:31:24. Your updated available balance is Rs.71272.45. If not done by you, forward this SMS")
        assertEquals("DEBIT", pos.direction)
        assertEquals("0000", pos.accountLast4)
        assertEquals("521218950948", pos.referenceNo)
        assertEquals(BigDecimal("71272.45"), pos.balanceAfter)
        val atm = parse("ATMSBI", "Dear SBI Customer, Rs.9000 withdrawn at INB ATM S1C01464 from A/cX0000 on 10Jan26 Transaction Number 601019001449. Available Balance Rs.26099.64. If not withdrawn by you")
        assertEquals("ATM", atm.paymentMode)
        assertEquals("INB ATM S1C01464", atm.counterparty)
        val reversal = parse("CBSSBI", "Your A/C XXXXX530000 has credit for UPI/REVERSAL/617190730342/2006 of Rs 216.92 on 21/06/26. Avl Bal Rs 4,552.65.-SBI")
        assertEquals("CREDIT", reversal.direction)
        assertEquals("UPI reversal", reversal.note)
    }

    @Test
    fun autopayChargeIsParsedButItsAdvanceNoticeIsNot() {
        val charge = parse("INDBNK", "A/c *0000 debited by Rs.707.00 towards mandate for AutoPay Bha on 07/09/2026.RRN:625077183480.Avl bal is Rs.3973.93 -INDIAN BANK")
        assertEquals("AUTOPAY", charge.paymentMode)
        assertEquals("625077183480", charge.referenceNo)
        assertNull(TransactionParser.parseSms("INDBNK", "Your account will be debited with Rs 707.00 towards PhonePe for the Autopay on 06-Jan-26.Pause mandate to stop execution - Indian Bank", now))
        assertNull(TransactionParser.parseSms("INDBNK", "For the upcoming mandate set for 06-Aug-25 ,your account will be debited with INR 707.00 towards PhonePe for the Autopay ,521799178156.", now))
    }

    @Test
    fun notificationsThatAreNotMoneyMovementsAreNeverTransactions() {
        listOf(
            "INDBNK" to "Your last ECOM txn has declined due to txn facility not enabled in Card. Not you? SMS BLOCK to 9289592895",
            "INDBNK" to "Transaction declined due to insufficient bal in acc XXXX010000.Service charge of Rs.18 will be applicable for txn with insuff bal.Avl Bal:Rs.146-Indian Bank.",
            "INDBNK" to "Earn 2pts/Rs100 spent on Debit Card T&C-Indian Bank",
            "INDBNK" to "OTP for IndSMART Mobile Registration/Re-registration is 328122.Not You?SMS BLOCK to 9289592895",
            "CSBBNK" to "Last transaction in A/C xxxx07-190001: 19-04-2025 Credit 10,000.00.Cur Bal: 29686.56 This includes uncleared effects, if any.-CSB",
            "CSBBNK" to "Your OTP for New Deposit of INR 50,000.00 from account ending XXXXXX7255418 is 077946. Valid for 5 mins.",
            "ICICIT" to "IRCTC Web UPI has requested money through Google-pay. On approval, Rs 140.00 will be debited from your Bank Account-ICICI Bank.",
            "SBIUPI" to "Ur 1st UPI Txn of Rs118.0 frm SBIN A/cX0000 is successful. If it's not u, visit ur branch",
        ).forEach { (bank, text) -> assertNull(text, TransactionParser.parseSms(bank, text, now)) }
    }

    @Test
    fun onlyBankSendersAreParsed() {
        assertNull(TransactionParser.parseSms("VICARE", "Rs218 recharged! Enjoy 3GB. debited from A/c X0000", now))
        assertNull(TransactionParser.parseSms("PHONPE", "Rs.707 will be debited", now))
    }
}
