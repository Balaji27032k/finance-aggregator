package com.personal.financecollector.profile

import org.json.JSONException
import org.json.JSONObject
import java.math.BigDecimal

data class RateItem(val key: String, val label: String, val percent: BigDecimal, val asOf: String?, val note: String?)

data class RateSource(val title: String, val url: String)

/** Interest rates the server found on the web. [status] is OK, PARTIAL, STALE or UNAVAILABLE. */
data class MarketRates(
    val status: String,
    val message: String?,
    val fetchedAt: String?,
    val items: List<RateItem>,
    val sources: List<RateSource>,
) {
    /** The rates this person's plans use, with the ones that were found replacing what was there; others are left alone. */
    fun toAssumptions(current: ProfileRates): ProfileRates {
        fun pick(vararg keys: String): Double? = keys.firstNotNullOfOrNull { k -> items.firstOrNull { it.key == k }?.percent?.toDouble() }
        return current.copy(
            inflation = pick("inflation") ?: current.inflation,
            equity = pick("equity") ?: current.equity,
            fd = pick("fd5y", "fd1y") ?: current.fd,
            ppf = pick("ppf") ?: current.ppf,
            epf = pick("epf") ?: current.epf,
            savings = pick("savings") ?: current.savings,
        )
    }
}

object RatesJson {
    fun parse(body: String): MarketRates? = try {
        val o = JSONObject(body)
        val items = o.optJSONArray("items")
        val sources = o.optJSONArray("sources")
        MarketRates(
            status = o.getString("status"),
            message = o.optString("message").takeIf { it.isNotBlank() && !o.isNull("message") },
            fetchedAt = o.optString("fetchedAt").takeIf { it.isNotBlank() && !o.isNull("fetchedAt") },
            items = if (items == null) emptyList() else (0 until items.length()).map {
                val i = items.getJSONObject(it)
                RateItem(
                    i.getString("key"), i.optString("label"), BigDecimal(i.optString("ratePercent", "0")),
                    i.optString("asOf").takeIf { s -> s.isNotBlank() }, i.optString("note").takeIf { s -> s.isNotBlank() },
                )
            },
            sources = if (sources == null) emptyList() else (0 until sources.length()).map {
                val s = sources.getJSONObject(it)
                RateSource(s.optString("title"), s.optString("url"))
            },
        )
    } catch (e: JSONException) {
        null
    } catch (e: NumberFormatException) {
        null
    }
}
