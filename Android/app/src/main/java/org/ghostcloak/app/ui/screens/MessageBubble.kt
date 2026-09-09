package org.ghostcloak.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import org.ghostcloak.messaging.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable fun MessageBubble(message: Message, onDelete: () -> Unit) {
    val outgoing = message.direction == Direction.OUTGOING
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (outgoing) Alignment.End else Alignment.Start) {
        Surface(shape = RoundedCornerShape(20.dp, 20.dp, if (outgoing) 6.dp else 20.dp, if (outgoing) 20.dp else 6.dp),
            color = if (outgoing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
            modifier = Modifier.widthIn(max = 310.dp)) {
            Text(message.body, Modifier.padding(horizontal = 18.dp, vertical = 14.dp), style = MaterialTheme.typography.bodyLarge)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
        Text("${DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(message.timestamp))} · ${when (message.state) {
            MessageState.PENDING -> "Pending"; MessageState.ENCRYPTED -> "Encrypted"; MessageState.SENT_TO_TRANSPORT -> "Sent to local transport"
            MessageState.DELIVERED_LOCAL_SIMULATION -> if (outgoing) "Delivered locally" else "Received locally"; MessageState.FAILED -> "Not delivered"
        }}", Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodyMedium,
            color = if (message.state == MessageState.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.semantics { contentDescription = "Message actions" }) { Text("···") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Delete from this device") }, onClick = { menu = false; onDelete() })
            }
        }
        }
    }
}
