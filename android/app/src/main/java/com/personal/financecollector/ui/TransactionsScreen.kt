package com.personal.financecollector.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private enum class Direction(val label: String) { ALL("All"), OUT("Money out"), IN("Money in") }

internal fun matches(t: LedgerTxn, query: String): Boolean {
    if (query.isBlank()) return true
    val q = query.trim().lowercase()
    return listOfNotNull(t.counterparty, t.label, t.note, t.referenceNo, t.category, t.accountLast4, t.paymentMode)
        .plus(t.splits.flatMap { listOfNotNull(it.category, it.name) })
        .any { it.lowercase().contains(q) } || t.amount.stripTrailingZeros().toPlainString().contains(q)
}

/**
 * How much of this payment went on what was searched for: only the named parts of a split payment, otherwise the whole
 * payment when its payee, note, description or category fits. Money in and own transfers never count.
 */
internal fun matchedSpend(t: LedgerTxn, query: String): java.math.BigDecimal {
    val q = query.trim().lowercase()
    if (q.isEmpty() || t.credit || t.internalTransfer) return java.math.BigDecimal.ZERO
    fun hit(s: String?) = s != null && s.lowercase().contains(q)
    if (t.splits.isNotEmpty()) {
        return t.splits.filter { hit(it.category) || hit(it.name) }.fold(java.math.BigDecimal.ZERO) { acc, p -> acc + p.amount }
    }
    return if (hit(t.counterparty) || hit(t.note) || hit(t.label) || hit(t.category)) t.amount else java.math.BigDecimal.ZERO
}

