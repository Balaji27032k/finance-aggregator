package com.personal.financecollector.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import androidx.compose.ui.unit.dp
import com.personal.financecollector.database.OwnAccountEntity

/** Where the user tells the app which accounts are theirs, so moving money between them is not counted as spending. */
@Composable
fun OwnAccountsScreen(
    detected: List<DetectedAccount>,
    entries: List<OwnAccountEntity>,
    onAddAccount: (String) -> Boolean,
    onAddName: (String) -> Boolean,
    onRemove: (String) -> Unit,
    onOpenAccount: (String) -> Unit,
    balances: List<AccountBalance>,
    netWorthExcluded: Set<String>,
    onToggleNetWorth: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var digits by remember { mutableStateOf("") }
    var digitsError by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var nameError by remember { mutableStateOf(false) }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("My accounts", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 8.dp))
        Panel(container = MaterialTheme.colorScheme.primaryContainer) {
            Text("Why this matters", style = MaterialTheme.typography.titleMedium)
            Text(
                "When you move money between your own accounts, it should not count as spending or income. " +
                    "Tell the app which accounts are yours and those transfers are marked \"Own transfer\" and left out of your totals.",
            )
        }
        Panel {
            Text("Found in your messages", style = MaterialTheme.typography.titleMedium)
            if (detected.isEmpty()) Text("No accounts seen yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            detected.forEach { a ->
                Box(Modifier.clickable { onOpenAccount(a.last4) }.padding(vertical = 4.dp)) {
                    StatRow("${a.bankCode} ending ${a.last4}", "${a.count} transaction${if (a.count == 1) "" else "s"} \u203a")
                }
            }
            if (detected.isNotEmpty()) {
                Text(
                    "Tap an account to see its transactions. Messages from the same bank under different names (for example SBIUPI and CBSSBI) are one account here. These are counted as yours automatically.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Panel {
            Text("Other accounts of yours", style = MaterialTheme.typography.titleMedium)
            Text(
                "An account that does not send SMS to this phone, or that you use only as the other side of a transfer. Enter the last digits as they appear in your messages.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            entries.filter { it.kind == "ACCOUNT" }.forEach { e -> RemovableRow(e.display) { onRemove(e.accountKey) } }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = digits,
                    onValueChange = { digits = it.take(8); digitsError = false },
                    label = { Text("Last digits (3 to 8)") },
                    isError = digitsError,
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = { if (onAddAccount(digits)) digits = "" else digitsError = true }) { Text("Add") }
            }
        }
        Panel {
            Text("Names your accounts appear under", style = MaterialTheme.typography.titleMedium)
            Text(
                "Your own name as banks show it, for example when you send money to yourself. A payee is treated as you only when every word of the name matches.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            entries.filter { it.kind == "NAME" }.forEach { e -> RemovableRow(e.display) { onRemove(e.accountKey) } }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(60); nameError = false },
                    label = { Text("Full name") },
                    isError = nameError,
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = { if (onAddName(name)) name = "" else nameError = true }) { Text("Add") }
            }
        }
        Panel {
            Text("Counted in net worth", style = MaterialTheme.typography.titleMedium)
            Text(
                "Net worth adds up the latest balance each account reported. Switch off an account, such as a credit card, that should not be included.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (balances.isEmpty()) Text("No balances seen yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            balances.forEach { b ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${b.bankCode} ending ${b.last4}", fontWeight = FontWeight.Medium)
                        Text(rupeesWhole(b.balance), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    androidx.compose.material3.Switch(b.key !in netWorthExcluded, { onToggleNetWorth(b.key, it) })
                }
            }
        }
        Panel {
            Text("Two sides of one transfer", style = MaterialTheme.typography.titleMedium)
            Text(
                "Even without any of the above, a debit from one of your accounts and an equal credit into another of yours within ten minutes is treated as one transfer. You can always change a transaction by tapping it.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun RemovableRow(text: String, onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(text, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        TextButton(onClick = onRemove) { Text("Remove") }
    }
}
