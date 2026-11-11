package com.personal.financecollector.export

import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.Charset
import java.util.Base64

/** The Gmail search used for the analysis export: finance-related mail, without job portals, YouTube and similar noise. */
object GmailQuery {
    private val keywords = listOf(
        "debited", "credited", "debit", "credit", "transaction", "payment", "paid", "UPI", "NEFT", "IMPS", "RTGS", "\"a/c\"",
        "account", "statement", "invoice", "receipt", "refund", "EMI", "loan", "salary", "payslip", "bill", "\"credit card\"",
        "\"net banking\"", "cheque", "withdrawal", "deposit", "balance", "order", "subscription", "renewal", "premium",
        "\"mutual fund\"", "SIP", "dividend", "TDS", "recharge", "booking", "ticket",
    )
    private val financeSenders = listOf(
        "bank", "hdfc", "icici", "sbi", "axis", "kotak", "indusind", "yesbank", "csb", "\"indian bank\"", "paytm", "phonepe",
        "gpay", "razorpay", "cred", "amazon", "flipkart", "swiggy", "zomato", "irctc", "uber", "ola", "bigbasket", "myntra",
        "airtel", "jio", "lic", "insurance", "zerodha", "groww", "upstox", "npci", "cleartax",
    )
    private val excludedSenders = listOf(
        "naukri", "linkedin", "indeed", "monster", "foundit", "glassdoor", "instahyre", "wellfound", "cutshort", "timesjobs",
        "iimjobs", "hirist", "apna.co", "shine.com", "youtube",
    )

    fun build(): String =
        "(${keywords.joinToString(" OR ")} OR from:(${financeSenders.joinToString(" OR ")})) " +
            "-from:(${excludedSenders.joinToString(" OR ")}) " +
            "-in:spam -in:trash -in:drafts -in:sent -category:promotions -category:social -category:forums"

    private val blockedTokens = listOf(
        "naukri", "linkedin", "indeed", "monster", "foundit", "glassdoor", "instahyre", "wellfound", "cutshort", "timesjobs",
        "iimjobs", "hirist", "apna.co", "shine.com", "youtube",
    )
    private val googlePaymentSenders = listOf("googlepay", "google pay", "pay.google", "payments-noreply", "payments.google", "googleplay", "google play")

    /** Job portals, YouTube and Google's own notifications are dropped; Google Pay and Google Play receipts are kept. */
    fun isBlockedSender(from: String): Boolean {
        val f = from.lowercase()
        if (blockedTokens.any { it in f }) return true
        return "google" in f && googlePaymentSenders.none { it in f }
    }
}

/** [message] is null when the sender is filtered out; [internalDateMs] is always set so progress can be checkpointed. */
data class ParsedMail(val message: StagedMessage?, val internalDateMs: Long)

data class MailText(val id: String, val fromAddress: String, val subject: String, val body: String, val internalDateMs: Long)

/** Turns a Gmail API message (format=full) into plain text. */
object GmailMessageParser {

    /** Sender, subject and readable body of one message, without any sender filtering or truncation. */
    fun extractText(json: String): MailText? {
        val message = JSONObject(json)
        val payload = message.optJSONObject("payload") ?: return null
        val from = header(payload, "From")?.let(::decodeWords).orEmpty()
        val plain = StringBuilder()
        val html = StringBuilder()
        collect(payload, plain, html)
        return MailText(
            id = message.getString("id"),
            fromAddress = Regex("<([^>]+)>").find(from)?.groupValues?.get(1) ?: from.trim(),
            subject = header(payload, "Subject")?.let(::decodeWords).orEmpty(),
            body = collapse(if (plain.isNotBlank()) plain.toString() else htmlToText(html.toString())),
            internalDateMs = message.optString("internalDate").toLongOrNull() ?: 0L,
        )
    }
    fun parse(json: String): ParsedMail {
        val message = JSONObject(json)
        val internalDate = message.optString("internalDate").toLongOrNull() ?: 0L
        val payload = message.optJSONObject("payload") ?: return ParsedMail(null, internalDate)
        val from = header(payload, "From")?.let(::decodeWords)
        if (from != null && GmailQuery.isBlockedSender(from)) return ParsedMail(null, internalDate)
        val subject = header(payload, "Subject")?.let(::decodeWords)

        val plain = StringBuilder()
        val html = StringBuilder()
        collect(payload, plain, html)
        val text = collapse(if (plain.isNotBlank()) plain.toString() else htmlToText(html.toString()))

        val labels = message.optJSONArray("labelIds")?.let { a -> (0 until a.length()).joinToString(",") { a.getString(it) } }
        return ParsedMail(
            StagedMessage(
                channel = "EMAIL",
                externalId = message.getString("id"),
                sender = from?.take(255),
                subject = subject?.take(500),
                receivedAtMs = internalDate,
                body = text.take(StagingJson.MAX_BODY),
                bodyTruncated = text.length > StagingJson.MAX_BODY,
                labels = labels,
            ),
            internalDate,
        )
    }

    private fun header(part: JSONObject, name: String): String? {
        val headers: JSONArray = part.optJSONArray("headers") ?: return null
        for (i in 0 until headers.length()) {
            val h = headers.getJSONObject(i)
            if (h.optString("name").equals(name, ignoreCase = true)) return h.optString("value")
        }
        return null
    }

    private fun collect(part: JSONObject, plain: StringBuilder, html: StringBuilder) {
        val data = part.optJSONObject("body")?.optString("data").orEmpty()
        if (data.isNotEmpty()) {
            val text = String(Base64.getUrlDecoder().decode(data), Charsets.UTF_8)
            when (part.optString("mimeType").lowercase()) {
                "text/plain" -> plain.append(text).append('\n')
                "text/html" -> html.append(text).append('\n')
            }
        }
        val children = part.optJSONArray("parts") ?: return
        for (i in 0 until children.length()) collect(children.getJSONObject(i), plain, html)
    }

    private val encodedWord = Regex("""=\?([^?]+)\?([bBqQ])\?([^?]*)\?=""")

    /** Decodes RFC 2047 "=?UTF-8?B?...?=" header words. */
    fun decodeWords(value: String): String =
        encodedWord.replace(value.replace(Regex("""\?=\s+=\?"""), "?==?")) { m ->
            runCatching {
                val charset = Charset.forName(m.groupValues[1])
                val bytes = if (m.groupValues[2].equals("B", true)) {
                    Base64.getDecoder().decode(m.groupValues[3])
                } else {
                    quotedPrintable(m.groupValues[3])
                }
                String(bytes, charset)
            }.getOrDefault(m.value)
        }

    private fun quotedPrintable(s: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '=' && i + 2 < s.length) {
                out.write(s.substring(i + 1, i + 3).toInt(16))
                i += 3
            } else {
                out.write(if (c == '_') ' '.code else c.code)
                i++
            }
        }
        return out.toByteArray()
    }

    fun htmlToText(html: String): String {
        var t = html.replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
        t = t.replace(Regex("(?s)<!--.*?-->"), " ")
        t = t.replace(Regex("(?i)<br\\s*/?>|</(p|div|tr|li|h[1-6])>"), " ")
        t = t.replace(Regex("(?s)<[^>]*>"), " ")
        t = t.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
        return t.replace(Regex("&#(\\d{1,5});")) { it.groupValues[1].toInt().toChar().toString() }
    }

    private fun collapse(text: String): String = text.replace(Regex("[\\s\\u00a0]+"), " ").trim()
}
