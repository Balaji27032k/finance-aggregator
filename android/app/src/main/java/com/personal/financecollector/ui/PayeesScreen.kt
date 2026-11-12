package com.personal.financecollector.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.personal.financecollector.database.PayeeRuleEntity
import java.math.BigDecimal

private data class PayeeRow(val key: String, val name: String, val count: Int, val total: BigDecimal, val category: String, val hasRule: Boolean)

/** Every payee seen so far, with the category their payments are counted in. Tap one to put all its payments in a category. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PayeesScreen(
    ledger: List<LedgerTxn>,
    rules: List<PayeeRuleEntity>,
    onSet: (String, String) -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    var picking by remember { mutableStateOf<PayeeRow?>(null) }
    val ruleByKey = remember(rules) { rules.associate { it.payeeKey to it.category } }
    val rows = remember(ledger, ruleByKey) {
        ledger.filter { LedgerOverlay.payeeKey(it.counterparty) != null }
            .groupBy { LedgerOverlay.payeeKey(it.counterparty)!! }
            .map { (key, list) ->
                val name = list.mapNotNull { it.counterparty }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: key
                val category = ruleByKey[key] ?: list.groupingBy { it.category }.eachCount().maxByOrNull { it.value }?.key ?: Categories.OTHER
                PayeeRow(key, prettyName(name), list.size, list.sumOf { it.amount }, category, key in ruleByKey)
            }
            .sortedByDescending { it.count }
    }
    val shown = rows.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }

    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Text("Payees and categories", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 8.dp))
            Text(
                "Choose a category for a payee once and every payment to them, past and future, is counted in it. Tap a payee to change it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 6.dp),
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search payees") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
        }
        if (shown.isEmpty()) item { Text("No payees yet.", modifier = Modifier.padding(vertical = 16.dp)) }
        items(shown, key = { it.key }) { p ->
            Row(
                Modifier.fillMaxWidth().clickable { picking = p }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CategoryAvatar(p.category)
                Column(Modifier.weight(1f)) {
                    Text(p.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${p.count} payment${if (p.count == 1) "" else "s"} \u00b7 ${rupeesWhole(p.total)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(p.category, style = MaterialTheme.typography.labelLarge, color = CategoryColors.of(p.category))
                    if (p.hasRule) Text("set by you", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    picking?.let { p ->
        var chosen by remember(p.key) { mutableStateOf(p.category) }
        var custom by remember(p.key) { mutableStateOf("") }
        val picked = custom.trim().take(30).ifBlank { null } ?: chosen
        AlertDialog(
            onDismissRequest = { picking = null },
            title = { Text(p.name) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Count every payment to this payee in:")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        (Categories.CHOICES + p.category).distinct().forEach { c ->
                            FilterChip(selected = picked == c, onClick = { chosen = c; custom = "" }, label = { Text(c) })
                        }
                    }
                    OutlinedTextField(
                        value = custom,
                        onValueChange = { custom = it.take(30) },
                        label = { Text("Or type your own") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = { TextButton(onClick = { onSet(p.key, picked); picking = null }) { Text("Save") } },
            dismissButton = {
                Row {
                    if (p.hasRule) TextButton(onClick = { onRemove(p.key); picking = null }) { Text("Back to automatic") }
                    TextButton(onClick = { picking = null }) { Text("Cancel") }
                }
            },
        )
    }
}
