package com.personal.financecollector.profile

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

data class ProfileGoal(val name: String, val amount: Long, val year: Int?, val priority: String?)

data class ProfileHolding(val type: String, val amount: Long, val monthly: Long?)

data class ProfileLoan(val type: String, val outstanding: Long, val emi: Long?, val rate: Double?)

/** Annual percentages used in plans; empty means the app's default for that one. */
data class ProfileRates(
    val inflation: Double? = null,
    val equity: Double? = null,
    val debt: Double? = null,
    val fd: Double? = null,
    val ppf: Double? = null,
    val epf: Double? = null,
    val savings: Double? = null,
)

/** What the person tells the app about themselves so plans and advice fit them. Every field is optional. */
data class MoneyProfile(
    val name: String? = null,
    val age: Int? = null,
    val occupation: String? = null,
    val monthlyIncome: Long? = null,
    val incomeStability: String? = null,
    val dependents: Int? = null,
    val riskComfort: String? = null,
    val experience: String? = null,
    val taxRegime: String? = null,
    val emergencyFund: Long? = null,
    val healthCover: Long? = null,
    val termCover: Long? = null,
    val goals: List<ProfileGoal> = emptyList(),
    val investments: List<ProfileHolding> = emptyList(),
    val loans: List<ProfileLoan> = emptyList(),
    val rates: ProfileRates = ProfileRates(),
    val notes: String? = null,
) {
    /** How much of what the coach needs has been filled in, in percent. */
    fun completeness(): Int {
        val answered = listOf(
            age != null, occupation != null, monthlyIncome != null, incomeStability != null, dependents != null, riskComfort != null,
            experience != null, taxRegime != null, emergencyFund != null, healthCover != null || termCover != null, goals.isNotEmpty(),
        )
        return answered.count { it } * 100 / answered.size
    }
}

object ProfileJson {

    private fun JSONObject.str(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
    private fun JSONObject.long(key: String): Long? = if (isNull(key) || !has(key)) null else optLong(key)
    private fun JSONObject.int(key: String): Int? = if (isNull(key) || !has(key)) null else optInt(key)
    private fun JSONObject.double(key: String): Double? = if (isNull(key) || !has(key)) null else optDouble(key).takeIf { !it.isNaN() }

    private fun <T> JSONArray?.list(block: (JSONObject) -> T?): List<T> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { block(getJSONObject(it)) }

    fun parse(body: String): MoneyProfile? = try {
        val o = JSONObject(body)
        val a = o.optJSONObject("assumptions")
        MoneyProfile(
            name = o.str("name"), age = o.int("age"), occupation = o.str("occupation"), monthlyIncome = o.long("monthlyIncome"),
            incomeStability = o.str("incomeStability"), dependents = o.int("dependents"), riskComfort = o.str("riskComfort"),
            experience = o.str("experience"), taxRegime = o.str("taxRegime"), emergencyFund = o.long("emergencyFund"),
            healthCover = o.long("healthCover"), termCover = o.long("termCover"),
            goals = o.optJSONArray("goals").list { g ->
                g.str("name")?.let { ProfileGoal(it, g.optLong("amount"), g.int("year"), g.str("priority")) }
            },
            investments = o.optJSONArray("investments").list { h ->
                h.str("type")?.let { ProfileHolding(it, h.optLong("amount"), h.long("monthly")) }
            },
            loans = o.optJSONArray("loans").list { l ->
                l.str("type")?.let { ProfileLoan(it, l.optLong("outstanding"), l.long("emi"), l.double("rate")) }
            },
            rates = a?.let {
                ProfileRates(it.double("inflation"), it.double("equity"), it.double("debt"), it.double("fd"), it.double("ppf"), it.double("epf"), it.double("savings"))
            } ?: ProfileRates(),
            notes = o.str("notes"),
        )
    } catch (e: JSONException) {
        null
    }

    private fun JSONObject.putOpt2(key: String, value: Any?): JSONObject = put(key, value ?: JSONObject.NULL)

    fun toJson(p: MoneyProfile): String {
        val o = JSONObject()
            .putOpt2("name", p.name?.trim()?.take(40)?.ifBlank { null }).putOpt2("age", p.age).putOpt2("occupation", p.occupation)
            .putOpt2("monthlyIncome", p.monthlyIncome).putOpt2("incomeStability", p.incomeStability).putOpt2("dependents", p.dependents)
            .putOpt2("riskComfort", p.riskComfort).putOpt2("experience", p.experience).putOpt2("taxRegime", p.taxRegime)
            .putOpt2("emergencyFund", p.emergencyFund).putOpt2("healthCover", p.healthCover).putOpt2("termCover", p.termCover)
            .putOpt2("notes", p.notes?.trim()?.take(500)?.ifBlank { null })
        o.put(
            "goals",
            JSONArray(p.goals.map { JSONObject().put("name", it.name.take(40)).put("amount", it.amount).putOpt2("year", it.year).putOpt2("priority", it.priority) }),
        )
        o.put(
            "investments",
            JSONArray(p.investments.map { JSONObject().put("type", it.type.take(30)).put("amount", it.amount).putOpt2("monthly", it.monthly) }),
        )
        o.put(
            "loans",
            JSONArray(p.loans.map { JSONObject().put("type", it.type.take(30)).put("outstanding", it.outstanding).putOpt2("emi", it.emi).putOpt2("rate", it.rate) }),
        )
        val r = p.rates
        o.put(
            "assumptions",
            JSONObject().putOpt2("inflation", r.inflation).putOpt2("equity", r.equity).putOpt2("debt", r.debt).putOpt2("fd", r.fd)
                .putOpt2("ppf", r.ppf).putOpt2("epf", r.epf).putOpt2("savings", r.savings),
        )
        return o.toString()
    }
}
