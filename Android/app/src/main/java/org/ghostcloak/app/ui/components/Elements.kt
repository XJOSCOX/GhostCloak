package org.ghostcloak.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.ghostcloak.app.ui.theme.GhostDimensions
import org.ghostcloak.identity.IdentityTrustState
import org.ghostcloak.app.ui.theme.avatarColors

@Composable fun Wordmark() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GhostDimensions.controlGap)) {
        BrandMark(Modifier.size(GhostDimensions.featureIcon)); Text("ghost cloak", style = MaterialTheme.typography.titleMedium)
    }
}
@Composable fun SectionLabel(text: String) { Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
@Composable fun Avatar(name: String, modifier: Modifier = Modifier) {
    val (background, foreground) = avatarColors()
    Surface(modifier.size(GhostDimensions.avatar), shape = CircleShape, color = background) {
        Box(contentAlignment = Alignment.Center) { Text(name.take(1).uppercase(), color = foreground, style = MaterialTheme.typography.titleLarge) }
    }
}
@Composable fun TrustBadge(state: IdentityTrustState?) {
    val text = when (state) { IdentityTrustState.VERIFIED -> "✓ Verified"; IdentityTrustState.CHANGED -> "! Identity changed"; else -> "• Unverified" }
    val color = when (state) { IdentityTrustState.VERIFIED -> MaterialTheme.colorScheme.primary; IdentityTrustState.CHANGED -> MaterialTheme.colorScheme.error; else -> MaterialTheme.colorScheme.onSurfaceVariant }
    Text(text, color = color, style = MaterialTheme.typography.labelLarge)
}
@Composable fun InfoPanel(title: String, body: String, warning: Boolean = false, urgent: Boolean = false) {
    Surface(shape = MaterialTheme.shapes.medium, border = if (urgent) BorderStroke(GhostDimensions.micro, MaterialTheme.colorScheme.error) else null,
        color = if (warning) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(GhostDimensions.regular), verticalArrangement = Arrangement.spacedBy(GhostDimensions.detail)) {
            Text(title, style = if (urgent) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.labelLarge,
                color = if (warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
@Composable fun FullButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = GhostDimensions.buttonHeight)) { Text(text) }
}
@Composable fun ErrorNotice(message: String?, important: Boolean = false) {
    var dismissed by remember(message, important) { mutableStateOf(false) }
    if (message == null) return
    if (important) {
        InfoPanel("Action needed", message, warning = true)
    } else if (!dismissed) {
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant) {
            Row(Modifier.fillMaxWidth().padding(start = GhostDimensions.regular), verticalAlignment = Alignment.CenterVertically) {
                Text(message, Modifier.weight(1f).padding(vertical = GhostDimensions.compact),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HeaderAction(Glyph.CLOSE, "Dismiss notice") { dismissed = true }
            }
        }
    }
}
