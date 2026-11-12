package com.personal.financecollector.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.math.BigDecimal

private class SplitRow(category: String, amount: String) {
    var category by mutableStateOf(category)
    var amount by mutableStateOf(amount)
}

/** Parses split amounts; null when a row is empty, not a positive number, or has more than two decimals. */
internal fun parseSplitAmounts(texts: List<String>): List<BigDecimal>? {
    val values = texts.map { it.replace(",", "").trim().toBigDecimalOrNull() }
    return if (values.any { it == null || it.signum() <= 0 || it.stripTrailingZeros().scale() > 2 }) null else values.filterNotNull()
}

/** Splits one payment across several categories; the parts must add up to the full amount. */
@Composable
fun SplitDialog(
    txn: LedgerTxn,
    knownCategories: List<String>,
    onSave: (List<SplitPart>) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val rows = remember(txn.key) {
        mutableStateListOf<SplitRow>().apply {
            if (txn.splits.isNotEmpty()) txn.splits.forEach { add(SplitRow(it.category, it.amount.stripTrailingZeros().toPlainString())) }
            else {
                add(SplitRow(txn.category, ""))
                add(SplitRow(if (txn.category == "Other") "Groceries" else "Other", ""))
            }
        }
    }
    val amounts = parseSplitAmounts(rows.map { it.amount })
    val assigned = rows.sumOf { it.amount.replace(",", "").trim().toBigDecimalOrNull() ?: BigDecimal.ZERO }
    val left = txn.amount - assigned
    val ok = amounts != null && left.signum() == 0 && rows.all { it.category.isNotBlank() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Split ${EventPresenter.rupees(txn.amount)} across categories") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                rows.forEachIndexed { i, row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        CategoryPicker(row.category, { row.category = it }, Modifier.weight(1f), knownCategories)
                        OutlinedTextField(
                            value = row.amount,
                            onValueChange = { row.amount = it.filter { c -> c.isDigit() || c == '.' }.take(12) },
                            placeholder = { Text("Amount") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.width(120.dp),
                        )
                        if (rows.size > 2) TextButton(onClick = { rows.removeAt(i) }) { Text("Remove") }
                    }
                }
                if (rows.size < 5) TextButton(onClick = { rows += SplitRow("Other", if (left.signum() > 0) left.stripTrailingZeros().toPlainString() else "") }) { Text("Add another category") }
                Text(
                    when {
                        left.signum() == 0 -> "Adds up to the full amount."
                        left.signum() > 0 -> "Still to assign: ${EventPresenter.rupees(left)}"
                        else -> "Over by ${EventPresenter.rupees(left.abs())}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (left.signum() == 0) MoneyColors.income() else MaterialTheme.colorScheme.error,
                )
                Text(
                    "Name a part anything, such as Tea or Biscuits, and later ask the dashboard how much went on it. Amounts can have up to two decimals.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Splits are sent to your server along with your other corrections.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = ok,
                onClick = { onSave(rows.mapIndexed { i, r -> SplitPart(r.category.trim().take(30), amounts!![i]) }) },
            ) { Text("Save split") }
        },
        dismissButton = {
            Row {
                if (txn.splits.isNotEmpty()) TextButton(onClick = onClear) { Text("Remove split") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

/** A category shown as a button that opens the list of choices, with a way to type a new one. */
@Composable
fun CategoryPicker(selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier, known: List<String> = emptyList()) {
    var open by remember { mutableStateOf(false) }
    var typing by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    Column(modifier) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) { Text(selected, maxLines = 1) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            (Categories.CHOICES + known + selected).distinct().forEach { c ->
                DropdownMenuItem(text = { Text(c) }, onClick = { onSelect(c); open = false })
            }
            DropdownMenuItem(text = { Text("Type your own...") }, onClick = { open = false; typed = ""; typing = true })
        }
    }
    if (typing) {
        AlertDialog(
            onDismissRequest = { typing = false },
            title = { Text("Your own category") },
            text = {
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it.take(30) },
                    placeholder = { Text("For example: Tea") },
                    singleLine = true,
                )
            },
            confirmButton = { TextButton(enabled = typed.isNotBlank(), onClick = { onSelect(typed.trim()); typing = false }) { Text("Use") } },
            dismissButton = { TextButton(onClick = { typing = false }) { Text("Cancel") } },
        )
    }
}
