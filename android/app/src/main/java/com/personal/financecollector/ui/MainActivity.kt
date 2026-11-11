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

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startTestNotification()
            else Toast.makeText(this, "Allow notifications to send a test alert", Toast.LENGTH_LONG).show()
        }

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
            sendTestNotification = ::requestTestNotification,
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
        )
        setContent {
            FinanceTheme {
                var tab by rememberSaveable { mutableIntStateOf(0) }
                var openKey by rememberSaveable { mutableStateOf<String?>(null) }
                var linkedCategory by rememberSaveable { mutableStateOf<String?>(null) }
                val budget by viewModel.budget.collectAsStateWithLifecycle()
                val health by viewModel.health.collectAsStateWithLifecycle()
                val events by viewModel.events.collectAsStateWithLifecycle()
                val uploadProblems by viewModel.uploadProblems.collectAsStateWithLifecycle()
                val ledger by viewModel.ledger.collectAsStateWithLifecycle()
                val sources by viewModel.sources.collectAsStateWithLifecycle()
                val smsSources by viewModel.smsSources.collectAsStateWithLifecycle()
                val apps by viewModel.installedApps.collectAsStateWithLifecycle()
                val pairingMessage by viewModel.pairingMessage.collectAsStateWithLifecycle()
                val export by viewModel.export.collectAsStateWithLifecycle()
                val onOpen: (LedgerTxn) -> Unit = { openKey = it.key }
                val screenActions = actions.copy(openSources = { tab = 5 })
                BackHandler(enabled = tab == 5) { tab = 4 }

                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(tab == 0, { tab = 0 }, { Icon(Icons.Default.Home, null) }, label = { Text("Overview", maxLines = 1, fontSize = 11.sp) })
                            NavigationBarItem(tab == 1, { tab = 1 }, { Icon(Icons.AutoMirrored.Filled.List, null) }, label = { Text("Transactions", maxLines = 1, fontSize = 11.sp) })
                            NavigationBarItem(tab == 2, { tab = 2 }, { Icon(Icons.Default.Notifications, null) }, label = { Text("Activity", maxLines = 1, fontSize = 11.sp) })
                            NavigationBarItem(tab == 3, { tab = 3 }, { Icon(Icons.Default.Person, null) }, label = { Text("Admin", maxLines = 1, fontSize = 11.sp) })
                            NavigationBarItem(tab == 4 || tab == 5, { tab = 4 }, { Icon(Icons.Default.CheckCircle, null) }, label = { Text("Health", maxLines = 1, fontSize = 11.sp) })
                        }
                    },
                ) { padding ->
                    val body = Modifier.padding(padding)
                    when (tab) {
                        0 -> OverviewScreen(
                            ledger, budget, viewModel::setBudget, onOpen,
                            onOpenCategory = { linkedCategory = it; tab = 1 },
                            onSeeAll = { tab = 1 },
                            modifier = body,
                        )
                        1 -> TransactionsScreen(ledger, onOpen, linkedCategory, { linkedCategory = null }, body)
                        2 -> EventsScreen(events, uploadProblems, body)
                        3 -> AdminScreen(
                            backendUrl = health?.cloud?.takeIf { it.paired }?.backendUrl,
                            onOpen = ::openDashboard,
                            onCopy = ::copyLink,
                            modifier = body,
                        )
                        4 -> HealthScreen(health, pairingMessage, export, screenActions, body)
                        else -> SourcesScreen(
                            apps = apps,
                            appSources = sources,
                            smsSources = smsSources,
                            onToggleApp = viewModel::setAppEnabled,
                            onToggleSms = viewModel::setSmsSenderEnabled,
                            onAddSms = viewModel::addSmsSender,
                            modifier = body,
                        )
                    }
                    ledger.firstOrNull { it.key == openKey }?.let { TxnDetailSheet(it, onDismiss = { openKey = null }) }
                }
            }
        }
    }

    private fun openDashboard(url: String) {
        val uri = Uri.parse(url)
        if (uri.scheme != "https") return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: android.content.ActivityNotFoundException) {
            Toast.makeText(this, "No browser found to open the dashboard", Toast.LENGTH_LONG).show()
        }
    }

    private fun copyLink(url: String) {
        val clipboard = getSystemService(android.content.ClipboardManager::class.java)
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Dashboard link", url))
        Toast.makeText(this, "Link copied", Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshSystemState()
        viewModel.ensureSmsScan()
    }

    private fun requestTestNotification() {
        val needsPermission = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsPermission) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else startTestNotification()
    }

    private fun startTestNotification() = viewModel.prepareTestSource { postTestNotification() }

    private fun postTestNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(TEST_CHANNEL, "Test alerts", NotificationManager.IMPORTANCE_DEFAULT))
        val notification = NotificationCompat.Builder(this, TEST_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Test bank alert")
            .setContentText("INR 500.00 credited to A/c XX0000 via UPI. Ref 000000000000. (test)")
            .setAutoCancel(true)
            .build()
        manager.notify(System.currentTimeMillis().toInt(), notification)
    }

    private companion object {
        const val TEST_CHANNEL = "test_alerts"
    }
}
