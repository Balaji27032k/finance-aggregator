package com.personal.financecollector.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.personal.financecollector.sync.AskReply
import java.time.LocalDate

private val STARTERS = listOf(
    "How much did I spend on food this month?",
    "What were my biggest expenses last month?",
    "Give me a summary of this year",
    "How much on dosa in the last 12 months?",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AskScreen(vm: AskViewModel, modifier: Modifier = Modifier) {
    val messages by vm.messages.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var text by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val last = messages.lastOrNull()?.reply

    LaunchedEffect(messages.size, busy) {
        if (messages.isNotEmpty() || busy) listState.animateScrollToItem(listState.layoutInfo.totalItemsCount.coerceAtLeast(1) - 1)
    }

    fun send(q: String) {
        vm.send(q)
        text = ""
    }

    Column(modifier.fillMaxSize().imePadding()) {
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = 12.dp),
        ) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Ask AI", style = MaterialTheme.typography.headlineSmall)
                    if (messages.isNotEmpty() && !busy) TextButton(onClick = vm::clear) { Text("New chat") }
                }
                Text(
                    "Ask in your own words. Totals are always added up from your own data.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (messages.isEmpty()) {
                item {
                    Text("Try one of these", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        STARTERS.forEach { s -> AssistChip(onClick = { send(s) }, label = { Text(s) }) }
                    }
                }
            }
            items(messages) { m -> Bubble(m) }
            if (busy) {
                item {
                    Text(
                        "Thinking... the first question can take a minute if the server was asleep.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (!busy && last != null && last.suggestions.isNotEmpty()) {
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        last.suggestions.forEach { s -> AssistChip(onClick = { send(s) }, label = { Text(s) }) }
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(300) },
                placeholder = { Text("For example: masala dosa last year") },
                shape = RoundedCornerShape(20.dp),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (!busy) send(text) }),
                modifier = Modifier.weight(1f),
            )
            Button(onClick = { send(text) }, enabled = !busy && text.isNotBlank()) { Text("Ask") }
        }
    }
}

@Composable
private fun Bubble(m: ChatMessage) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.fromUser) Arrangement.End else Arrangement.Start) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = when {
                m.fromUser -> MaterialTheme.colorScheme.primary
                m.failed -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.surfaceContainerHigh
            },
            contentColor = when {
                m.fromUser -> MaterialTheme.colorScheme.onPrimary
                m.failed -> MaterialTheme.colorScheme.onErrorContainer
                else -> MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.fillMaxWidth(if (m.fromUser) 0.85f else 0.95f),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(m.text)
                m.reply?.let { Details(it) }
            }
        }
    }
}

@Composable
private fun Details(reply: AskReply) {
    var withRelated by remember(reply) { mutableStateOf(false) }
    var showSent by remember(reply) { mutableStateOf(false) }
    val exact = reply.exact
    val related = reply.related
    if (exact != null) {
        val total = exact.total + if (withRelated && related != null) related.total else java.math.BigDecimal.ZERO
        val count = exact.count + if (withRelated && related != null) related.count else 0
        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                Text(
                    reply.terms.joinToString(" / ") + " \u00b7 " + reply.periodLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(rupeesWhole(total), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    "$count payment${if (count == 1) "" else "s"}" + if (withRelated) " including related" else " exactly matching",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (related != null && related.count > 0) {
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Include related (${rupeesWhole(related.total)})", Modifier.weight(1f))
                        Switch(withRelated, { withRelated = it })
                    }
                }
            }
        }
    }
    if (reply.breakdown.isNotEmpty()) {
        Column {
            Text(reply.breakdownTitle.orEmpty(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            reply.breakdown.forEach { g -> StatRow("${g.name} \u00b7 ${g.count}", rupeesWhole(g.total)) }
        }
    }
    if (reply.items.isNotEmpty()) {
        Column {
            Text(
                if (reply.items.size == 1) "Payment" else "Payments",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            reply.items.take(8).forEach { i ->
                val date = runCatching { shortDate(LocalDate.parse(i.date)) }.getOrDefault(i.date)
                StatRow("${prettyName(i.title)}\n${i.name}${if (i.related) " (related)" else ""} \u00b7 $date", rupeesWhole(i.amount))
            }
            if (reply.items.size > 8) Text("and ${reply.items.size - 8} more", style = MaterialTheme.typography.bodySmall)
        }
    }
    Box {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                (if (reply.aiUsed) "Answered with AI" else "Answered by the built-in search") + (reply.note?.let { ". $it" } ?: ""),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (reply.sentToAi.isNotEmpty()) {
                TextButton(onClick = { showSent = !showSent }, contentPadding = PaddingValues(0.dp)) {
                    Text(if (showSent) "Hide what was sent to the AI" else "What was sent to the AI", style = MaterialTheme.typography.labelMedium)
                }
                if (showSent) {
                    Text(
                        "Account numbers, UPI ids, phone numbers, references and balances are removed before anything is sent.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    reply.sentToAi.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}
