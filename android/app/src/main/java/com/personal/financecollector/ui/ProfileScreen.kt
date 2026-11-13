package com.personal.financecollector.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.personal.financecollector.profile.MoneyProfile
import com.personal.financecollector.profile.ProfileGoal
import com.personal.financecollector.profile.ProfileHolding
import com.personal.financecollector.profile.ProfileLoan
import com.personal.financecollector.profile.ProfileRates
import java.math.BigDecimal

private val OCCUPATIONS = listOf(
    "SALARIED" to "Salaried", "SELF_EMPLOYED" to "Self-employed", "BUSINESS" to "Business", "STUDENT" to "Student", "RETIRED" to "Retired", "OTHER" to "Other",
)
private val STABILITY = listOf("STABLE" to "Steady every month", "VARIABLE" to "Varies")
private val TAX = listOf("NEW" to "New regime", "OLD" to "Old regime", "UNSURE" to "Not sure")
private val RISK = listOf("LOW" to "Safety first", "MEDIUM" to "Balanced", "HIGH" to "Growth, I can ride dips")
private val EXPERIENCE = listOf("NONE" to "New to investing", "SOME" to "Some experience", "EXPERIENCED" to "Experienced")
private val PRIORITY = listOf("HIGH" to "High", "MEDIUM" to "Medium", "LOW" to "Low")
private val HOLDING_TYPES = listOf(
    "Savings account", "FD", "RD", "PPF", "EPF", "NPS", "Mutual funds", "Stocks", "Gold", "Post office", "Real estate", "Other",
)
private val LOAN_TYPES = listOf("Home loan", "Car loan", "Personal loan", "Education loan", "Credit card", "Other")

@Composable
private fun NumberField(label: String, value: Long?, onValue: (Long?) -> Unit, modifier: Modifier = Modifier, hint: String? = null) {
    OutlinedTextField(
        value = value?.toString().orEmpty(),
        onValueChange = { onValue(it.filter { c -> c.isDigit() }.take(12).toLongOrNull()) },
        label = { Text(label) },
        supportingText = hint?.let { { Text(it) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun DecimalField(label: String, value: Double?, hint: String, onValue: (Double?) -> Unit, modifier: Modifier = Modifier) {
    var text by remember { mutableStateOf(rateText(value)) }
    LaunchedEffect(value) { if (text.toDoubleOrNull() != value) text = rateText(value) }
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            val cleaned = raw.filter { c -> c.isDigit() || c == '.' }.take(6)
            text = cleaned
            onValue(cleaned.toDoubleOrNull()?.takeIf { it <= 30 })
        },
        label = { Text(label) },
        placeholder = { Text(hint) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth(),
    )
}

private fun rateText(value: Double?) = value?.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() }.orEmpty()

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Choices(options: List<Pair<String, String>>, selected: String?, onSelect: (String?) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { (key, label) ->
            FilterChip(selected = selected == key, onClick = { onSelect(if (selected == key) null else key) }, label = { Text(label) })
        }
    }
}

@Composable
private fun Ask(title: String, hint: String? = null, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        content()
    }
}

@Composable
private fun ListRow(title: String, detail: String, onEdit: () -> Unit, onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = onEdit) { Text("Edit") }
        TextButton(onClick = onRemove) { Text("Remove") }
    }
}

private fun money(value: Long?) = value?.let { rupeesWhole(BigDecimal(it)) } ?: "not set"

/** One of the person's accounts with the balance its latest message reported. */
data class AccountLine(val title: String, val balance: BigDecimal?, val asOfMs: Long?)

/** The listed accounts with their balances; until accounts are listed, every account seen in messages. */
fun accountLines(entries: List<com.personal.financecollector.database.OwnAccountEntity>, balances: List<AccountBalance>): List<AccountLine> {
    val mine = entries.filter { it.kind == "ACCOUNT" }
    if (mine.isEmpty()) return balances.map { AccountLine("${it.bankCode} ending ${it.last4}", it.balance, it.asOfMs) }
    return mine.map { e ->
        val b = balances.firstOrNull { it.last4 == e.value }
        AccountLine(e.display, b?.balance, b?.asOfMs)
    }
}

