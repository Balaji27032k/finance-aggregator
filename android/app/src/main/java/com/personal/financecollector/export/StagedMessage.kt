package com.personal.financecollector.export

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** One SMS or e-mail uploaded as-is (OTP digits masked) to the temporary analysis table. */
data class StagedMessage(
    val channel: String,
    val externalId: String,
    val sender: String?,
    val subject: String?,
    val receivedAtMs: Long,
    val body: String,
    val bodyTruncated: Boolean,
    val labels: String?,
)

object StagingJson {
    const val MAX_BODY = 8000

    fun build(messages: List<StagedMessage>): String {
        val array = JSONArray()
        messages.forEach { m ->
            array.put(
                JSONObject()
                    .put("channel", m.channel)
                    .put("externalId", m.externalId.take(128))
                    .put("sender", m.sender?.take(255) ?: JSONObject.NULL)
                    .put("subject", m.subject?.take(500) ?: JSONObject.NULL)
                    .put("receivedAt", Instant.ofEpochMilli(m.receivedAtMs).toString())
                    .put("body", m.body.ifBlank { "(empty)" }.take(MAX_BODY))
                    .put("bodyTruncated", m.bodyTruncated || m.body.length > MAX_BODY)
                    .put("labels", m.labels?.take(255) ?: JSONObject.NULL),
            )
        }
        return JSONObject().put("messages", array).toString()
    }
}

object ExportMasking {
    private val codeAfterWord = Regex("""(?i)\b(otp|one[- ]?time|verification|passcode|pin|cvv|code)\b([^0-9\n]{0,40})([0-9]{4,8})\b""")
    private val codeBeforeWord = Regex("""(?i)\b([0-9]{4,8})\b(?=[^0-9]{0,30}\b(?:otp|verification code|one[- ]?time)\b)""")

    /** Replaces one-time codes with asterisks; everything else in the message is left untouched. */
    fun maskOtp(text: String): String =
        text.replace(codeAfterWord) { "${it.groupValues[1]}${it.groupValues[2]}****" }.replace(codeBeforeWord, "****")

    /** A sender that is a phone number (a personal chat), not an alphanumeric header or short code. */
    fun isPersonalNumber(sender: String?): Boolean {
        val s = sender?.trim().orEmpty()
        return s.isNotEmpty() && s.all { it.isDigit() || it in "+ -()" } && s.count { it.isDigit() } >= 7
    }
}
