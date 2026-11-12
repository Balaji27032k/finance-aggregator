package com.personal.financecollector.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Switch
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

/** Where the user lists their own accounts: only these count towards net worth and as "own transfers". */
@Composable
fun OwnAccountsScreen(
    detected: List<DetectedAccount>,
    entries: List<OwnAccountEntity>,
    onAddAccount: (String, String) -> Boolean,
    onAddName: (String) -> Boolean,
    onRemove: (String) -> Unit,
    onOpenAccount: (String) -> Unit,
    balances: List<AccountBalance>,
    netWorthExcluded: Set<String>,
    onToggleNetWorth: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var bank by remember { mutableStateOf("") }
    var digits by remember { mutableStateOf("") }
    var digitsError by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var nameError by remember { mutableStateOf(false) }
    val mine = entries.filter { it.kind == "ACCOUNT" }
    val mineDigits = mine.map { it.value }.toSet()
    val others = detected.filter { it.last4 !in mineDigits }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("My accounts", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 8.dp))
        Panel(container = MaterialTheme.colorScheme.primaryContainer) {
            Text("Why this matters", style = MaterialTheme.typography.titleMedium)
            Text(
                "Only the accounts listed here are counted as yours: they make up your net worth, and money moved between them " +
                    "is marked \"Own transfer\" and left out of spending and income.",
            )
        }
        Panel {
            Text("Your accounts", style = MaterialTheme.typography.titleMedium)
            if (mine.isEmpty()) {
                Text(
                    "None added yet, so every account seen in your messages is treated as yours.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            mine.forEach { e ->
                val seen = detected.filter { it.last4 == e.value }.sumOf { it.count }
                val balance = balances.firstOrNull { it.last4 == e.value }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).clickable { onOpenAccount(e.value) }.padding(vertical = 4.dp)) {
                        Text(e.display, fontWeight = FontWeight.Medium)
                        Text(
                            "$seen transaction${if (seen == 1) "" else "s"}" + (balance?.let { " \u00b7 balance ${rupeesWhole(it.balance)}" } ?: "") + " \u203a",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (balance != null) {
                        Column(horizontalAlignment = Alignment.End) {
                            Switch(balance.key !in netWorthExcluded, { onToggleNetWorth(balance.key, it) })
                            Text("In net worth", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    TextButton(onClick = { onRemove(e.accountKey) }) { Text("Remove") }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = bank,
                    onValueChange = { bank = it.take(30) },
                    label = { Text("Bank") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = digits,
                    onValueChange = { digits = it.take(8); digitsError = false },
                    label = { Text("Last digits") },
                    isError = digitsError,
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.weight(1f),
                )
            }
            Button(
                onClick = { if (onAddAccount(digits, bank)) { digits = ""; bank = "" } else digitsError = true },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Add account") }
            Text(
                "Enter the last 3 to 8 digits as they appear in your messages. Several senders of one bank (for example SBIUPI and CBSSBI) are one account.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (others.isNotEmpty()) {
            Panel {
                Text("Other accounts seen in your messages", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (mine.isEmpty()) "These are counted as yours until you add your own accounts above."
                    else "Not in your list, so left out of net worth. Tap one to see its transactions, or add it if it is yours.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                others.forEach { a ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).clickable { onOpenAccount(a.last4) }.padding(vertical = 4.dp)) {
                            Text("${a.bankCode} ending ${a.last4}", fontWeight = FontWeight.Medium)
                            Text(
                                "${a.count} transaction${if (a.count == 1) "" else "s"} \u203a",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (mine.isNotEmpty()) TextButton(onClick = { onAddAccount(a.last4, a.bankCode) }) { Text("Add") }
                    }
                }
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
            Text("Two sides of one transfer", style = MaterialTheme.typography.titleMedium)
            Text(
                "A debit from one of your accounts and an equal credit into another of yours within ten minutes is treated as one transfer. You can always change a transaction by tapping it.",
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
