package org.ghostcloak.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.ghostcloak.app.foundation.FoundationService
import org.ghostcloak.app.ui.theme.GhostCloakTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GhostCloakTheme {
                var status by remember { mutableStateOf("Opening local identity…") }
                LaunchedEffect(Unit) { status = FoundationService(applicationContext).status() }
                Scaffold { padding ->
                    Column(Modifier.padding(padding).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("Ghost Cloak", style = MaterialTheme.typography.headlineLarge)
                        Text("Phase 1A · Identity foundation", style = MaterialTheme.typography.titleMedium)
                        Text("Experimental. No independent security audit. Do not use for high-risk communications.")
                        Text(status)
                        Text("The Alice / Bob / Charlie developer harness runs through Gradle. This build has no chat or network service.")
                    }
                }
            }
        }
    }
}
