package com.personal.financecollector.ui

/** One bank under every name its messages use: SBIUPI, CBSSBI, ATMSBI and the e-mail alerts are all SBI. */
object BankBrand {
    fun known(code: String): String? {
        val c = code.trim().uppercase()
        return when {
            c.startsWith("SBI") || c == "CBSSBI" || c == "ATMSBI" || c == "SBYONO" -> "SBI"
            c == "CSBBNK" || c == "CSB" -> "CSB Bank"
            c == "INDBNK" || c == "INDIAN BANK" -> "Indian Bank"
            c.startsWith("ICICI") -> "ICICI"
            else -> null
        }
    }
}

object AccountGrouping {

    /**
     * The bank an account belongs to. Messages from a payment app carry no bank, so they join the one bank
     * that has the same last digits; otherwise the app's own name is kept.
     */
    fun brandOf(list: List<LedgerTxn>): (LedgerTxn) -> String {
        val banksByLast4 = list.filter { it.accountLast4 != null }
            .groupBy({ it.accountLast4!! }, { BankBrand.known(it.bankCode) })
            .mapValues { (_, v) -> v.filterNotNull().toSet() }
        return { t -> BankBrand.known(t.bankCode) ?: banksByLast4[t.accountLast4]?.singleOrNull() ?: t.bankCode.ifBlank { "Unknown bank" } }
    }
}
