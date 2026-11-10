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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.personal.financecollector.collector.NotificationCollector

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
        )
        setContent {
            MaterialTheme {
                var tab by rememberSaveable { mutableIntStateOf(0) }
                val health by viewModel.health.collectAsStateWithLifecycle()
                val events by viewModel.events.collectAsStateWithLifecycle()
                val sources by viewModel.sources.collectAsStateWithLifecycle()
                val smsSources by viewModel.smsSources.collectAsStateWithLifecycle()
                val apps by viewModel.installedApps.collectAsStateWithLifecycle()
                val pairingMessage by viewModel.pairingMessage.collectAsStateWithLifecycle()

                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(tab == 0, { tab = 0 }, { Icon(Icons.Default.Home, null) }, label = { Text("Health") })
                            NavigationBarItem(tab == 1, { tab = 1 }, { Icon(Icons.AutoMirrored.Filled.List, null) }, label = { Text("Events") })
                            NavigationBarItem(tab == 2, { tab = 2 }, { Icon(Icons.Default.Settings, null) }, label = { Text("Sources") })
                        }
                    },
                ) { padding ->
                    val body = Modifier.padding(padding)
                    when (tab) {
                        0 -> HealthScreen(health, pairingMessage, actions, body)
                        1 -> EventsScreen(events, body)
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
