package org.ghostcloak.app.ui.screens

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import org.ghostcloak.app.ui.theme.GhostDimensions
import org.ghostcloak.messaging.*
import org.ghostcloak.app.ui.theme.GhostEffects
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable fun MessageBubble(message: Message, onDelete: () -> Unit, onReply: (() -> Unit)? = null,
    onCopy: (() -> Unit)? = null, replyPreview: String? = null,
    content: (@Composable () -> Unit)? = null) {
    val outgoing = message.direction == Direction.OUTGOING
    var menu by remember { mutableStateOf(false) }
    val time = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(message.timestamp))
    Column(Modifier.fillMaxWidth(),horizontalAlignment=if(outgoing) Alignment.End else Alignment.Start) {
        Box {
            Surface(shape=RoundedCornerShape(GhostDimensions.sectionGap,GhostDimensions.sectionGap,if(outgoing) GhostDimensions.tiny else GhostDimensions.sectionGap,if(outgoing) GhostDimensions.sectionGap else GhostDimensions.tiny),
                color=if(outgoing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                contentColor=if(outgoing) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                modifier=Modifier.widthIn(max=GhostDimensions.previewWidth).combinedClickable(onClick={},onLongClick={menu=true})
                    .semantics { customActions=listOf(CustomAccessibilityAction("Delete from this device") { onDelete();true }) }) {
                Column(Modifier.padding(horizontal=GhostDimensions.fieldCorner,vertical=GhostDimensions.controlGap),verticalArrangement=Arrangement.spacedBy(GhostDimensions.tiny)) {
                    if(replyPreview!=null) Surface(color=LocalContentColor.current.copy(alpha=0.12f),
                        shape=RoundedCornerShape(GhostDimensions.tiny)) {
                        Text(replyPreview,Modifier.padding(GhostDimensions.controlGap),style=MaterialTheme.typography.labelMedium,
                            maxLines=2)
                    }
                    if(content!=null) content() else Text(message.body,style=MaterialTheme.typography.bodyLarge)
                    Text(time,Modifier.align(Alignment.End),style=MaterialTheme.typography.labelSmall,color=LocalContentColor.current.copy(alpha=GhostEffects.SecondaryContentAlpha))
                }
            }
            DropdownMenu(expanded=menu,onDismissRequest={menu=false}) {
                if(onReply!=null) DropdownMenuItem(text={Text("Reply")},onClick={menu=false;onReply()})
                if(onCopy!=null) DropdownMenuItem(text={Text("Copy")},onClick={menu=false;onCopy()})
                DropdownMenuItem(text={Text("Delete for me")},onClick={menu=false;onDelete()})
            }
        }
        if(outgoing) Text(when(message.state) {
            MessageState.PENDING -> "Pending"; MessageState.ENCRYPTED -> "Encrypted"
            MessageState.SENT_TO_TRANSPORT -> "Sent locally"; MessageState.DELIVERED_LOCAL_SIMULATION -> "Delivered locally"
            MessageState.FAILED -> "Not delivered"; MessageState.SERVER_ACCEPTED -> "Encrypting, Waiting…"
            MessageState.DELIVERED -> "Delivered"; MessageState.RECEIVED -> "Received"
            MessageState.EXPIRED_UNDELIVERED -> "Expired before delivery"
        },Modifier.padding(top=GhostDimensions.tiny,end=GhostDimensions.tiny),style=MaterialTheme.typography.labelSmall,
            color=if(message.state==MessageState.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
