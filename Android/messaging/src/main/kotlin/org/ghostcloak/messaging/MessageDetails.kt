package org.ghostcloak.messaging

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Local presentation only. Never include envelope, routing, submission or device identifiers. */
object MessageDetails {
    fun fields(message:Message):List<Pair<String,String>> = buildList {
        add("Direction" to if(message.direction==Direction.OUTGOING) "Sent" else "Received")
        add("Local time" to DateTimeFormatter.ofPattern("MMM d, yyyy · HH:mm")
            .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(message.timestamp)))
        add("Status" to when(message.state) {
            MessageState.PENDING,MessageState.ENCRYPTED,MessageState.SENT_TO_TRANSPORT -> "Pending"
            MessageState.FAILED -> "Not delivered · this attempt cannot be retried safely"
            MessageState.SERVER_ACCEPTED -> "Waiting for delivery"
            MessageState.DELIVERED,MessageState.DELIVERED_LOCAL_SIMULATION -> "Delivered"
            MessageState.RECEIVED -> "Received"
            MessageState.EXPIRED_UNDELIVERED -> "Expired before delivery"
            MessageState.SUBMISSION_EXPIRED -> "Send attempt expired · create a new message if needed"
            MessageState.STATUS_UNAVAILABLE -> "Delivery status unavailable"
        })
        add("Disappearing" to if(message.disappearingSeconds>0)
            "On · ${DisappearingTimer.from(message.disappearingSeconds).label}" else "Off")
        message.attachment?.let { add("Attachment" to when(it.kind) {
            org.ghostcloak.attachments.AttachmentKind.IMAGE -> "Photo"
            org.ghostcloak.attachments.AttachmentKind.VOICE_NOTE -> "Voice note"
            org.ghostcloak.attachments.AttachmentKind.DOCUMENT -> "Document"
            else -> "Attachment"
        }) }
        add("Reply" to if(message.replyTo!=null) "Yes" else "No")
        add("Reactions" to message.reactions.size.toString())
        if(message.editRevision>0) add("Edit" to when(message.editStatus) {
            EditRequestStatus.PENDING -> "Pending"
            EditRequestStatus.FAILED -> "Not sent"
            EditRequestStatus.SENT, null -> "Edited"
        })
    }
}
