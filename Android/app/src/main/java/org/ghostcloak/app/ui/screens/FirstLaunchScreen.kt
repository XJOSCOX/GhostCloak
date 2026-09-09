package org.ghostcloak.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.ghostcloak.app.application.AppState
import org.ghostcloak.app.ui.components.*

@Composable fun FirstLaunchScreen(state: AppState, create: (String) -> Unit) {
    // Draft text deliberately never enters saved-instance state or plaintext disk storage.
    var username by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp).imePadding(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Wordmark()
        Spacer(Modifier.height(28.dp))
        BrandMark(Modifier.size(88.dp))
        SectionLabel("YOUR LOCAL IDENTITY")
        Text("Make it yours.", style = MaterialTheme.typography.displaySmall)
        Text("A private space starts with a name. No phone number. No email.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(value = username, onValueChange = { if (it.length <= 32) username = it }, label = { Text("Username") },
            singleLine = true, modifier = Modifier.fillMaxWidth(), supportingText = { Text("1–32 letters, numbers or underscores") })
        FullButton("Create local identity", !state.loading && state.ready && username.isNotBlank()) { create(username) }
        ErrorNotice(state.error)
        InfoPanel("Your name is a label", "Your cryptographic identity is generated independently. You can change your username later.")
        Text("Local prototype · No network messaging\nExperimental. Not independently audited.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