@Composable
fun CategoryAvatar(category: String, size: Int = 40) {
    val color = CategoryColors.of(category)
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(color.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(category.first().uppercase(), color = color, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun TxnRow(txn: LedgerTxn, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CategoryAvatar(txn.category)
        Column(Modifier.weight(1f)) {
            Text(txn.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(txn.label?.let { "“$it”" }, txn.category, txn.paymentMode, txn.sources.joinToString("+")).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                (if (txn.credit) "+" else "-") + EventPresenter.rupees(txn.amount),
                fontWeight = FontWeight.SemiBold,
                color = when {
                    txn.internalTransfer -> MaterialTheme.colorScheme.onSurfaceVariant
                    txn.credit -> MoneyColors.income()
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
            Text(timeOfDay(txn.timeMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun TransactionsScreen(
    ledger: List<LedgerTxn>,
    onOpen: (LedgerTxn) -> Unit,
    presetCategory: String? = null,
    presetQuery: String? = null,
    presetAccount: String? = null,
    onPresetUsed: () -> Unit = {},
    onExport: (List<LedgerTxn>) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var direction by rememberSaveable { mutableStateOf(Direction.ALL) }
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    var account by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(presetCategory, presetQuery, presetAccount) {
        if (presetCategory != null || presetQuery != null || presetAccount != null) {
            query = presetQuery.orEmpty()
            direction = if (presetAccount != null) Direction.ALL else Direction.OUT
            category = presetCategory
            account = presetAccount
            onPresetUsed()
        }
    }
    val today = remember { LocalDate.now(LedgerBuilder.IST) }

    val categories = remember(ledger) { ledger.flatMap { it.categoryParts.map { p -> p.first } }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key } }
    val shown = remember(ledger, query, direction, category, account) {
        ledger.filter {
            matches(it, query) &&
                (account == null || it.accountLast4 == account) &&
                (direction == Direction.ALL || (direction == Direction.IN) == it.credit) &&
                (category == null || it.category == category || it.splits.any { s -> s.category == category })
        }
    }
    val grouped = remember(shown) { shown.groupBy { it.date }.toSortedMap(compareByDescending { it }) }
    val spentOnQuery = remember(shown, query) {
        if (query.isBlank()) null else shown.fold(java.math.BigDecimal.ZERO) { acc, t -> acc + matchedSpend(t, query) }
    }

    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Text("Transactions", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 8.dp, bottom = 8.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search payee, note, amount") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                items(Direction.entries) { d ->
                    FilterChip(direction == d, { direction = d }, { Text(d.label) })
                }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                account?.let { a -> item { FilterChip(true, { account = null }, { Text("Account ending $a  \u2715") }) } }
                items(categories) { c ->
                    FilterChip(category == c, { category = if (category == c) null else c }, { Text(c) })
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text(
                        "${shown.size} transaction${if (shown.size == 1) "" else "s"}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (spentOnQuery != null && spentOnQuery.signum() > 0) {
                        Text(
                            "Spent on \u201c${query.trim()}\u201d: ${rupeesWhole(spentOnQuery)}",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                TextButton(onClick = { onExport(shown) }, enabled = shown.isNotEmpty()) { Text("Export CSV") }
            }
        }
        if (shown.isEmpty()) {
            item {
                Text(
                    if (ledger.isEmpty()) "Nothing yet. Bank SMS and e-mails appear here once they are read." else "Nothing matches these filters.",
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }
        }
        grouped.forEach { (date, list) ->
            item(key = "h-$date") {
                val net = list.fold(java.math.BigDecimal.ZERO) { acc, t -> if (t.credit) acc + t.amount else acc - t.amount }
                Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(dayLabel(date, today), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Text(
                        (if (net.signum() >= 0) "+" else "-") + rupeesWhole(net.abs()),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(list, key = { it.key }) { t -> TxnRow(t, onClick = { onOpen(t) }) }
        }
    }
}

private val timeFormat = DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a", Locale.ENGLISH)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TxnDetailSheet(
    txn: LedgerTxn,
    onDismiss: () -> Unit,
    onSave: ((LedgerTxn, String?, String?, String?) -> Unit)? = null,
    onReset: ((LedgerTxn, Boolean) -> Unit)? = null,
    onSplit: (LedgerTxn, List<SplitPart>) -> Unit = { _, _ -> },
    onClearSplit: (LedgerTxn) -> Unit = {},
    knownCategories: List<String> = emptyList(),
    onManagePayees: () -> Unit = {},
) {
    var editing by remember(txn.key) { mutableStateOf(false) }
    if (editing && onSave != null && onReset != null) {
        TxnEditSheet(
            txn, onSave, onReset, onSplit, onClearSplit, knownCategories,
            onManagePayees = { editing = false; onDismiss(); onManagePayees() },
            onClose = { editing = false },
            onDone = { editing = false; onDismiss() },
        )
        return
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(if (txn.credit) "Money in" else "Money out", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                (if (txn.credit) "+" else "-") + EventPresenter.rupees(txn.amount),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = if (txn.credit) MoneyColors.income() else MaterialTheme.colorScheme.onSurface,
            )
            Text(txn.title, style = MaterialTheme.typography.titleMedium)
            txn.label?.let { Text("“$it”", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary) }
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Detail("Category", txn.category + if (txn.internalTransfer) " (not counted in spending)" else "")
                txn.splits.forEach { Detail("  " + (it.name ?: it.category), EventPresenter.rupees(it.amount) + if (it.name != null) " (${it.category})" else "") }
                Detail("Date", txn.date.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)))
                Detail("Received", Instant.ofEpochMilli(txn.timeMs).atZone(LedgerBuilder.IST).format(timeFormat))
                txn.paymentMode?.let { Detail("Payment mode", it) }
                txn.accountLast4?.let { Detail("Account", "ending $it") }
                txn.counterpartyAccountLast4?.let { Detail("Other account", "ending $it") }
                txn.referenceNo?.let { Detail("Reference", it) }
                txn.balanceAfter?.let { Detail("Balance after", EventPresenter.rupees(it)) }
                txn.note?.let { Detail("Note", it) }
                Detail("Seen in", txn.sources.joinToString(", "))
                Detail("Cloud", if (txn.uploaded) "Uploaded" else "Waiting to upload")
            }
            if (onSave != null && onReset != null && txn.eventIds.isNotEmpty()) {
                OutlinedButton(onClick = { editing = true }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    Text(if (txn.edited) "Edit category or note" else "Change category or add a note")
                }
            }
        }
    }
}

@Composable
private fun Detail(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 16.dp))
    }
}
