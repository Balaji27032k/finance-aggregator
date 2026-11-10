package com.personal.financecollector.collector

import com.personal.financecollector.database.ParserStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fixtures keep the real bank formats but use made-up names, references and balances. */
class SmsClassifierTest {

    private val rules = SmsRules(paused = false, allowedSenders = setOf("CSBBNK", "INDBNK", "SBIUPI", "SBIPSG"))

    private val csbDebit =
        "Your A/c XXXXXXXX0001 is debited for Rs 1.00 on 10-11-26 and credited to A/c XXXXXXXX1234 (UPI Ref no.100000000001). " +
            "Not you? SMS BLOCK LAST 4 DIGITS of A/c No. to 56161 or call 18002669090/+91 2269578500.- CSB Bank"
    private val indianDebit =
        "Sent Rs.6.00 from A/c *0000 on 09-11-26 to Sample Merchant.RRN 000000000001.Avl Bal Rs.100.00.Not you?SMS BLOCK to 9289592895-Indian Bank"
    private val indianCredit =
        "Your A/c *0000 is credited with Rs.15000.00 on 10-09-26 by Sample Person. RRN 000000000002. Available balance is Rs. 20000.00 - Indian Bank"
    private val sbiUpiDebit =
        "Dear UPI user A/C X0000 debited by 413.00 on date 30Oct26 trf to SAMPLE STORES Refno 000000000003 If not u? call-1800111109 for other services-18001234-SBI"
    private val sbiNeftCredit =
        "Dear Customer, INR 50 credited to your A/c No XX0000 on 29/08/2025 through NEFT with UTR TESTUTR000000001 by SAMPLE EMPLOYER LIMITED, INFO: BATCHID:0015 SALARY AUG 2025-SBI"

    private fun accepted(sender: String, body: String): SmsClassification.Accept {
        val result = SmsClassifier.classify(sender, body, rules)
        assertTrue("expected Accept but was $result", result is SmsClassification.Accept)
        return result as SmsClassification.Accept
    }

    @Test
    fun senderHeadersWithDifferentRoutesMapToTheSameEntity() {
        assertEquals("CSBBNK", SmsSender.entityCode("VA-CSBBNK-T"))
        assertEquals("INDBNK", SmsSender.entityCode("BT-INDBNK-S"))
        assertEquals("INDBNK", SmsSender.entityCode("VM-INDBNK-S"))
        assertEquals("INDBNK", SmsSender.entityCode("AD-INDBNK-S"))
        assertEquals("SBIUPI", SmsSender.entityCode("VM-SBIUPI-S"))
        assertEquals("SBIPSG", SmsSender.entityCode("AD-SBIPSG-S"))
        assertEquals("SBIUPI", SmsSender.entityCode("sbiupi"))
    }

    @Test
    fun phoneNumbersAndOddHeadersAreNotSenderIds() {
        assertNull(SmsSender.entityCode("+919876543210"))
        assertNull(SmsSender.entityCode("9876543210"))
        assertNull(SmsSender.entityCode(null))
        assertNull(SmsSender.entityCode("A B"))
    }

    @Test
    fun csbDebitIsCaptured() {
        val a = accepted("VA-CSBBNK-T", csbDebit)
        assertEquals("CSBBNK", a.entityCode)
        assertEquals(ParserStatus.UNPARSED, a.parserStatus)
    }

    @Test
    fun indianBankDebitAndCreditAreCaptured() {
        accepted("BT-INDBNK-S", indianDebit)
        accepted("VM-INDBNK-S", indianCredit)
    }

    @Test
    fun sbiUpiDebitWithoutCurrencyMarkerIsCaptured() {
        accepted("VM-SBIUPI-S", sbiUpiDebit)
    }

    @Test
    fun sbiNeftCreditIsCaptured() {
        accepted("AD-SBIPSG-S", sbiNeftCredit)
    }

    @Test
    fun smsFromOtherSendersIsNeverCaptured() {
        assertEquals(SmsClassification.SenderNotAllowed, SmsClassifier.classify("VM-HDFCBK-S", csbDebit, rules))
        assertEquals(SmsClassification.SenderNotAllowed, SmsClassifier.classify("+919876543210", csbDebit, rules))
    }

    @Test
    fun otpFromAnAllowedSenderIsDropped() {
        val body = "123456 is your OTP for UPI transaction of Rs 500 at SAMPLE. Do not share it with anyone. -SBI"
        assertEquals(SmsClassification.Filtered, SmsClassifier.classify("VM-SBIUPI-S", body, rules))
    }

    @Test
    fun promotionalMessageFromAnAllowedSenderIsDropped() {
        val body = "Dear Customer, upgrade to the new app today and explore more offers. -SBI"
        assertEquals(SmsClassification.Filtered, SmsClassifier.classify("AD-SBIPSG-S", body, rules))
    }

    @Test
    fun pausedCollectionCapturesNothing() {
        val paused = rules.copy(paused = true)
        assertEquals(SmsClassification.Paused, SmsClassifier.classify("VA-CSBBNK-T", csbDebit, paused))
    }

    @Test
    fun disabledSenderIsNotCaptured() {
        val withoutCsb = rules.copy(allowedSenders = rules.allowedSenders - "CSBBNK")
        assertEquals(SmsClassification.SenderNotAllowed, SmsClassifier.classify("VA-CSBBNK-T", csbDebit, withoutCsb))
    }

    @Test
    fun capturedPayloadKeepsTheReferenceNumber() {
        assertTrue(accepted("VM-SBIUPI-S", sbiUpiDebit).payload.contains("000000000003"))
        assertFalse(accepted("VM-SBIUPI-S", sbiUpiDebit).payload.isBlank())
    }
}
