package org.ghostcloak.app.ui.screens

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import org.ghostcloak.messaging.*
import org.ghostcloak.app.ui.theme.GhostEffects
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable fun MessageBubble(message: Message, onDelete: () -> Unit) {
    val outgoing = message.direction == Direction.OUTGOING
    var menu by remember { mutableStateOf(false) }
    val time = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(message.timestamp))
    Column(Modifier.fillMaxWidth(),horizontalAlignment=if(outgoing) Alignment.End else Alignment.Start) {
        Box {
            Surface(shape=RoundedCornerShape(18.dp,18.dp,if(outgoing) 4.dp else 18.dp,if(outgoing) 18.dp else 4.dp),
                color=if(outgoing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                contentColor=if(outgoing) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                modifier=Modifier.widthIn(max=300.dp).combinedClickable(onClick={menu=true},onLongClick={menu=true})
                    .semantics { customActions=listOf(CustomAccessibilityAction("Delete from this device") { onDelete();true }) }) {
                Column(Modifier.padding(horizontal=14.dp,vertical=10.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Text(message.body,style=MaterialTheme.typography.bodyLarge)
                    Text(time,Modifier.align(Alignment.End),style=MaterialTheme.typography.labelSmall,color=LocalContentColor.current.copy(alpha=GhostEffects.SecondaryContentAlpha))
                }
            }
            DropdownMenu(expanded=menu,onDismissRequest={menu=false}) {
                DropdownMenuItem(text={Text("Delete from this device")},onClick={menu=false;onDelete()})
            }
        }
        if(outgoing) Text(when(message.state) {
            MessageState.PENDING -> "Pending"; MessageState.ENCRYPTED -> "Encrypted"
            MessageState.SENT_TO_TRANSPORT -> "Sent locally"; MessageState.DELIVERED_LOCAL_SIMULATION -> "Delivered locally"
            MessageState.FAILED -> "Not delivered"; MessageState.SERVER_ACCEPTED -> "Queued on server"
            MessageState.DELIVERED -> "Delivered"; MessageState.RECEIVED -> "Received"
        },Modifier.padding(top=4.dp,end=4.dp),style=MaterialTheme.typography.labelSmall,
            color=if(message.state==MessageState.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
