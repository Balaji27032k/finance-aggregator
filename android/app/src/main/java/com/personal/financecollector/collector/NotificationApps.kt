package com.personal.financecollector.collector

/** Short, stable names for notification apps; the backend stores this as the bank or app code. */
object NotificationApps {
    private val known = mapOf(
        "com.google.android.apps.nbu.paisa.user" to "GPAY",
        "com.phonepe.app" to "PHONEPE",
        "net.one97.paytm" to "PAYTM",
        "in.org.npci.upiapp" to "BHIM",
        "com.dreamplug.androidapp" to "CRED",
        "in.indwealth" to "INDMONEY",
    )

    fun shortName(packageName: String): String = known[packageName] ?: packageName.substringAfterLast('.').uppercase().take(32)
}
