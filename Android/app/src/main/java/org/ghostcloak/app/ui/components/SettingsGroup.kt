package org.ghostcloak.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text(title,Modifier.padding(start=4.dp),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement=Arrangement.spacedBy(14.dp),content=content)
        }
    }
}
@Composable fun DetailRow(icon: Glyph, title: String, body: String) {
    Row(horizontalArrangement=Arrangement.spacedBy(14.dp),verticalAlignment=Alignment.Top) {
        AppIcon(icon,tint=MaterialTheme.colorScheme.primary)
        Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text(title,style=MaterialTheme.typography.titleMedium)
            Text(body,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
