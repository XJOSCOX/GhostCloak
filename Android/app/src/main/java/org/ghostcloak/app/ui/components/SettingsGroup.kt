package org.ghostcloak.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.ghostcloak.app.ui.theme.GhostDimensions

@Composable fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement=Arrangement.spacedBy(GhostDimensions.controlGap)) {
        Text(title,Modifier.padding(start=GhostDimensions.tiny),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().padding(GhostDimensions.sectionGap),verticalArrangement=Arrangement.spacedBy(GhostDimensions.fieldCorner),content=content)
        }
    }
}
@Composable fun DetailRow(icon: Glyph, title: String, body: String) {
    Row(horizontalArrangement=Arrangement.spacedBy(GhostDimensions.fieldCorner),verticalAlignment=Alignment.Top) {
        AppIcon(icon,tint=MaterialTheme.colorScheme.primary)
        Column(verticalArrangement=Arrangement.spacedBy(GhostDimensions.tiny)) {
            Text(title,style=MaterialTheme.typography.titleMedium)
            Text(body,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
