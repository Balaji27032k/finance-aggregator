package com.personal.financecollector.collector

import com.personal.financecollector.ConfigKeys
import com.personal.financecollector.database.RawFinancialEventEntity
import com.personal.financecollector.database.SourceType
import com.personal.financecollector.export.GmailNeedsConsent
import com.personal.financecollector.export.MailPage
import com.personal.financecollector.export.MailSource
import com.personal.financecollector.sync.FakeConfig
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BankMailIngestorTest {

    private fun b64(s: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray())

    private fun mail(id: String, from: String, subject: String, body: String, date: Long): String = JSONObject()
        .put("id", id).put("internalDate", date.toString())
        .put(
            "payload",
            JSONObject().put("mimeType", "text/plain")
                .put("headers", JSONArray().put(JSONObject().put("name", "From").put("value", from)).put(JSONObject().put("name", "Subject").put("value", subject)))
                .put("body", JSONObject().put("data", b64(body))),
        ).toString()

    private class FakeSource(private val mails: Map<String, String>) : MailSource {
        val queries = mutableListOf<String>()
        override suspend fun list(query: String, pageToken: String?): MailPage {
            queries += query
            return MailPage(mails.keys.toList(), null, mails.size)
        }

        override suspend fun get(id: String): String? = mails[id]
    }

    private class MemoryStore : BankMailStore {
        val events = mutableListOf<RawFinancialEventEntity>()
        override suspend fun add(event: RawFinancialEventEntity): Boolean {
            if (events.any { it.sourceEventId == event.sourceEventId }) return false
            events += event
            return true
        }
    }

    private val alert =
        "Dear Customer, Please note that an amount of INR 10.00 has been debited from your account XXXXXXXXX0000 towards " +
            "UPI/DR/631593452510/SAMPLE SHOP/FDRL/sample@upi/lunch on 11-NOV-2026. Your updated account balance is INR 545.20. " +
            "If this transaction was not done by you, SMS BLOCK. Warm regards, CSB Bank"

    @Test
    fun aBankAlertEmailIsStoredAsAnEventThatParsesIntoATransaction() = runBlocking {
        val source = FakeSource(
            mapOf(
                "m1" to mail("m1", "CSB Bank <noreply@csb.bank.in>", "Account Debit Alert", alert, 1_794_000_000_000),
                "m2" to mail("m2", "CSB Bank <noreply@csb.bank.in>", "CSB Mobile+ App Update", "Update your app", 1_794_000_100_000),
            ),
        )
        val store = MemoryStore()
        val config = FakeConfig()
        val result = BankMailIngestor(source, store, config, clock = { 5L }).run()

        assertEquals(BankMailResult.Done(1), result)
        val event = store.events.single()
        assertEquals(SourceType.GMAIL, event.sourceType)
        assertEquals("CSB", event.sourceApplication)
        assertEquals("noreply@csb.bank.in", event.sourceSender)
        assertEquals("m1", event.sourceEventId)
        val t = TransactionParser.parse(event)!!
        assertEquals("SAMPLE SHOP", t.counterparty)
        assertEquals("lunch", t.note)
        assertEquals("1", config.values[ConfigKeys.GMAIL_BANK_CAPTURED])
        assertEquals("OK", config.values[ConfigKeys.GMAIL_BANK_STATUS])
        assertTrue(config.values[ConfigKeys.GMAIL_BANK_AFTER]!!.toLong() > 0)
    }

    @Test
    fun aSecondCheckDoesNotStoreTheSameEmailAgainAndUsesTheCheckpoint() = runBlocking {
        val source = FakeSource(mapOf("m1" to mail("m1", "noreply@csb.bank.in", "Account Debit Alert", alert, 1_794_000_000_000)))
        val store = MemoryStore()
        val config = FakeConfig()
        BankMailIngestor(source, store, config).run()
        val second = BankMailIngestor(source, store, config).run()

        assertEquals(BankMailResult.Done(0), second)
        assertEquals(1, store.events.size)
        assertTrue(source.queries.last().contains(" after:"))
        assertTrue(!source.queries.first().contains(" after:"))
        assertEquals("1", config.values[ConfigKeys.GMAIL_BANK_CAPTURED])
    }

    @Test
    fun theQueryOnlyAsksForBankAlertSenders() {
        val q = BankMailQuery.build(0)
        assertTrue(q.contains("@csb.bank.in") && q.contains("alerts.sbi.bank.in") && q.contains("Debit Alert"))
    }

    @Test
    fun missingConsentIsReportedSoTheAppCanAskForSignIn() = runBlocking {
        val source = object : MailSource {
            override suspend fun list(query: String, pageToken: String?): MailPage = throw GmailNeedsConsent()
            override suspend fun get(id: String): String? = null
        }
        val config = FakeConfig()
        assertEquals(BankMailResult.NeedsConnect, BankMailIngestor(source, MemoryStore(), config).run())
        assertEquals(BankMailIngestor.NEEDS_CONNECT, config.values[ConfigKeys.GMAIL_BANK_STATUS])
    }
}
