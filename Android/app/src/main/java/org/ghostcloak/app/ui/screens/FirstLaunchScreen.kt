package org.ghostcloak.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.foundation.shape.RoundedCornerShape
import org.ghostcloak.app.ui.theme.GhostDimensions
import org.ghostcloak.app.application.AppState
import org.ghostcloak.app.ui.components.*
import org.ghostcloak.app.ui.privacy.noSensitiveCopyCut

@Composable fun FirstLaunchScreen(state: AppState, create: (String) -> Unit) {
    // Draft text deliberately never enters saved-instance state or plaintext disk storage.
    var displayName by remember { mutableStateOf("") }
    PageContent("Ghost Cloak") {
        Surface(shape=RoundedCornerShape(GhostDimensions.heroInset),color=MaterialTheme.colorScheme.primaryContainer,modifier=Modifier.size(GhostDimensions.heroSize)) {
            Box(contentAlignment=Alignment.Center) {BrandMark(Modifier.size(GhostDimensions.brandHero))}
        }
        Text("Conversations.\nJust between you.", style = MaterialTheme.typography.displaySmall)
        Text("Choose a display name. Other people may use the same name. No phone number or email.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(value = displayName, onValueChange = { if (it.length <= 32) displayName = it }, label = { Text("Display name") },
            singleLine = true, modifier = Modifier.fillMaxWidth().noSensitiveCopyCut(), supportingText = { Text("1–32 characters. Names are not unique and are shared only in encrypted messages.") })
        FullButton(if (state.networkConfigured) "Create identity" else "Create local identity", !state.loading && state.ready && displayName.isNotBlank()) { create(displayName) }
        ErrorNotice(state.error, important = state.errorImportant)
        DetailRow(Glyph.SHIELD,"Your keys stay with you", "Created and encrypted on this device. Keep your identity if a connection fails; you can retry.")
        Text(if (state.networkConfigured) "Encrypted messaging via Ghost Cloak staging\nExperimental. Not independently audited." else "Local prototype · No network messaging\nExperimental. Not independently audited.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
