package com.personal.financecollector.ui

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.personal.financecollector.collector.NotificationCollector
import com.personal.financecollector.export.GmailAuth
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private val adminViewModel: AdminViewModel by viewModels()

    private val smsPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            viewModel.refreshSystemState()
            viewModel.ensureSmsScan()
        }

    private val gmailConsent =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            try {
                Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(result.data)
                viewModel.gmailConnected(true)
            } catch (e: ApiException) {
                viewModel.gmailConnected(false, "Gmail sign-in was not completed (code ${e.statusCode})")
            }
        }

    private var pendingCsv: String? = null

    /** Bumped whenever the notification permission may have changed, so screens re-check it. */
    private var notificationsVersion by mutableIntStateOf(0)

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            notificationsVersion++
            if (!com.personal.financecollector.alerts.Alerts.notificationsAllowed(this)) {
                // Denied for good: the only way left is the system settings page.
                startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            }
        }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        else startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
    }

    private val csvLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
            val text = pendingCsv
            pendingCsv = null
            if (uri == null || text == null) return@registerForActivityResult
            try {
                contentResolver.openOutputStream(uri)?.use { out ->
                    // The byte order mark makes Excel read the rupee-era names and symbols correctly.
                    out.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
                    out.write(text.toByteArray(Charsets.UTF_8))
                }
                Toast.makeText(this, "Exported", Toast.LENGTH_SHORT).show()
            } catch (e: java.io.IOException) {
                Toast.makeText(this, "Could not save the file", Toast.LENGTH_LONG).show()
            }
        }

    private fun exportCsv(list: List<LedgerTxn>) {
        pendingCsv = CsvExport.build(list)
        csvLauncher.launch("transactions-${java.time.LocalDate.now(LedgerBuilder.IST)}.csv")
    }

    private fun connectGmail() {
        Identity.getAuthorizationClient(this).authorize(GmailAuth.request)
            .addOnSuccessListener { r ->
                val pending = r.pendingIntent
                if (r.hasResolution() && pending != null) {
                    gmailConsent.launch(IntentSenderRequest.Builder(pending.intentSender).build())
                } else {
                    viewModel.gmailConnected(true)
                }
            }
            .addOnFailureListener {
                val code = (it as? ApiException)?.statusCode
                viewModel.showExportMessage("Gmail sign-in failed: ${it.javaClass.simpleName}${code?.let { c -> " (code $c)" }.orEmpty()}")
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val actions = HealthActions(
            openNotificationAccess = { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
            openAppSettings = {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            },
            openBatterySettings = { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) },
            reconnectListener = {
                NotificationListenerService.requestRebind(ComponentName(this, NotificationCollector::class.java))
                viewModel.refreshSystemState()
            },
            setPaused = viewModel::setPaused,
            deleteLocalData = viewModel::deleteLocalData,
            requestSmsPermission = {
                smsPermission.launch(arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS))
            },
            rescanSms = viewModel::rescanSms,
            pair = viewModel::pair,
            unpair = viewModel::unpair,
            syncNow = viewModel::syncNow,
            retryFailed = viewModel::retryFailed,
            resendAll = viewModel::resendAll,
            fetchAllSms = viewModel::fetchAllSms,
            connectGmail = ::connectGmail,
            fetchGmail = viewModel::fetchGmail,
            fetchNotifications = viewModel::fetchNotifications,
            checkGmail = viewModel::checkGmailNow,
            openSources = {},
            openAccounts = {},
            openPayees = {},
            openBudgets = {},
            openBills = {},
        )
        setContent {
            FinanceTheme {
                var tab by rememberSaveable { mutableIntStateOf(0) }
                var openKey by rememberSaveable { mutableStateOf<String?>(null) }
                var linkedCategory by rememberSaveable { mutableStateOf<String?>(null) }
                var linkedQuery by rememberSaveable { mutableStateOf<String?>(null) }
                var linkedAccount by rememberSaveable { mutableStateOf<String?>(null) }
                val budget by viewModel.budget.collectAsStateWithLifecycle()
                val health by viewModel.health.collectAsStateWithLifecycle()
                val notUnderstood by viewModel.notUnderstood.collectAsStateWithLifecycle()
                val payeeRules by viewModel.payeeRules.collectAsStateWithLifecycle()
                val ledger by viewModel.ledger.collectAsStateWithLifecycle()
                val sources by viewModel.sources.collectAsStateWithLifecycle()
                val smsSources by viewModel.smsSources.collectAsStateWithLifecycle()
                val apps by viewModel.installedApps.collectAsStateWithLifecycle()
                val pairingMessage by viewModel.pairingMessage.collectAsStateWithLifecycle()
                val export by viewModel.export.collectAsStateWithLifecycle()
                val onOpen: (LedgerTxn) -> Unit = { openKey = it.key }
                val screenActions = actions.copy(
                    openSources = { tab = 5 }, openAccounts = { tab = 6 }, openPayees = { tab = 7 }, openBudgets = { tab = 8 }, openBills = { tab = 9 },
                )
                val categoryBudgets by viewModel.categoryBudgets.collectAsStateWithLifecycle()
                val netWorthExcluded by viewModel.netWorthExcluded.collectAsStateWithLifecycle()
                BackHandler(enabled = tab >= 5) { tab = 4 }
                val ownEntries by viewModel.ownEntries.collectAsStateWithLifecycle()
                val detectedAccounts by viewModel.detectedAccounts.collectAsStateWithLifecycle()
                // Keep the home-screen widget in step with what the app is showing.
                LaunchedEffect(ledger, budget) {
                    com.personal.financecollector.widget.SpendWidget.save(
                        applicationContext, com.personal.financecollector.widget.SpendWidget.numbers(ledger, budget),
                    )
                }

                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(tab == 0, { tab = 0 }, { Icon(Icons.Default.Home, null) }, label = { Text("Overview", maxLines = 1, fontSize = 11.sp) })
                            NavigationBarItem(tab == 1, { tab = 1 }, { Icon(Icons.AutoMirrored.Filled.List, null) }, label = { Text("Transactions", maxLines = 1, fontSize = 11.sp) })
                            NavigationBarItem(tab == 2, { tab = 2 }, { Icon(Icons.Default.Notifications, null) }, label = { Text("Activity", maxLines = 1, fontSize = 11.sp) })
                            NavigationBarItem(tab == 3, { tab = 3 }, { Icon(Icons.Default.Person, null) }, label = { Text("Admin", maxLines = 1, fontSize = 11.sp) })
                            NavigationBarItem(tab >= 4, { tab = 4 }, { Icon(Icons.Default.CheckCircle, null) }, label = { Text("Health", maxLines = 1, fontSize = 11.sp) })
                        }
                    },
                ) { padding ->
                    val body = Modifier.padding(padding)
                    when (tab) {
                        0 -> OverviewScreen(
                            ledger, budget, viewModel::setBudget, onOpen,
                            onOpenCategory = { linkedCategory = it; linkedQuery = null; tab = 1 },
                            onOpenPayee = { linkedQuery = it; linkedCategory = null; tab = 1 },
                            onSeeAll = { tab = 1 },
                            categoryLimits = categoryBudgets,
                            netWorthExcluded = netWorthExcluded,
                            onOpenBudgets = { tab = 8 },
                            onOpenBills = { tab = 9 },
                            onOpenAccounts = { tab = 6 },
                            modifier = body,
                        )
                        1 -> TransactionsScreen(
                            ledger, onOpen, linkedCategory, linkedQuery, linkedAccount, { linkedCategory = null; linkedQuery = null; linkedAccount = null }, ::exportCsv, body,
                        )
                        2 -> EventsScreen(ledger, notUnderstood, onOpen, body)
                        3 -> AdminScreen(
                            vm = adminViewModel,
                            paired = health?.cloud?.paired == true,
                            budget = budget,
                            onSetBudget = viewModel::setBudget,
                            modifier = body,
                        )
                        4 -> HealthScreen(health, pairingMessage, export, screenActions, body)
                        5 -> SourcesScreen(
                            apps = apps,
                            appSources = sources,
                            smsSources = smsSources,
                            onToggleApp = viewModel::setAppEnabled,
                            onToggleSms = viewModel::setSmsSenderEnabled,
                            onAddSms = viewModel::addSmsSender,
                            modifier = body,
                        )
                        6 -> OwnAccountsScreen(
                            detected = detectedAccounts,
                            entries = ownEntries,
                            onAddAccount = viewModel::addOwnAccount,
                            onAddName = viewModel::addOwnName,
                            onRemove = viewModel::removeOwn,
                            onOpenAccount = { linkedAccount = it; linkedCategory = null; linkedQuery = null; tab = 1 },
                            balances = NetWorthCalculator.latestBalances(ledger),
                            netWorthExcluded = netWorthExcluded,
                            onToggleNetWorth = viewModel::setIncludedInNetWorth,
                            modifier = body,
                        )
                        7 -> PayeesScreen(ledger, payeeRules, viewModel::setPayeeRule, viewModel::removePayeeRule, body)
                        8 -> BudgetsScreen(ledger, categoryBudgets, viewModel::setCategoryBudget, ::requestNotifications, notificationsVersion, body)
                        else -> BillsScreen(ledger, ::requestNotifications, notificationsVersion, body)
                    }
                    ledger.firstOrNull { it.key == openKey }?.let {
                        TxnDetailSheet(
                            it, onDismiss = { openKey = null }, onSave = viewModel::saveLabel, onReset = viewModel::resetLabel,
                            onSplit = viewModel::saveSplit, onClearSplit = viewModel::clearSplit,
                            knownCategories = ledger.flatMap { l -> l.categoryParts.map { p -> p.first } }.distinct(),
                            onManagePayees = { tab = 7 },
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        notificationsVersion++
        viewModel.refreshSystemState()
        viewModel.ensureSmsScan()
    }
}
