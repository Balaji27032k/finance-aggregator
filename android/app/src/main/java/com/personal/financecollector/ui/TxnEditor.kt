package com.personal.financecollector.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Full-height editor: Save and Cancel stay at the top so they are always reachable, even with the keyboard open.
 * Saved on the phone and used in every total.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun TxnEditSheet(
    txn: LedgerTxn,
    onSave: (LedgerTxn, String?, String?, String?) -> Unit,
    onReset: (LedgerTxn, Boolean) -> Unit,
    onSplit: (LedgerTxn, List<SplitPart>) -> Unit,
    onClearSplit: (LedgerTxn) -> Unit,
    knownCategories: List<String>,
    onManagePayees: () -> Unit,
    onClose: () -> Unit,
    onDone: () -> Unit,
) {
    var splitting by remember(txn.key) { mutableStateOf(false) }
    var label by remember(txn.key) { mutableStateOf(txn.label.orEmpty()) }
    // Starts as the category the user set before, so saving a note alone never wipes it.
    var chosen by remember(txn.key) { mutableStateOf(txn.userCategory) }
    var custom by remember(txn.key) { mutableStateOf("") }
    val payee = LedgerOverlay.payeeKey(txn.counterparty)
    var always by remember(txn.key) { mutableStateOf(false) }
    val finalCategory = custom.trim().take(30).ifBlank { null } ?: chosen
    val shownCategory = finalCategory ?: txn.category
    val choices = (Categories.CHOICES + txn.category + listOfNotNull(txn.userCategory)).distinct()

    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onClose) { Text("Cancel") }
                Text("Edit transaction", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Button(onClick = {
                    onSave(txn, finalCategory, label, if (always) shownCategory else null)
                    onDone()
                }) { Text("Save") }
            }
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "${txn.title}   ${if (txn.credit) "+" else "-"}${EventPresenter.rupees(txn.amount)}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
                            selected = shownCategory == c,
                            onClick = { chosen = c; custom = ""; if (payee != null) always = true },
                            label = { Text(c) },
                        )
                    }
                }
                OutlinedTextField(
                    value = custom,
                    onValueChange = { custom = it.take(30); if (it.isNotBlank() && payee != null) always = true },
                    label = { Text("Or type your own category") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (payee != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = always, onCheckedChange = { always = it })
                        Text(
                            "Use \u201c$shownCategory\u201d for every payment to ${txn.title}, past and future",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                } else {
                    Text(
                        "This payment has no payee name in the message, so the category can only be set for this one payment.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (shownCategory == Categories.OWN_TRANSFER) {
                    Text(
                        "Own transfers are left out of your spending and income totals.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onManagePayees) { Text("Manage all payees") }
                    if (txn.edited) {
                        TextButton(onClick = { onReset(txn, true); onDone() }) { Text("Reset to automatic") }
                    }
                }
                if (!txn.credit) {
                    TextButton(onClick = { splitting = true }) {
                        Text(if (txn.splits.isNotEmpty()) "Edit split (${txn.splits.joinToString(", ") { it.category }})" else "Split across categories")
                    }
                }
            }
        }
    }
    if (splitting) {
        SplitDialog(
            txn,
            knownCategories,
            onSave = { onSplit(txn, it); splitting = false; onDone() },
            onClear = { onClearSplit(txn); splitting = false; onDone() },
            onDismiss = { splitting = false },
        )
    }
}
