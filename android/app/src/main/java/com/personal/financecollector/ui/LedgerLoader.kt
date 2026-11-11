package com.personal.financecollector.ui

import com.personal.financecollector.database.FinanceDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Builds the same corrected transaction list the app shows, for code that runs outside the screens (the home-screen widget). */
object LedgerLoader {
    suspend fun load(db: FinanceDatabase): List<LedgerTxn> = withContext(Dispatchers.Default) {
        val base = LedgerBuilder.build(db.eventDao().recentList(6000))
        LedgerOverlay.apply(
            base,
            db.labelDao().labels().associateBy { it.eventId },
            db.labelDao().rules().associate { it.payeeKey to it.category },
            ownSettings(db.labelDao().ownAccounts()),
        )
    }
}
