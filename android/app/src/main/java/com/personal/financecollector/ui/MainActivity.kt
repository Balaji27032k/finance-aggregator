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
        )
        setContent {
            FinanceTheme {
                var tab by rememberSaveable { mutableIntStateOf(0) }
                var openKey by rememberSaveable { mutableStateOf<String?>(null) }
                val health by viewModel.health.collectAsStateWithLifecycle()
                val events by viewModel.events.collectAsStateWithLifecycle()
                val ledger by viewModel.ledger.collectAsStateWithLifecycle()
                val sources by viewModel.sources.collectAsStateWithLifecycle()
                val smsSources by viewModel.smsSources.collectAsStateWithLifecycle()
                val apps by viewModel.installedApps.collectAsStateWithLifecycle()
                val pairingMessage by viewModel.pairingMessage.collectAsStateWithLifecycle()
                val export by viewModel.export.collectAsStateWithLifecycle()
                val onOpen: (LedgerTxn) -> Unit = { openKey = it.key }

                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(tab == 0, { tab = 0 }, { Icon(Icons.Default.Home, null) }, label = { Text("Overview") })
                            NavigationBarItem(tab == 1, { tab = 1 }, { Icon(Icons.AutoMirrored.Filled.List, null) }, label = { Text("Transactions") })
                            NavigationBarItem(tab == 2, { tab = 2 }, { Icon(Icons.Default.Notifications, null) }, label = { Text("Activity") })
                            NavigationBarItem(tab == 3, { tab = 3 }, { Icon(Icons.Default.CheckCircle, null) }, label = { Text("Health") })
                        }
                    },
                ) { padding ->
                    val body = Modifier.padding(padding)
                    when (tab) {
                        0 -> OverviewScreen(ledger, onOpen, onSeeAll = { tab = 1 }, modifier = body)
                        1 -> TransactionsScreen(ledger, onOpen, body)
                        2 -> EventsScreen(events, body)
                        3 -> HealthScreen(health, pairingMessage, export, actions, body)
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
