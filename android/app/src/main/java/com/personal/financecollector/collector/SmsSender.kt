package com.personal.financecollector.collector

import com.personal.financecollector.database.ParserStatus

object SmsSender {
    // Operators prepend a 2-letter route (VA-, BT-, VM-, AD-) and append a category (-S, -T); the middle is the entity.
    private val header = Regex("""^(?:[A-Z]{2}-)?([A-Z0-9]{3,10})(?:-[A-Z])?$""")

    /** Returns the sender entity code (e.g. "SBIUPI"), or null for phone numbers and unrecognised headers. */
    fun entityCode(address: String?): String? {
        val code = header.matchEntire(address?.trim()?.uppercase() ?: return null)?.groupValues?.get(1) ?: return null
        return code.takeIf { c -> c.any { it.isLetter() } }
    }
}

data class SmsRules(val paused: Boolean, val allowedSenders: Set<String>)

sealed interface SmsClassification {
    data class Accept(val entityCode: String, val payload: String, val parserStatus: ParserStatus) : SmsClassification
    data object Paused : SmsClassification
    data object SenderNotAllowed : SmsClassification
    data object Filtered : SmsClassification
}

object SmsClassifier {
    fun classify(sender: String?, body: String, rules: SmsRules): SmsClassification {
        if (rules.paused) return SmsClassification.Paused
        val entity = SmsSender.entityCode(sender)
        if (entity == null || entity !in rules.allowedSenders) return SmsClassification.SenderNotAllowed
        return when (val decision = NotificationFilter.evaluateText(body)) {
            is FilterDecision.Capture -> SmsClassification.Accept(entity, decision.payload, decision.parserStatus)
            FilterDecision.DropOtp, FilterDecision.DropUnrelated -> SmsClassification.Filtered
        }
    }
}
