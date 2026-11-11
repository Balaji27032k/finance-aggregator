package com.personal.financecollector.ui

import com.personal.financecollector.database.ParserStatus
import com.personal.financecollector.database.RawFinancialEventEntity
import com.personal.financecollector.database.SourceType
import com.personal.financecollector.database.UploadStatus
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EventPresenterTest {

    private fun event(type: SourceType, app: String, text: String, status: UploadStatus = UploadStatus.SYNCED) = RawFinancialEventEntity(
        localEventId = "e", sourceType = type, sourceApplication = app, sourceSender = null, sourceEventId = "s",
        eventTimestamp = 1_700_000_000_000, capturedTimestamp = 1_700_000_000_000, rawPayload = text, payloadHash = "h",
        parserStatus = ParserStatus.UNPARSED, parserVersion = "raw-1", uploadStatus = status,
    )

    @Test
    fun aParsedSmsShowsTheMainFactsAndNotTheMessage() {
        val c = EventPresenter.present(
            event(SourceType.SMS, "INDBNK", "A/c *0000 debited Rs. 86.80 on 07-12-25 to HungerBox. UPI:421960362907. Not you? SMS BLOCK to 9289592895 - Indian Bank"),
        )
        assertTrue(c.understood)
        assertEquals("HungerBox", c.title)
        assertEquals("-₹86.80", c.amount)
        assertEquals(false, c.credit)
        assertEquals("07 Dec 2025 · UPI · A/c ••0000 · INDBNK", c.details)
        assertEquals("Ref 421960362907", c.extra)
        assertEquals("Uploaded", c.status)
        assertFalse(listOf(c.title, c.details, c.extra, c.note).any { it?.contains("Not you") == true })
    }

    @Test
    fun creditsAndBalancesAreShown() {
        val c = EventPresenter.present(
            event(SourceType.SMS, "SBIPSG", "Dear Customer, INR 1,06,216.00 credited to your A/c No XX0000 on 30/04/2026 through NEFT with UTR CITIN26661327820 by SAMPLE EMPLOYER LIMITED, INFO: BATCHID:0014 SALARY APR 2026-SBI"),
        )
        assertEquals("SAMPLE EMPLOYER LIMITED", c.title)
        assertEquals("+₹1,06,216.00", c.amount)
        assertEquals(true, c.credit)
        assertEquals("BATCHID:0014 SALARY APR 2026", c.note)
    }

    @Test
    fun aNotificationIsLabelledWithTheAppName() {
        val c = EventPresenter.present(event(SourceType.NOTIFICATION, "com.google.android.apps.nbu.paisa.user", "Sample Person paid you ₹500.00", UploadStatus.PENDING))
        assertEquals("Sample Person", c.title)
        assertEquals("+₹500.00", c.amount)
        assertTrue(c.details.endsWith("GPAY"))
        assertEquals("Waiting to upload", c.status)
    }

    @Test
    fun anUnreadableMessageIsLabelledWithoutShowingItsText() {
        val c = EventPresenter.present(event(SourceType.SMS, "INDBNK", "Dear Customer, our branches will remain OPEN on Sunday"))
        assertFalse(c.understood)
        assertEquals("Not understood yet", c.title)
        assertNull(c.amount)
        assertEquals("INDBNK", c.details)
    }

    @Test
    fun failedAndRetryingUploadsAreFlaggedWithTheReason() {
        val sms = "A/c *0000 debited Rs. 86.80 on 07-12-25 to HungerBox. UPI:421960362907. Not you? SMS BLOCK to 9289592895 - Indian Bank"
        val retry = EventPresenter.present(event(SourceType.SMS, "INDBNK", sms, UploadStatus.RETRY).copy(lastError = "timeout"))
        assertEquals(StatusKind.RETRYING, retry.statusKind)
        assertEquals("Upload failed, will retry", retry.status)
        assertEquals("timeout", retry.problem)
        val failed = EventPresenter.present(event(SourceType.SMS, "INDBNK", sms, UploadStatus.FAILED_PERMANENTLY).copy(lastError = "HTTP 400"))
        assertEquals(StatusKind.FAILED, failed.statusKind)
        val done = EventPresenter.present(event(SourceType.SMS, "INDBNK", sms, UploadStatus.SYNCED).copy(lastError = "old error"))
        assertEquals(StatusKind.DONE, done.statusKind)
        assertNull(done.problem)
    }

    @Test
    fun rupeesUseIndianGrouping() {
        assertEquals("₹0.50", EventPresenter.rupees(BigDecimal("0.5")))
        assertEquals("₹999.00", EventPresenter.rupees(BigDecimal("999")))
        assertEquals("₹1,000.00", EventPresenter.rupees(BigDecimal("1000")))
        assertEquals("₹12,34,567.89", EventPresenter.rupees(BigDecimal("1234567.89")))
    }
}
