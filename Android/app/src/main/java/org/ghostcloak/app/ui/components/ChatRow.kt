package org.ghostcloak.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.ghostcloak.messaging.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable fun ChatRow(status: ContactStatus, last: Message?, open: (String) -> Unit) {
    val contact = status.contact
    Surface(onClick = { open(contact.remoteDeviceId) }, shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(vertical = 13.dp, horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Avatar(contact.displayName)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(contact.displayName, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    last?.let {
                        Text(DateTimeFormatter.ofPattern(if (Instant.ofEpochMilli(it.timestamp).atZone(ZoneId.systemDefault()).toLocalDate() == java.time.LocalDate.now()) "HH:mm" else "MMM d").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(it.timestamp)),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(when {
                    contact.blocked -> "Blocked on this device"
                    contact.request -> "Message request - Unverified"
                    last != null -> (if (last.direction == Direction.OUTGOING) "You: " else "") + last.body
                    else -> "Start an encrypted conversation"
                }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
                    color = if (contact.request) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
