package com.personal.financecollector.collector

import com.personal.financecollector.database.ParserStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationFilterTest {

    private fun content(title: String? = null, text: String? = null, bigText: String? = null) =
        NotificationContent(title, text, bigText, emptyList(), emptyList())

    @Test
    fun amountWithStrongKeywordIsCaptured() {
        val d = NotificationFilter.evaluate(content("Alert", "INR 500.00 credited to A/c XX0000"))
        assertTrue(d is FilterDecision.Capture)
        assertEquals(ParserStatus.UNPARSED, (d as FilterDecision.Capture).parserStatus)
    }

    @Test
    fun rupeeSymbolAndRsPrefixAreRecognised() {
        assertTrue(NotificationFilter.evaluate(content(text = "₹1,250.50 debited")) is FilterDecision.Capture)
        assertTrue(NotificationFilter.evaluate(content(text = "Rs.99 paid via UPI")) is FilterDecision.Capture)
    }

    @Test
    fun strongKeywordWithoutAmountNeedsReview() {
        val d = NotificationFilter.evaluate(content("Payment received"))
        assertEquals(ParserStatus.NEEDS_REVIEW, (d as FilterDecision.Capture).parserStatus)
    }

    @Test
    fun unrelatedNotificationIsDropped() {
        assertEquals(FilterDecision.DropUnrelated, NotificationFilter.evaluate(content("Offer", "Get 20% off on your next order")))
        assertEquals(FilterDecision.DropUnrelated, NotificationFilter.evaluate(content()))
    }

    @Test
    fun otpMessageIsDropped() {
        val d = NotificationFilter.evaluate(content(text = "123456 is your OTP for payment of Rs 500. Do not share."))
        assertEquals(FilterDecision.DropOtp, d)
    }

    @Test
    fun transactionAlertThatMentionsOtpIsKeptWithCodeMasked() {
        val d = NotificationFilter.evaluate(content(text = "Rs 500 debited from A/c XX0000. OTP 987654 was used."))
        val payload = (d as FilterDecision.Capture).payload
        assertFalse(payload.contains("987654"))
        assertTrue(payload.contains("OTP"))
    }

    @Test
    fun bigTextIsPreferredOverTruncatedText() {
        val d = NotificationFilter.evaluate(content("Alert", "INR 5...", "INR 500.00 credited to A/c XX0000, Ref 1234"))
        val payload = (d as FilterDecision.Capture).payload
        assertTrue(payload.contains("Ref 1234"))
        assertFalse(payload.contains("INR 5..."))
    }

    @Test
    fun payloadIsCapped() {
        val d = NotificationFilter.evaluate(content(bigText = "INR 500 credited " + "x".repeat(5000)))
        assertTrue((d as FilterDecision.Capture).payload.length <= 2000)
    }
}
