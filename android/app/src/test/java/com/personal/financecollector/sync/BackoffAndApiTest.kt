package com.personal.financecollector.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject
import kotlin.random.Random

class BackoffAndApiTest {

    @Test
    fun firstRetryIsBetween15And30Seconds() {
        val rnd = Random(1)
        repeat(200) {
            val d = Backoff.delayMillis(0, rnd)
            assertTrue("was $d", d in 15_000..30_000)
        }
    }

    @Test
    fun delaysDoubleAndAreCapped() {
        val rnd = Random(2)
        assertTrue(Backoff.delayMillis(3, rnd) in 120_000..240_000)
        repeat(50) { assertTrue(Backoff.delayMillis(1000, rnd) <= Backoff.CAP_MS) }
        assertTrue(Backoff.delayMillis(1000, rnd) >= Backoff.CAP_MS / 2)
    }

    @Test
    fun jitterActuallyVaries() {
        val rnd = Random(3)
        assertTrue((1..50).map { Backoff.delayMillis(5, rnd) }.toSet().size > 10)
    }

    @Test
    fun onlyHttpsAddressesAreAccepted() {
        assertEquals("https://app.example.com", BackendApi.normalizeBaseUrl(" https://app.example.com/ "))
        assertEquals("https://app.example.com/v1", BackendApi.normalizeBaseUrl("https://app.example.com/v1/"))
        assertNull(BackendApi.normalizeBaseUrl("http://app.example.com"))
        assertNull(BackendApi.normalizeBaseUrl("app.example.com"))
        assertNull(BackendApi.normalizeBaseUrl("not a url"))
    }

    @Test
    fun theBatchRequestMatchesTheBackendContract() {
        val json = JSONObject(BackendApi.buildBatchJson(listOf(testEvent("evt-1"))))
        val item = json.getJSONArray("events").getJSONObject(0)
        assertEquals("evt-1", item.getString("localEventId"))
        assertEquals("SMS", item.getString("sourceType"))
        assertEquals("SBIUPI", item.getString("sourceApplication"))
        assertEquals("VM-SBIUPI-S", item.getString("sourceSender"))
        assertEquals("src-evt-1", item.getString("sourceEventId"))
        assertEquals("2023-11-14T22:13:20Z", item.getString("eventTimestamp"))
        assertEquals("hash-evt-1", item.getString("payloadHash"))
        assertEquals(false, item.has("payload"))
        assertEquals("PARSED", item.getString("parserStatus"))
        assertEquals("sms-2", item.getString("parserVersion"))
        val t = item.getJSONObject("transaction")
        assertEquals("DEBIT", t.getString("direction"))
        assertEquals("10.00", t.getString("amount"))
        assertEquals("INR", t.getString("currency"))
        assertEquals("SBIUPI", t.getString("bankCode"))
        assertTrue(t.isNull("counterparty"))
    }

    @Test
    fun theMessageTextIsNeverInTheRequest() {
        val body = "Sent Rs.6.00 from A/c *0000 on 09-11-26 to Sample Merchant.RRN 000000000001.Avl Bal Rs.100.00.Not you?SMS BLOCK to 9289592895-Indian Bank"
        val json = BackendApi.buildBatchJson(listOf(testEvent("m").copy(sourceApplication = "INDBNK", rawPayload = body)))
        assertTrue(!json.contains("Not you"))
        assertTrue(!json.contains("SMS BLOCK"))
        assertTrue(json.contains("Sample Merchant"))
    }

    @Test
    fun aMissingSenderIsSentAsNull() {
        val event = testEvent("x").copy(sourceSender = null)
        val item = JSONObject(BackendApi.buildBatchJson(listOf(event))).getJSONArray("events").getJSONObject(0)
        assertTrue(item.isNull("sourceSender"))
    }

    @Test
    fun acknowledgementsAreParsed() {
        val body = """{"results":[{"localEventId":"a","status":"ACCEPTED","serverEventId":7,"reason":null},
            {"localEventId":"b","status":"REJECTED","serverEventId":null,"reason":"invalid fields: payload"}]}"""
        val results = BackendApi.parseBatchResults(body)!!
        assertEquals(listOf("ACCEPTED", "REJECTED"), results.map { it.status })
        assertNull(results[0].reason)
        assertEquals("invalid fields: payload", results[1].reason)
    }

    @Test
    fun garbageResponsesAreNotMistakenForAcknowledgements() {
        assertNull(BackendApi.parseBatchResults("<html>gateway error</html>"))
        assertNull(BackendApi.parseBatchResults("{}"))
    }

    @Test
    fun registrationResponseIsParsed() {
        val ok = BackendApi.parseRegistration("""{"deviceId":12,"deviceToken":"abc"}""")
        assertNotNull(ok)
        assertEquals(12L, ok!!.deviceId)
        assertEquals("abc", ok.token)
        assertNull(BackendApi.parseRegistration("nope"))
    }
}
