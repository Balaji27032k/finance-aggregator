package com.personal.financecollector.sync

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.math.BigDecimal

data class AskTotals(val total: BigDecimal, val count: Int)

data class AskGroup(val name: String, val total: BigDecimal, val count: Int)

data class AskItem(val name: String, val title: String, val date: String, val amount: BigDecimal, val related: Boolean)

/** The server's answer to a plain-words question; every number in it was added up on the server. */
data class AskReply(
    val answer: String,
    val aiUsed: Boolean,
    val intent: String,
    val periodLabel: String,
    val terms: List<String>,
    val exact: AskTotals?,
    val related: AskTotals?,
    val breakdownTitle: String?,
    val breakdown: List<AskGroup>,
    val items: List<AskItem>,
    val suggestions: List<String>,
    val sentToAi: List<String>,
    val note: String?,
)

sealed interface AskOutcome {
    data class Done(val reply: AskReply) : AskOutcome
    data object Unauthorized : AskOutcome
    data class Failed(val reason: String) : AskOutcome
}

object AskJson {

    fun request(question: String, history: List<Pair<String, String>>): String {
        val turns = JSONArray()
        history.forEach { (role, text) -> turns.put(JSONObject().put("role", role).put("text", text.take(600))) }
        return JSONObject().put("question", question.take(300)).put("history", turns).toString()
    }

    private fun JSONObject.strings(key: String): List<String> =
        optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()

    private fun JSONObject.money(key: String) = BigDecimal(optString(key, "0").ifBlank { "0" })

    private fun totals(o: JSONObject?) = o?.let { AskTotals(it.money("total"), it.optInt("count")) }

    fun parse(body: String): AskReply? = try {
        val o = JSONObject(body)
        AskReply(
            answer = o.getString("answer"),
            aiUsed = o.optBoolean("aiUsed"),
            intent = o.optString("intent"),
            periodLabel = o.optString("periodLabel"),
            terms = o.strings("terms"),
            exact = totals(o.optJSONObject("exact")),
            related = totals(o.optJSONObject("related")),
            breakdownTitle = o.optString("breakdownTitle").takeIf { it.isNotBlank() && !o.isNull("breakdownTitle") },
            breakdown = o.optJSONArray("breakdown")?.let { a ->
                (0 until a.length()).map { val g = a.getJSONObject(it); AskGroup(g.getString("name"), g.money("total"), g.optInt("count")) }
            } ?: emptyList(),
            items = o.optJSONArray("items")?.let { a ->
                (0 until a.length()).map {
                    val i = a.getJSONObject(it)
                    AskItem(i.optString("name"), i.optString("title"), i.optString("date"), i.money("amount"), i.optBoolean("related"))
                }
            } ?: emptyList(),
            suggestions = o.strings("suggestions"),
            sentToAi = o.strings("sentToAi"),
            note = o.optString("note").takeIf { it.isNotBlank() && !o.isNull("note") },
        )
    } catch (e: JSONException) {
        null
    } catch (e: NumberFormatException) {
        null
    }
}
