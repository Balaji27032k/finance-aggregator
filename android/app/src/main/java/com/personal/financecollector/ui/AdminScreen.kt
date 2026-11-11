package com.personal.financecollector.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.personal.financecollector.admin.AdminSummary
import com.personal.financecollector.admin.CountItem
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private enum class AdminTab(val label: String) { SUMMARY("Summary"), TRANSACTIONS("Transactions"), HEALTH("Data health") }

/** Everything the server holds, from all sources. Sign-in happens here; the password is sent once and never stored. */
@Composable
fun AdminScreen(
    vm: AdminViewModel,
    paired: Boolean,
    budget: Long?,
    onSetBudget: (Long?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val signedIn by vm.signedIn.collectAsStateWithLifecycle()
    when {
        !paired -> Column(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Admin", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 8.dp))
            Panel {
                Text("Pair this phone first", style = MaterialTheme.typography.titleMedium)
                Text("Admin shows what the server holds, so the phone must be paired with it. Pair it in the Health tab, then come back here.")
            }
        }
        !signedIn -> LoginForm(vm, modifier)
        else -> AdminHome(vm, budget, onSetBudget, modifier)
    }
}

@Composable
private fun LoginForm(vm: AdminViewModel, modifier: Modifier) {
    var user by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val busy by vm.signingIn.collectAsStateWithLifecycle()
    val error by vm.loginError.collectAsStateWithLifecycle()

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Admin", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 8.dp))
        Panel(container = MaterialTheme.colorScheme.primaryContainer) {
            Text("Sign in", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text("Use the admin user name and password you set on the server to see everything from all your sources.")
            OutlinedTextField(
                value = user,
                onValueChange = { user = it },
                label = { Text("User name") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Medium) }
            Button(
                onClick = { vm.signIn(user, password); password = "" },
                enabled = !busy && user.isNotBlank() && password.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (busy) "Signing in..." else "Sign in") }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        Panel {
            Text("Good to know", style = MaterialTheme.typography.titleMedium)
            Text("The first sign-in can take about a minute while the free server wakes up.", style = MaterialTheme.typography.bodyMedium)
            Text("The password is not saved on this phone. You sign in again after closing the app, and after 12 hours.", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun AdminHome(vm: AdminViewModel, budget: Long?, onSetBudget: (Long?) -> Unit, modifier: Modifier) {
    var tab by rememberSaveable { mutableStateOf(AdminTab.SUMMARY) }
    var period by rememberSaveable { mutableStateOf(Period.MONTH) }
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    var openKey by rememberSaveable { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    val today = remember { LocalDate.now(LedgerBuilder.IST) }
    val (from, to) = period.range(today)
    val summary by vm.summary.collectAsStateWithLifecycle()
    val txns by vm.transactions.collectAsStateWithLifecycle()

    Column(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Admin", style = MaterialTheme.typography.headlineSmall)
            Row {
                TextButton(onClick = { refresh++ }) { Text("Refresh") }
                TextButton(onClick = vm::signOut) { Text("Sign out") }
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 4.dp)) {
            items(AdminTab.entries) { t -> FilterChip(tab == t, { tab = t }, { Text(t.label) }) }
        }
        if (tab != AdminTab.HEALTH) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                items(Period.entries) { p -> FilterChip(period == p, { period = p }, { Text(p.label) }) }
            }
        }
        when (tab) {
            AdminTab.SUMMARY -> {
                LaunchedEffect(period, refresh) { vm.loadSummary(from, to) }
                SummaryTab(
                    state = summary,
                    budget = budget,
                    onSetBudget = onSetBudget,
                    onRetry = { vm.loadSummary(from, to) },
                    onOpen = { openKey = it.key },
                    onOpenCategory = { category = it; tab = AdminTab.TRANSACTIONS },
                )
            }
            AdminTab.TRANSACTIONS -> TransactionsTab(vm, txns, from, to, category, refresh, { category = it }, { openKey = it.key })
            AdminTab.HEALTH -> {
                LaunchedEffect(refresh) { vm.loadHealth() }
                HealthTab(vm)
            }
        }
    }
    val all = (summary.data?.stats?.biggest.orEmpty()) + (txns.data?.items.orEmpty())
    all.firstOrNull { it.key == openKey }?.let { TxnDetailSheet(it, onDismiss = { openKey = null }) }
}

@Composable
private fun StateBanner(state: Remote<*>, onRetry: () -> Unit) {
    if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 8.dp))
    state.error?.let {
        Panel(container = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.padding(bottom = 8.dp)) {
            Text(it)
            OutlinedButton(onClick = onRetry) { Text("Try again") }
        }
    }
}

@Composable
private fun SummaryTab(
    state: Remote<AdminSummary>,
    budget: Long?,
    onSetBudget: (Long?) -> Unit,
    onRetry: () -> Unit,
    onOpen: (LedgerTxn) -> Unit,
    onOpenCategory: (String) -> Unit,
) {
    StateBanner(state, onRetry)
    val s = state.data ?: return
    val stats = s.stats
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { HeroCard(stats) }
        item { HighlightsCard(stats.highlights) }
        item { InfoCard("Money in and out") { InOutChart(stats.daily) } }
        if (stats.byCategory.isNotEmpty()) item { CategoryCard(stats, onOpenCategory) }
        item { PaceCard(s.pace, budget, onSetBudget) }
        item {
            InfoCard("Last 12 months") {
                GroupedBars(
                    labels = s.monthly.map { monthShort(it.month) },
                    series = listOf(MoneyColors.income() to s.monthly.map { it.income }, MoneyColors.spend() to s.monthly.map { it.spend }),
                    caption = { i -> s.monthly[i].let { "${monthShort(it.month)} ${it.month.take(4)}   In ${rupeesWhole(it.income)}   Out ${rupeesWhole(it.spend)}" } },
                )
            }
        }
        item {
            InfoCard(if (stats.daily.size > 182) "Spending calendar (last 26 weeks)" else "Spending calendar") {
                SpendCalendar(stats.daily.takeLast(182))
            }
        }
        item {
            InfoCard("By day of week") {
                GroupedBars(
                    labels = s.byWeekday.map { it.key },
                    series = listOf(MaterialTheme.colorScheme.primary to s.byWeekday.map { it.total }),
                    caption = { i -> s.byWeekday[i].let { "${it.key}   ${rupeesWhole(it.total)} in ${it.count} payments" } },
                )
            }
        }
        item {
            InfoCard("By time of day (India time)") {
                GroupedBars(
                    labels = s.byHour.map { it.key },
                    series = listOf(MaterialTheme.colorScheme.secondary to s.byHour.map { it.total }),
                    caption = { i -> s.byHour[i].let { "${it.key}:00   ${rupeesWhole(it.total)} in ${it.count} payments" } },
                    labelEvery = 4,
                )
            }
        }
        if (s.spikes.isNotEmpty()) {
            item {
                InfoCard("Unusually high days") {
                    s.spikes.forEach { d -> StatRow(shortDate(d.date), rupeesWhole(d.spend)) }
                }
            }
        }
        if (s.balances.isNotEmpty()) {
            item {
                InfoCard("Latest balances") {
                    s.balances.take(6).forEach { b ->
                        StatRow("${b.bank} ending ${b.last4}", EventPresenter.rupees(b.balance) + "\n" + ago(b.asOf))
                    }
                }
            }
        }
        if (s.recurring.isNotEmpty()) {
            item {
                InfoCard("Repeating payments") {
                    s.recurring.take(8).forEach { r ->
                        StatRow(prettyName(r.name) + "\n${r.times} times, next around ${shortDate(r.next)}", rupeesWhole(r.amount))
                    }
                }
            }
        }
        if (stats.topPayees.isNotEmpty()) item { TopPayeesCard("Top payees", stats.topPayees) }
        if (s.incomeSources.isNotEmpty()) item { TopPayeesCard("Where money came from", s.incomeSources.take(5)) }
        if (s.byMode.isNotEmpty()) item { TopPayeesCard("By payment mode", s.byMode.take(6)) }
        if (s.byBank.isNotEmpty()) item { TopPayeesCard("By bank", s.byBank.take(6)) }
        if (stats.biggest.isNotEmpty()) {
            item {
                InfoCard("Biggest expenses") {
                    stats.biggest.forEach { t -> TxnRow(t, onClick = { onOpen(t) }) }
                }
            }
        }
        item {
            Text(
                "These numbers come from the server and cover every source and device that uploaded to it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TransactionsTab(
    vm: AdminViewModel,
    state: Remote<com.personal.financecollector.admin.AdminTxnPage>,
    from: LocalDate,
    to: LocalDate,
    category: String?,
    refresh: Int,
    onCategory: (String?) -> Unit,
    onOpen: (LedgerTxn) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var direction by rememberSaveable { mutableStateOf<String?>(null) }
    var limit by rememberSaveable { mutableIntStateOf(50) }
    LaunchedEffect(from, to, query, direction, category) { limit = 50 }
    LaunchedEffect(from, to, query, direction, category, limit, refresh) {
        delay(300)
        vm.loadTransactions(from, to, query, direction, category, limit)
    }
    val page = state.data

    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        placeholder = { Text("Search payee, note, amount") },
        leadingIcon = { Icon(Icons.Default.Search, null) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
        items(listOf<String?>(null, "DEBIT", "CREDIT")) { d ->
            FilterChip(direction == d, { direction = d }, { Text(when (d) { "DEBIT" -> "Money out"; "CREDIT" -> "Money in"; else -> "All" }) })
        }
        category?.let { c -> item { FilterChip(true, { onCategory(null) }, { Text("$c  x") }) } }
        items(page?.categories.orEmpty().filter { it != category }) { c -> FilterChip(false, { onCategory(c) }, { Text(c) }) }
    }
    StateBanner(state) { vm.loadTransactions(from, to, query, direction, category, limit) }
    if (page == null) return
    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("${page.total} found", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Out ${rupeesWhole(page.totalDebit)}  In ${rupeesWhole(page.totalCredit)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        if (page.items.isEmpty()) item { Text("Nothing matches these filters.", modifier = Modifier.padding(vertical = 24.dp)) }
        val today = LocalDate.now(LedgerBuilder.IST)
        page.items.groupBy { it.date }.toSortedMap(compareByDescending { it }).forEach { (date, list) ->
            item(key = "h-$date") {
                val net = list.fold(java.math.BigDecimal.ZERO) { acc, t -> if (t.credit) acc + t.amount else acc - t.amount }
                Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(dayLabel(date, today), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Text((if (net.signum() >= 0) "+" else "-") + rupeesWhole(net.abs()), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(list, key = { it.key }) { t -> TxnRow(t, onClick = { onOpen(t) }) }
        }
        if (page.items.size < page.total) {
            item {
                if (limit < 200) {
                    OutlinedButton(onClick = { limit += 50 }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Show more") }
                } else {
                    Text(
                        "Showing the newest 200. Narrow the dates or search to see others.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun HealthTab(vm: AdminViewModel) {
    val state by vm.health.collectAsStateWithLifecycle()
    StateBanner(state) { vm.loadHealth() }
    val h = state.data ?: return
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Panel {
                Text("On the server", style = MaterialTheme.typography.titleMedium)
                StatRow("Transactions", h.transactions.toString())
                StatRow("Seen by more than one source", h.multiChannel.toString())
                StatRow("Oldest message", h.firstEvent?.let { dateFormat.format(it.atZone(LedgerBuilder.IST)) } ?: "none")
                StatRow("Newest message", ago(h.bySource.mapNotNull { it.latest }.maxOrNull()))
            }
        }
        item { CountsPanel("By source", h.bySource) }
        item { CountsPanel("By bank or sender", h.byBank) }
    }
}

@Composable
private fun CountsPanel(title: String, items: List<CountItem>) {
    Panel {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (items.isEmpty()) Text("No data yet")
        items.forEach { c ->
            StatRow(
                when (c.key) {
                    "GMAIL" -> "Email"
                    "NOTIFICATION" -> "Notification"
                    else -> c.key
                } + "\nlast " + ago(c.latest),
                c.count.toString(),
            )
        }
    }
}

private val dateFormat = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

private fun ago(at: Instant?): String {
    if (at == null) return "never"
    val minutes = Duration.between(at, Instant.now()).toMinutes().coerceAtLeast(0)
    return when {
        minutes < 2 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 60 * 24 -> "${minutes / 60} h ago"
        else -> "${minutes / (60 * 24)} days ago"
    }
}
