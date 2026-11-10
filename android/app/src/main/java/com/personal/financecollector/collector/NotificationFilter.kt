package com.personal.financecollector.collector

import com.personal.financecollector.database.ParserStatus

data class NotificationContent(
    val title: String?,
    val text: String?,
    val bigText: String?,
    val lines: List<String>,
    val messages: List<String>,
) {
    fun isEmpty(): Boolean =
        title.isNullOrBlank() && text.isNullOrBlank() && bigText.isNullOrBlank() && lines.isEmpty() && messages.isEmpty()
}

sealed interface FilterDecision {
    data class Capture(val payload: String, val parserStatus: ParserStatus) : FilterDecision
    data object DropOtp : FilterDecision
    data object DropUnrelated : FilterDecision
}

object NotificationFilter {
    private const val MAX_PAYLOAD_CHARS = 2000

    private val otpTerms = Regex(
        """\b(otp|one[- ]?time[- ]?(password|pin|code)|verification code|security code|passcode|cvv|m-?pin|login code)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val otpValue = Regex("""(otp[^0-9]{0,20})(\d{4,8})""", RegexOption.IGNORE_CASE)
    // Second branch covers alerts like "debited by 413.00" that carry no currency marker.
    private val amount = Regex(
        """(?:₹|\brs\.?|\binr)\s*\d[\d,]*(?:\.\d{1,2})?|\b(?:debited|credited|sent|received|paid|spent|withdrawn|deposited)\s+(?:by|with|for|of)\s+\d[\d,]*(?:\.\d{1,2})?""",
        RegexOption.IGNORE_CASE,
    )
    private val strongKeywords = Regex(
        """\b(credited|debited|received|paid|sent|spent|withdrawn|deposited|refunded|refund|reversed|reversal|transferred)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val weakKeywords = Regex("""\b(upi|txn|transaction|payment|a/c|account|balance)\b""", RegexOption.IGNORE_CASE)

    fun evaluate(content: NotificationContent): FilterDecision = evaluateText(buildPayload(content))

    /** Shared by notifications and SMS: decides whether free text looks like a transaction alert. */
    fun evaluateText(raw: String): FilterDecision {
        val payload = raw.trim().take(MAX_PAYLOAD_CHARS)
        if (payload.isBlank()) return FilterDecision.DropUnrelated

        val hasAmount = amount.containsMatchIn(payload)
        val hasStrong = strongKeywords.containsMatchIn(payload)
        val hasWeak = weakKeywords.containsMatchIn(payload)
        val hasOtpTerm = otpTerms.containsMatchIn(payload)

        // An alert that merely warns "never share your OTP" is kept, but the code itself is masked.
        if (hasOtpTerm && !(hasAmount && hasStrong)) return FilterDecision.DropOtp

        val safePayload = if (hasOtpTerm) otpValue.replace(payload) { "${it.groupValues[1]}****" } else payload

        return when {
            hasAmount && (hasStrong || hasWeak) -> FilterDecision.Capture(safePayload, ParserStatus.UNPARSED)
            !hasAmount && hasStrong -> FilterDecision.Capture(safePayload, ParserStatus.NEEDS_REVIEW)
            else -> FilterDecision.DropUnrelated
        }
    }

    private fun buildPayload(c: NotificationContent): String {
        val body = if (!c.bigText.isNullOrBlank()) c.bigText else c.text
        return (listOf(c.title, body) + c.lines + c.messages)
            .filter { !it.isNullOrBlank() }
            .map { it!!.trim() }
            .distinct()
            .joinToString("\n")
            .take(MAX_PAYLOAD_CHARS)
    }
}
