package com.personal.financecollector.ui

import com.personal.financecollector.database.FinanceDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Builds the same corrected transaction list the app shows, for code that runs outside the screens (the home-screen widget). */
object LedgerLoader {
    const val LEDGER_EVENTS = 20000

    suspend fun load(db: FinanceDatabase): List<LedgerTxn> = withContext(Dispatchers.Default) {
        val base = LedgerBuilder.build(db.eventDao().ledgerEvents(LEDGER_EVENTS))
        LedgerOverlay.apply(
            base,
            db.labelDao().labels().associateBy { it.eventId },
            db.labelDao().rules().associate { it.payeeKey to it.category },
            ownSettings(db.labelDao().ownAccounts()),
            db.planDao().splits().groupBy({ it.eventId }, { SplitPart(it.category, java.math.BigDecimal(it.amount), it.name) }),
        )
    }
}
