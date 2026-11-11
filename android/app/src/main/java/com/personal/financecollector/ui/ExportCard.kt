package com.personal.financecollector.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.personal.financecollector.export.ExportState

data class ExportProgressUi(
    val state: String?,
    val seen: Long,
    val uploaded: Long,
    val skipped: Long,
    val total: Long,
    val error: String?,
)

data class ExportUi(
    val paired: Boolean,
    val sms: ExportProgressUi,
    val mail: ExportProgressUi,
    val gmailConnected: Boolean,
    val message: String?,
)

private fun describe(p: ExportProgressUi, totalLabel: Boolean): String {
    val status = when (p.state) {
        ExportState.RUNNING -> "running"
        ExportState.COMPLETE -> "complete"
        ExportState.FAILED -> "failed"
        else -> "not started"
    }
    val total = if (totalLabel && p.total > 0) " of about ${p.total}" else ""
    val error = p.error?.takeIf { it.isNotBlank() }?.let { " Last error: $it" }.orEmpty()
    return "Status: $status. Read ${p.seen}$total, uploaded ${p.uploaded}, skipped ${p.skipped}.$error"
}

@Composable
fun ExportCard(export: ExportUi, actions: HealthActions) {
    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Analysis export (temporary)", style = MaterialTheme.typography.titleMedium)
            Text(
                "Copies full message text into a separate table so message formats can be studied. " +
                    "Personal-number SMS senders are skipped and OTP digits are masked.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (!export.paired) {
                Text("Pair this phone with the backend first.", style = MaterialTheme.typography.bodySmall)
                return@Column
            }
            Text("SMS", style = MaterialTheme.typography.titleSmall)
            Text(describe(export.sms, false), style = MaterialTheme.typography.bodySmall)
            Button(onClick = actions.fetchAllSms, enabled = export.sms.state != ExportState.RUNNING) { Text("Fetch all SMS") }

            Text("Gmail", style = MaterialTheme.typography.titleSmall)
            Text(describe(export.mail, true), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!export.gmailConnected) {
                    Button(onClick = actions.connectGmail) { Text("Connect Gmail") }
                } else {
                    Button(onClick = actions.fetchGmail, enabled = export.mail.state != ExportState.RUNNING) { Text("Fetch Gmail") }
                    OutlinedButton(onClick = actions.connectGmail) { Text("Reconnect") }
                }
            }
            export.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
