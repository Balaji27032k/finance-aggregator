package com.personal.financecollector.export

import com.personal.financecollector.sync.FakeConfig
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportLogicTest {

    private fun b64(s: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray())

    private fun mail(from: String, subject: String, parts: List<Pair<String, String>>, id: String = "m1", date: Long = 1_700_000_000_000): String {
        val partsJson = JSONArray()
        parts.forEach { (type, text) -> partsJson.put(JSONObject().put("mimeType", type).put("body", JSONObject().put("data", b64(text)))) }
        return JSONObject()
            .put("id", id).put("internalDate", date.toString()).put("labelIds", JSONArray(listOf("INBOX", "CATEGORY_UPDATES")))
            .put(
                "payload",
                JSONObject().put("mimeType", "multipart/alternative")
                    .put("headers", JSONArray().put(JSONObject().put("name", "From").put("value", from)).put(JSONObject().put("name", "Subject").put("value", subject)))
                    .put("parts", partsJson),
            ).toString()
    }

    @Test
    fun otpDigitsAreMaskedButAmountsAndReferencesAreNot() {
        assertEquals("Your OTP is ****. Do not share.", ExportMasking.maskOtp("Your OTP is 123456. Do not share."))
        assertEquals("**** is your one time password", ExportMasking.maskOtp("482913 is your one time password"))
        val txn = "Rs 5000 debited from A/c XX0000 on 09-11-26 Ref 123456789012"
        assertEquals(txn, ExportMasking.maskOtp(txn))
    }

    @Test
    fun personalNumbersAreSkippedButHeadersAndShortCodesAreNot() {
        assertTrue(ExportMasking.isPersonalNumber("+919876543210"))
        assertTrue(ExportMasking.isPersonalNumber("98765 43210"))
        assertFalse(ExportMasking.isPersonalNumber("VM-SBIUPI-S"))
        assertFalse(ExportMasking.isPersonalNumber("56161"))
        assertFalse(ExportMasking.isPersonalNumber(null))
    }

    @Test
    fun theUploadJsonCarriesTheMessageAndNeverExceedsTheLimits() {
        val long = "x".repeat(20_000)
        val json = JSONObject(StagingJson.build(listOf(StagedMessage("EMAIL", "id1", "a@b.c", "subj", 1_700_000_000_000, long, false, "INBOX"))))
        val m = json.getJSONArray("messages").getJSONObject(0)
        assertEquals("EMAIL", m.getString("channel"))
        assertEquals(StagingJson.MAX_BODY, m.getString("body").length)
        assertTrue(m.getBoolean("bodyTruncated"))
        assertEquals("2023-11-14T22:13:20Z", m.getString("receivedAt"))
        val blank = JSONObject(StagingJson.build(listOf(StagedMessage("SMS", "i", null, null, 0, "  ", false, null))))
        assertEquals("(empty)", blank.getJSONArray("messages").getJSONObject(0).getString("body"))
    }

    @Test
    fun theGmailQueryExcludesJobPortalsAndYoutubeButKeepsFinanceTerms() {
        val q = GmailQuery.build()
        assertTrue(q.contains("-from:(naukri OR linkedin OR indeed"))
        assertTrue(q.contains("youtube"))
        assertTrue(q.contains("debited") && q.contains("UPI") && q.contains("statement"))
        assertTrue(q.contains("-category:promotions"))
    }

    @Test
    fun blockedSendersIncludeGoogleNoiseButNotGooglePayReceipts() {
        assertTrue(GmailQuery.isBlockedSender("\"Naukri\" <info@naukri.com>"))
        assertTrue(GmailQuery.isBlockedSender("LinkedIn <jobs-listings@linkedin.com>"))
        assertTrue(GmailQuery.isBlockedSender("YouTube <noreply@youtube.com>"))
        assertTrue(GmailQuery.isBlockedSender("Google <no-reply@accounts.google.com>"))
        assertFalse(GmailQuery.isBlockedSender("Google Pay <googlepay-noreply@google.com>"))
        assertFalse(GmailQuery.isBlockedSender("Google Play <googleplay-noreply@google.com>"))
        assertFalse(GmailQuery.isBlockedSender("HDFC Bank <alerts@hdfcbank.net>"))
    }

    @Test
    fun plainTextMailIsExtractedWithItsHeaders() {
        val parsed = GmailMessageParser.parse(
            mail("HDFC Bank <alerts@hdfcbank.net>", "Account update", listOf("text/plain" to "Rs 500.00 debited\nfrom A/c XX0000", "text/html" to "<p>ignored</p>")),
        )
        val m = parsed.message!!
        assertEquals("EMAIL", m.channel)
        assertEquals("m1", m.externalId)
        assertEquals("HDFC Bank <alerts@hdfcbank.net>", m.sender)
        assertEquals("Account update", m.subject)
        assertEquals("Rs 500.00 debited from A/c XX0000", m.body)
        assertEquals("INBOX,CATEGORY_UPDATES", m.labels)
        assertEquals(1_700_000_000_000, parsed.internalDateMs)
    }

    @Test
    fun htmlOnlyMailIsConvertedToText() {
        val html = "<html><style>p{color:red}</style><body><p>Amount&nbsp;paid: <b>Rs&#46; 250</b></p><br>Thanks &amp; regards</body></html>"
        val m = GmailMessageParser.parse(mail("Swiggy <noreply@swiggy.in>", "Receipt", listOf("text/html" to html))).message!!
        assertEquals("Amount paid: Rs. 250 Thanks & regards", m.body)
    }

    @Test
    fun filteredSendersProduceNoMessageButKeepTheDate() {
        val parsed = GmailMessageParser.parse(mail("Naukri <info@naukri.com>", "Jobs for you", listOf("text/plain" to "x"), date = 5))
        assertNull(parsed.message)
        assertEquals(5L, parsed.internalDateMs)
    }

    @Test
    fun encodedHeaderWordsAreDecoded() {
        assertEquals("Payment received", GmailMessageParser.decodeWords("=?UTF-8?B?UGF5bWVudCByZWNlaXZlZA==?="))
        assertEquals("Rs 50 paid", GmailMessageParser.decodeWords("=?UTF-8?Q?Rs_50_paid?="))
        assertEquals("caf\u00e9", GmailMessageParser.decodeWords("=?UTF-8?Q?caf=C3=A9?="))
        assertEquals("plain subject", GmailMessageParser.decodeWords("plain subject"))
    }

    // --- exporters ---

    private class RecordingSink(private val outcomes: MutableList<StagingOutcome> = mutableListOf()) : StagingSink {
        val batches = mutableListOf<List<StagedMessage>>()
        override suspend fun upload(messages: List<StagedMessage>): StagingOutcome {
            batches += messages.toList()
            return if (outcomes.isEmpty()) StagingOutcome.Stored(messages.size, 0) else outcomes.removeAt(0)
        }
    }

    private class FakeSms(private val rows: List<SmsRow>) : SmsReader {
        override suspend fun read(afterId: Long, onRow: suspend (SmsRow) -> Unit) {
            rows.filter { it.id > afterId }.forEach { onRow(it) }
        }
    }

    private val smsRows = listOf(
        SmsRow(1, "VM-SBIUPI-S", "Rs 10 debited from A/c X0000", 1000),
        SmsRow(2, "+919876543210", "hey are you free tonight", 2000),
        SmsRow(3, "AD-SBIPSG-S", "123456 is your OTP for login", 3000),
        SmsRow(4, null, "no sender", 4000),
        SmsRow(5, "VA-CSBBNK-T", "Your A/c is debited for Rs 1.00", 5000),
    )

    @Test
    fun smsExportSkipsPersonalChatsMasksOtpsAndCheckpoints() = runBlocking {
        val config = FakeConfig()
        val progress = ExportProgress(config, "sms")
        val sink = RecordingSink()
        assertEquals(ExportResult.Complete, SmsExporter(FakeSms(smsRows), progress, sink, batchSize = 2).run())

        val sent = sink.batches.flatten()
        assertEquals(listOf("VM-SBIUPI-S", "AD-SBIPSG-S", "VA-CSBBNK-T"), sent.map { it.sender })
        assertEquals("**** is your OTP for login", sent[1].body)
        assertEquals(2, sink.batches.size)
        assertEquals("5", progress.text(ExportProgress.CHECKPOINT))
        assertEquals(5L, progress.number(ExportProgress.SEEN))
        assertEquals(2L, progress.number(ExportProgress.SKIPPED))
        assertEquals(3L, progress.number(ExportProgress.UPLOADED))
        assertEquals(ExportState.COMPLETE, progress.text(ExportProgress.STATE))
    }

    @Test
    fun smsExportResumesAfterATransientFailureWithoutResendingStoredBatches() = runBlocking {
        val config = FakeConfig()
        val progress = ExportProgress(config, "sms")
        val sink = RecordingSink(mutableListOf(StagingOutcome.Stored(1, 0), StagingOutcome.Transient("HTTP 503")))
        val first = SmsExporter(FakeSms(smsRows), progress, sink, batchSize = 1).run()
        assertTrue(first is ExportResult.Retry)
        assertEquals("1", progress.text(ExportProgress.CHECKPOINT))

        val second = RecordingSink()
        assertEquals(ExportResult.Complete, SmsExporter(FakeSms(smsRows), progress, second, batchSize = 1).run())
        assertEquals(listOf("AD-SBIPSG-S", "VA-CSBBNK-T"), second.batches.flatten().map { it.sender })
    }

    @Test
    fun anUnauthorizedBackendFailsTheExportWithAClearReason() = runBlocking {
        val progress = ExportProgress(FakeConfig(), "sms")
        val result = SmsExporter(FakeSms(smsRows), progress, RecordingSink(mutableListOf(StagingOutcome.Unauthorized))).run()
        assertTrue(result is ExportResult.Failed)
        assertEquals(ExportState.FAILED, progress.text(ExportProgress.STATE))
        assertTrue(progress.text(ExportProgress.ERROR)!!.contains("Pair"))
    }

    private class FakeMail(private val pages: List<List<String>>, private val bodies: Map<String, String>) : MailSource {
        val queries = mutableListOf<String>()
        override suspend fun list(query: String, pageToken: String?): MailPage {
            queries += query
            val i = pageToken?.toInt() ?: 0
            return MailPage(pages[i], if (i + 1 < pages.size) (i + 1).toString() else null, 42)
        }

        override suspend fun get(id: String): String? = bodies[id]
    }

    @Test
    fun gmailExportUploadsKeptMailSkipsFilteredAndMissingOnesAndCheckpointsByDate() = runBlocking {
        val bodies = mapOf(
            "a" to mail("HDFC <alerts@hdfcbank.net>", "Alert", listOf("text/plain" to "debited"), id = "a", date = 3_000_000),
            "b" to mail("Naukri <x@naukri.com>", "Jobs", listOf("text/plain" to "jobs"), id = "b", date = 2_000_000),
            "c" to mail("Swiggy <n@swiggy.in>", "Receipt", listOf("text/plain" to "paid"), id = "c", date = 1_000_000),
        )
        val progress = ExportProgress(FakeConfig(), "mail")
        val sink = RecordingSink()
        val source = FakeMail(listOf(listOf("a", "b"), listOf("c", "gone")), bodies)
        assertEquals(ExportResult.Complete, GmailExporter(source, progress, sink, chunkSize = 2).run())

        assertEquals(listOf("a", "c"), sink.batches.flatten().map { it.externalId })
        assertEquals(4L, progress.number(ExportProgress.SEEN))
        assertEquals(2L, progress.number(ExportProgress.SKIPPED))
        assertEquals(42L, progress.number(ExportProgress.TOTAL))
        assertEquals("1000", progress.text(ExportProgress.CHECKPOINT))
        assertEquals(ExportState.COMPLETE, progress.text(ExportProgress.STATE))
    }

    @Test
    fun gmailExportResumesWithABeforeFilterAfterAnInterruption() = runBlocking {
        val progress = ExportProgress(FakeConfig(), "mail")
        progress.put(ExportProgress.CHECKPOINT, 1234)
        val source = FakeMail(listOf(emptyList()), emptyMap())
        GmailExporter(source, progress, RecordingSink()).run()
        assertTrue(source.queries.single().endsWith(" before:1234"))
        assertTrue(source.queries.single().contains("-in:spam"))
    }

    @Test
    fun missingGmailConsentFailsClearly() = runBlocking {
        val progress = ExportProgress(FakeConfig(), "mail")
        val source = object : MailSource {
            override suspend fun list(query: String, pageToken: String?): MailPage = throw GmailNeedsConsent()
            override suspend fun get(id: String): String? = null
        }
        val result = GmailExporter(source, progress, RecordingSink()).run()
        assertTrue(result is ExportResult.Failed)
        assertEquals(ExportState.FAILED, progress.text(ExportProgress.STATE))
    }

    @Test
    fun theStagingResponseIsParsed() {
        assertEquals(StagingOutcome.Stored(3, 1), BackendStagingSink.parseStored("""{"received":4,"stored":3,"duplicates":1}"""))
        assertNull(BackendStagingSink.parseStored("<html>"))
    }
}