@Composable
fun ProfileScreen(vm: ProfileViewModel, accounts: List<AccountLine>, onManageAccounts: () -> Unit, modifier: Modifier = Modifier) {
    val state by vm.state.collectAsStateWithLifecycle()
    val p = state.profile
    LaunchedEffect(Unit) {
        vm.load()
        vm.showSavedRates()
    }
    var goalDialog by remember { mutableStateOf<Pair<Int, ProfileGoal?>?>(null) }
    var holdingDialog by remember { mutableStateOf<Pair<Int, ProfileHolding?>?>(null) }
    var loanDialog by remember { mutableStateOf<Pair<Int, ProfileLoan?>?>(null) }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("My profile", style = MaterialTheme.typography.headlineSmall)
            Button(onClick = vm::save, enabled = state.dirty && !state.saving) { Text(if (state.saving) "Saving..." else "Save") }
        }
        Panel(container = MaterialTheme.colorScheme.primaryContainer) {
            Text("Profile ${p.completeness()}% complete", style = MaterialTheme.typography.titleMedium)
            LinearProgressIndicator(progress = { p.completeness() / 100f }, modifier = Modifier.fillMaxWidth())
            Text(
                "Everything here is optional. The more you fill in, the better your money coach can fit its advice to you. It is saved on your own server; your name and any numbers you type are never sent to the AI as identifiers.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (state.loading) Text("Loading your profile...", color = MaterialTheme.colorScheme.onSurfaceVariant)
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        state.message?.let { Text(it, color = MoneyColors.income()) }

        Panel {
            Text("Your accounts", style = MaterialTheme.typography.titleMedium)
            if (accounts.isEmpty()) {
                Text("No account balances seen yet. They come from the balance in your bank messages.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            accounts.forEach { a ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(a.title, fontWeight = FontWeight.Medium)
                        Text(
                            a.asOfMs?.let { "as of " + shortDate(java.time.Instant.ofEpochMilli(it).atZone(LedgerBuilder.IST).toLocalDate()) } ?: "no balance in the messages yet",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(a.balance?.let { EventPresenter.rupees(it) } ?: "-", fontWeight = FontWeight.SemiBold)
                }
            }
            val total = accounts.mapNotNull { it.balance }.fold(BigDecimal.ZERO, BigDecimal::add)
            if (accounts.any { it.balance != null }) StatRow("Total across accounts", EventPresenter.rupees(total))
            Text(
                "Balances are the last one each bank reported by message, so they can be a little behind. Card and ATM payments count towards the account they come from.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onManageAccounts) { Text("Manage my accounts") }
        }

        Panel {
            Text("About you", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = p.name.orEmpty(),
                onValueChange = { v -> vm.edit { it.copy(name = v.take(40).ifBlank { null }) } },
                label = { Text("Name (only shown here)") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            NumberField("Age", p.age?.toLong(), { v -> vm.edit { it.copy(age = v?.toInt()?.coerceAtMost(100)) } }, hint = "14 to 100")
            Ask("What do you do?") { Choices(OCCUPATIONS, p.occupation) { v -> vm.edit { it.copy(occupation = v) } } }
            NumberField("Monthly take-home income (rupees)", p.monthlyIncome, { v -> vm.edit { it.copy(monthlyIncome = v) } }, hint = "After tax, what reaches your account")
            Ask("Is your income steady?") { Choices(STABILITY, p.incomeStability) { v -> vm.edit { it.copy(incomeStability = v) } } }
            NumberField("People who depend on your income", p.dependents?.toLong(), { v -> vm.edit { it.copy(dependents = v?.toInt()?.coerceAtMost(20)) } })
            Ask("Which tax regime do you file under?") { Choices(TAX, p.taxRegime) { v -> vm.edit { it.copy(taxRegime = v) } } }
        }

        Panel {
            Text("Your comfort with risk", style = MaterialTheme.typography.titleMedium)
            Ask("If your investments fell 20% for a year, you would...", "Pick what is closest to you") {
                Choices(RISK, p.riskComfort) { v -> vm.edit { it.copy(riskComfort = v) } }
            }
            Ask("Investing experience") { Choices(EXPERIENCE, p.experience) { v -> vm.edit { it.copy(experience = v) } } }
        }

        Panel {
            Text("Safety net", style = MaterialTheme.typography.titleMedium)
            NumberField("Emergency fund set aside (rupees)", p.emergencyFund, { v -> vm.edit { it.copy(emergencyFund = v) } }, hint = "Money kept only for emergencies")
            NumberField("Health insurance cover (rupees)", p.healthCover, { v -> vm.edit { it.copy(healthCover = v) } }, hint = "Include any employer cover; 0 if none")
            NumberField("Term life insurance cover (rupees)", p.termCover, { v -> vm.edit { it.copy(termCover = v) } }, hint = "0 if none")
        }

        Panel {
            Text("Goals", style = MaterialTheme.typography.titleMedium)
            Text("House, child's education, retirement, a trip... in today's money.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            p.goals.forEachIndexed { i, g ->
                ListRow(
                    g.name,
                    money(g.amount) + (g.year?.let { " by $it" } ?: "") + (g.priority?.let { " \u00b7 ${it.lowercase()} priority" } ?: ""),
                    { goalDialog = i to g },
                    { vm.edit { it.copy(goals = it.goals.filterIndexed { n, _ -> n != i }) } },
                )
            }
            if (p.goals.size < 10) TextButton(onClick = { goalDialog = p.goals.size to null }) { Text("Add a goal") }
        }

        Panel {
            Text("What you already hold", style = MaterialTheme.typography.titleMedium)
            Text("Approximate values are fine. No account or folio numbers.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            p.investments.forEachIndexed { i, h ->
                ListRow(
                    h.type,
                    money(h.amount) + (h.monthly?.takeIf { it > 0 }?.let { " \u00b7 ${rupeesWhole(BigDecimal(it))} a month" } ?: ""),
                    { holdingDialog = i to h },
                    { vm.edit { it.copy(investments = it.investments.filterIndexed { n, _ -> n != i }) } },
                )
            }
            if (p.investments.size < 15) TextButton(onClick = { holdingDialog = p.investments.size to null }) { Text("Add an investment") }
        }

        Panel {
            Text("Loans and EMIs", style = MaterialTheme.typography.titleMedium)
            p.loans.forEachIndexed { i, l ->
                ListRow(
                    l.type,
                    "${money(l.outstanding)} left" + (l.emi?.takeIf { it > 0 }?.let { " \u00b7 EMI ${rupeesWhole(BigDecimal(it))}" } ?: "") + (l.rate?.let { " \u00b7 $it%" } ?: ""),
                    { loanDialog = i to l },
                    { vm.edit { it.copy(loans = it.loans.filterIndexed { n, _ -> n != i }) } },
                )
            }
            if (p.loans.size < 8) TextButton(onClick = { loanDialog = p.loans.size to null }) { Text("Add a loan") }
        }

        Panel {
            Text("Latest market rates", style = MaterialTheme.typography.titleMedium)
            Text(
                "Looked up on the web for each kind of investment. When a live lookup is not possible, standard values are shown and marked as such. Nothing is guessed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = vm::fetchRates, enabled = !state.ratesBusy, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.ratesBusy) "Looking up the web... this can take a minute" else "Fetch latest rates")
            }
            state.ratesNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            val found = state.rates
            if (found != null && found.items.isNotEmpty()) {
                found.fetchedAt?.let { Text("Fetched $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                found.items.forEach { r ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(r.label, fontWeight = FontWeight.Medium)
                            val detail = listOfNotNull(r.asOf?.let { "as of $it" }, r.note).joinToString(" \u00b7 ")
                            if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("${r.percent.stripTrailingZeros().toPlainString()}%", fontWeight = FontWeight.SemiBold)
                    }
                }
                if (found.sources.isNotEmpty()) {
                    Text(
                        "Sources: " + found.sources.take(6).joinToString(", ") { s -> s.title.ifBlank { s.url } },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = vm::useFetchedRates) { Text("Use these as my assumptions") }
            }
        }

        Panel {
            Text("Rates the coach should assume", style = MaterialTheme.typography.titleMedium)
            Text(
                "Yearly percentages used in plans. Leave blank to use the app's rough defaults, and change them when rates change.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val r = p.rates
            fun rate(change: (ProfileRates, Double?) -> ProfileRates) = { v: Double? -> vm.edit { it.copy(rates = change(it.rates, v)) } }
            DecimalField("Inflation", r.inflation, "6", rate { x, v -> x.copy(inflation = v) })
            DecimalField("Equity / equity funds return", r.equity, "12", rate { x, v -> x.copy(equity = v) })
            DecimalField("Debt funds return", r.debt, "7", rate { x, v -> x.copy(debt = v) })
            DecimalField("Fixed deposit rate", r.fd, "7", rate { x, v -> x.copy(fd = v) })
            DecimalField("PPF rate", r.ppf, "7.1", rate { x, v -> x.copy(ppf = v) })
            DecimalField("EPF rate", r.epf, "8.25", rate { x, v -> x.copy(epf = v) })
            DecimalField("Savings account rate", r.savings, "3", rate { x, v -> x.copy(savings = v) })
        }

        Panel {
            Text("Anything else the coach should know", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = p.notes.orEmpty(),
                onValueChange = { v -> vm.edit { it.copy(notes = v.take(500).ifBlank { null }) } },
                placeholder = { Text("For example: planning to change jobs next year") },
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Button(onClick = vm::save, enabled = state.dirty && !state.saving, modifier = Modifier.fillMaxWidth()) { Text(if (state.saving) "Saving..." else "Save profile") }
        Column(Modifier.padding(bottom = 24.dp)) {}
    }

    goalDialog?.let { (index, existing) ->
        EntryDialog(
            title = if (existing == null) "Add a goal" else "Edit goal",
            onDismiss = { goalDialog = null },
        ) { close ->
            var name by remember { mutableStateOf(existing?.name.orEmpty()) }
            var amount by remember { mutableStateOf(existing?.amount) }
            var year by remember { mutableStateOf(existing?.year?.toLong()) }
            var priority by remember { mutableStateOf(existing?.priority) }
            OutlinedTextField(value = name, onValueChange = { name = it.take(40) }, label = { Text("Goal") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            NumberField("Amount needed, in today's money", amount, { amount = it })
            NumberField("Target year", year, { year = it }, hint = "For example 2034")
            Ask("Priority") { Choices(PRIORITY, priority) { priority = it } }
            val valid = name.isNotBlank() && (amount ?: 0) > 0 && (year == null || year!! in 2026..2100)
            TextButton(
                enabled = valid,
                onClick = {
                    val goal = ProfileGoal(name.trim(), amount!!, year?.toInt(), priority)
                    vm.edit { p0 -> p0.copy(goals = if (index < p0.goals.size) p0.goals.mapIndexed { n, g -> if (n == index) goal else g } else p0.goals + goal) }
                    close()
                },
            ) { Text("Save goal") }
        }
    }
    holdingDialog?.let { (index, existing) ->
        EntryDialog(title = if (existing == null) "Add an investment" else "Edit investment", onDismiss = { holdingDialog = null }) { close ->
            var type by remember { mutableStateOf(existing?.type) }
            var amount by remember { mutableStateOf(existing?.amount) }
            var monthly by remember { mutableStateOf(existing?.monthly) }
            Ask("Type") { Choices(HOLDING_TYPES.map { it to it }, type) { type = it } }
            NumberField("Approximate value today (rupees)", amount, { amount = it })
            NumberField("Added every month (rupees)", monthly, { monthly = it }, hint = "SIP or regular deposit; blank if none")
            TextButton(
                enabled = type != null && amount != null,
                onClick = {
                    val holding = ProfileHolding(type!!, amount!!, monthly)
                    vm.edit { p0 -> p0.copy(investments = if (index < p0.investments.size) p0.investments.mapIndexed { n, h -> if (n == index) holding else h } else p0.investments + holding) }
                    close()
                },
            ) { Text("Save investment") }
        }
    }
    loanDialog?.let { (index, existing) ->
        EntryDialog(title = if (existing == null) "Add a loan" else "Edit loan", onDismiss = { loanDialog = null }) { close ->
            var type by remember { mutableStateOf(existing?.type) }
            var outstanding by remember { mutableStateOf(existing?.outstanding) }
            var emi by remember { mutableStateOf(existing?.emi) }
            var rate by remember { mutableStateOf(existing?.rate) }
            Ask("Type") { Choices(LOAN_TYPES.map { it to it }, type) { type = it } }
            NumberField("Still to repay (rupees)", outstanding, { outstanding = it })
            NumberField("Monthly EMI (rupees)", emi, { emi = it })
            DecimalField("Interest rate", rate, "9", { rate = it })
            TextButton(
                enabled = type != null && outstanding != null,
                onClick = {
                    val loan = ProfileLoan(type!!, outstanding!!, emi, rate)
                    vm.edit { p0 -> p0.copy(loans = if (index < p0.loans.size) p0.loans.mapIndexed { n, l -> if (n == index) loan else l } else p0.loans + loan) }
                    close()
                },
            ) { Text("Save loan") }
        }
    }
}

@Composable
private fun EntryDialog(title: String, onDismiss: () -> Unit, content: @Composable (close: () -> Unit) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) { content(onDismiss) }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
