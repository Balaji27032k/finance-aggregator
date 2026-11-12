package com.personal.financecollector.sync

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AskJsonTest {

    @Test
    fun theRequestCarriesTheQuestionAndRecentTurnsOnly() {
        val body = org.json.JSONObject(AskJson.request("how much on tea", listOf("user" to "hi", "assistant" to "hello")))
        assertEquals("how much on tea", body.getString("question"))
        assertEquals(2, body.getJSONArray("history").length())
        assertEquals("assistant", body.getJSONArray("history").getJSONObject(1).getString("role"))
    }

    @Test
    fun aServerAnswerIsReadWithItsTotalsAndRelatedNames() {
        val reply = AskJson.parse(
            """{"answer":"You spent ₹300 on dosa.","aiUsed":true,"intent":"spend_on","periodLabel":"the last 12 months",
            "from":"2025-11-12","to":"2026-11-12","terms":["dosa"],"exact":{"total":300,"count":3},"related":{"total":120.5,"count":1},
            "breakdownTitle":"Related","breakdown":[{"name":"Dossa","total":120.5,"count":1}],
            "items":[{"name":"Masala dosa","title":"Hotel Saravana","date":"2026-11-03","amount":150,"related":false}],
            "suggestions":["And last month?"],"sentToAi":["{}"],"note":null}""",
        )!!
        assertTrue(reply.aiUsed)
        assertEquals(BigDecimal("300"), reply.exact!!.total)
        assertEquals(3, reply.exact!!.count)
        assertEquals(0, BigDecimal("120.5").compareTo(reply.related!!.total))
        assertEquals("Dossa", reply.breakdown.single().name)
        assertEquals("Masala dosa", reply.items.single().name)
        assertFalse(reply.items.single().related)
        assertEquals(listOf("And last month?"), reply.suggestions)
        assertNull(reply.note)
    }

    @Test
    fun aSummaryHasNoExactTotalsAndABrokenBodyIsRejected() {
        val reply = AskJson.parse("""{"answer":"Hi","aiUsed":false,"intent":"summary","exact":null,"related":null,"breakdown":[],"items":[]}""")!!
        assertNull(reply.exact)
        assertTrue(reply.suggestions.isEmpty())
        assertNull(AskJson.parse("not json"))
        assertNull(AskJson.parse("""{"intent":"x"}"""))
    }
}
