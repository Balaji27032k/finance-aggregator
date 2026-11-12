package com.personal.financecollector.sync

import com.personal.financecollector.ConfigKeys
import com.personal.financecollector.database.FinanceDatabase
import com.personal.financecollector.database.LabelSyncEntity
import com.personal.financecollector.ui.LedgerTxn
import com.personal.financecollector.ui.SplitPart

/** Sends the user's categories and notes to the server so the dashboard and Admin show the same thing as the phone. */
class LabelSync(
    private val db: FinanceDatabase,
    private val api: BackendApi,
    private val credentials: TokenSource,
) {
    /** Everything that differs from what the server already has: [push] to send and [clear] to take back. */
    data class Plan(val push: List<LabelPush>, val clear: List<String>)

    suspend fun run(ledger: List<LedgerTxn>) {
        val config = db.configDao()
        val baseUrl = config.get(ConfigKeys.BACKEND_URL)
        val token = credentials.token()
        if (config.get(ConfigKeys.PAIRED) != "true" || baseUrl.isNullOrBlank() || token == null) return

        val sent = db.planDao().syncState().associate { it.eventId to it.signature }
        val plan = plan(ledger, sent)
        val items = plan.push + plan.clear.map { LabelPush(it, null, null) }
        for (chunk in items.chunked(CHUNK)) {
            when (val outcome = api.pushLabels(baseUrl, token, chunk)) {
                is LabelOutcome.Done -> {
                    val done = chunk.filter { it.localEventId !in outcome.missing }
                    db.planDao().upsertSync(done.filter { it.category != null || it.label != null || it.splits.isNotEmpty() }.map { LabelSyncEntity(it.localEventId, signature(it.category, it.label, it.splits)) })
                    db.planDao().deleteSync(done.filter { it.category == null && it.label == null && it.splits.isEmpty() }.map { it.localEventId })
                }
                else -> return
            }
        }
    }

    companion object {
        private const val CHUNK = 100

        fun signature(category: String?, label: String?, splits: List<SplitPart> = emptyList()) =
            "${category.orEmpty()}\u0001${label.orEmpty()}\u0001${splits.joinToString(";") { "${it.category}=${it.amount.toPlainString()}" }}"

        /** Events the user has changed (or that are own transfers) and anything sent earlier that is no longer changed. */
        fun plan(ledger: List<LedgerTxn>, sent: Map<String, String>): Plan {
            val desired = ledger.filter { it.edited || it.internalTransfer }
                .flatMap { t -> t.eventIds.map { id -> id to Triple(t.category, t.label, t.splits) } }.toMap()
            val push = desired.filter { (id, v) -> sent[id] != signature(v.first, v.second, v.third) }
                .map { (id, v) -> LabelPush(id, v.first, v.second, v.third) }
            val clear = sent.keys.filter { it !in desired }
            return Plan(push, clear)
        }
    }
}
