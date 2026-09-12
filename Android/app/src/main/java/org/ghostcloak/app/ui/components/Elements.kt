package org.ghostcloak.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.ghostcloak.identity.IdentityTrustState
import org.ghostcloak.app.ui.theme.avatarColors

@Composable fun Wordmark() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        BrandMark(Modifier.size(30.dp)); Text("ghost cloak", style = MaterialTheme.typography.titleMedium)
    }
}
@Composable fun SectionLabel(text: String) { Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
@Composable fun Avatar(name: String, modifier: Modifier = Modifier) {
    val (background, foreground) = avatarColors()
    Surface(modifier.size(52.dp), shape = CircleShape, color = background) {
        Box(contentAlignment = Alignment.Center) { Text(name.take(1).uppercase(), color = foreground, style = MaterialTheme.typography.titleLarge) }
    }
}
@Composable fun TrustBadge(state: IdentityTrustState?) {
    val text = when (state) { IdentityTrustState.VERIFIED -> "✓ Verified"; IdentityTrustState.CHANGED -> "! Identity changed"; else -> "• Unverified" }
    val color = when (state) { IdentityTrustState.VERIFIED -> MaterialTheme.colorScheme.primary; IdentityTrustState.CHANGED -> MaterialTheme.colorScheme.error; else -> MaterialTheme.colorScheme.onSurfaceVariant }
    Text(text, color = color, style = MaterialTheme.typography.labelLarge)
}
@Composable fun InfoPanel(title: String, body: String, warning: Boolean = false, urgent: Boolean = false) {
    Surface(shape = MaterialTheme.shapes.medium, border = if (urgent) BorderStroke(2.dp, MaterialTheme.colorScheme.error) else null,
        color = if (warning) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(title, style = if (urgent) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.labelLarge,
                color = if (warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
@Composable fun ScreenHeader(title: String, subtitle: String, back: (() -> Unit)? = null) {
    if (back != null) IconButton(onClick = back) { AppIcon(Glyph.BACK, "Back") }
    Text(title, style = MaterialTheme.typography.headlineLarge)
    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
@Composable fun FullButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)) { Text(text) }
}
@Composable fun ErrorNotice(message: String?) {
    if (message != null) InfoPanel("Unable to continue", message, true)
}
