package com.personal.financecollector.collector

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.personal.financecollector.FinanceApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent)?.takeIf { it.isNotEmpty() } ?: return

        // Parts of one multipart SMS share a sender.
        val sender = parts.first().originatingAddress
        val body = parts.joinToString("") { it.messageBody.orEmpty() }
        val timestamp = parts.first().timestampMillis

        // Only a short local DB write happens here; no network work in a receiver.
        val pending = goAsync()
        val db = (context.applicationContext as FinanceApp).container.db
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                SmsIngestor(db).ingest(sender, body, timestamp)
            } finally {
                pending.finish()
            }
        }
    }
}
