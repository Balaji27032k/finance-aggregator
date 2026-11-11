package com.personal.financecollector.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Lets the user say what a transaction was for and which category it belongs to. Saved on the phone and used in every total. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TxnEditor(
    txn: LedgerTxn,
    onSave: (LedgerTxn, String?, String?, Boolean) -> Unit,
    onReset: (LedgerTxn, Boolean) -> Unit,
    onDone: () -> Unit,
) {
    var editing by remember(txn.key) { mutableStateOf(false) }
    if (!editing) {
        OutlinedButton(onClick = { editing = true }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
            Text(if (txn.edited) "Edit what this was" else "Change category or add a note")
        }
        return
    }

    var label by remember(txn.key) { mutableStateOf(txn.label.orEmpty()) }
    var chosen by remember(txn.key) { mutableStateOf<String?>(null) }
    var custom by remember(txn.key) { mutableStateOf("") }
    var always by remember(txn.key) { mutableStateOf(true) }
    val payee = LedgerOverlay.payeeKey(txn.counterparty)
    val finalCategory = custom.trim().take(30).ifBlank { null } ?: chosen
    val choices = (Categories.CHOICES + txn.category).distinct()

    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("What was this for?", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = label,
            onValueChange = { label = it.take(60) },
            placeholder = { Text("For example: milk, gas, auto fare") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text("Category", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            choices.forEach { c ->
                FilterChip(
                    selected = (finalCategory ?: txn.category) == c,
                    onClick = { chosen = c; custom = "" },
                    label = { Text(c) },
                )
            }
        }
        OutlinedTextField(
            value = custom,
            onValueChange = { custom = it.take(30); if (it.isNotBlank()) chosen = null },
            label = { Text("Or type your own category") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (payee != null && finalCategory != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = always, onCheckedChange = { always = it })
                Text("Always use \u201c$finalCategory\u201d for ${txn.title}", style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (finalCategory == Categories.OWN_TRANSFER) {
            Text(
                "Own transfers are left out of your spending and income totals.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { onSave(txn, finalCategory, label, always && finalCategory != null); onDone() }) { Text("Save") }
            TextButton(onClick = { editing = false }) { Text("Cancel") }
            if (txn.edited) {
                TextButton(onClick = { onReset(txn, true); onDone() }) { Text("Reset to automatic", fontWeight = FontWeight.Normal) }
            }
        }
    }
}
