package com.personal.financecollector.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** The full dashboard runs in the browser; this tab is the shortcut to it. The admin password is typed there, never here. */
@Composable
fun AdminScreen(backendUrl: String?, onOpen: (String) -> Unit, onCopy: (String) -> Unit, modifier: Modifier = Modifier) {
    val secure = backendUrl != null && backendUrl.startsWith("https://")
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Admin", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 8.dp))
        Panel(container = MaterialTheme.colorScheme.primaryContainer) {
            Text("Web dashboard", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text("Sign in with the admin user name and password you set on the server to see everything from all your sources: charts, insights, search, CSV export and data health.")
            if (secure) {
                Text(backendUrl!!, style = MaterialTheme.typography.bodySmall)
                Button(onClick = { onOpen(backendUrl) }, modifier = Modifier.fillMaxWidth()) { Text("Open dashboard") }
                OutlinedButton(onClick = { onCopy(backendUrl) }, modifier = Modifier.fillMaxWidth()) { Text("Copy link") }
            } else {
                Text(
                    "This phone is not paired with a server yet. Pair it in the Health tab, then come back here.",
                    fontWeight = FontWeight.Medium,
                )
            }
        }
        Panel {
            Text("Good to know", style = MaterialTheme.typography.titleMedium)
            Text("The first load can take about a minute while the free server wakes up.", style = MaterialTheme.typography.bodyMedium)
            Text("Your admin password is entered in the browser only. This app never sees or stores it.", style = MaterialTheme.typography.bodyMedium)
            Text("Sessions end after 12 hours or when the server restarts.", style = MaterialTheme.typography.bodyMedium)
        }
    }
}
