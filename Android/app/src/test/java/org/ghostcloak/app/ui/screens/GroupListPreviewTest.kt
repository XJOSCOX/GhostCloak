package org.ghostcloak.app.ui.screens

import org.ghostcloak.attachments.AttachmentKind
import org.ghostcloak.messaging.GroupChatMessage
import org.ghostcloak.messaging.GroupExpiryState
import org.ghostcloak.messaging.GroupLocalStatus
import org.ghostcloak.messaging.GroupModerationState
import org.junit.Assert.assertEquals
import org.junit.Test

class GroupListPreviewTest {
    private val message=GroupChatMessage("group","message",1,"member",false,"private text",1)

    @Test fun terminalStatesNeverPreviewRetainedPlaintextOrMediaDetails() {
        assertEquals("Message removed by an admin",groupListPreview(message.copy(
            moderationState=GroupModerationState.REMOVED_BY_ADMIN,
            mediaKind=AttachmentKind.DOCUMENT,mediaFilename="private.pdf")))
        assertEquals("This message was deleted",groupListPreview(message.copy(
            moderationState=GroupModerationState.DELETED_BY_SENDER)))
        assertEquals("Message expired",groupListPreview(message.copy(
            expiryState=GroupExpiryState.EXPIRED,mediaKind=AttachmentKind.IMAGE)))
    }

    @Test fun liveMediaUsesOnlyGenericPreview() {
        assertEquals("Photo",groupListPreview(message.copy(mediaKind=AttachmentKind.IMAGE)))
        assertEquals("Document",groupListPreview(message.copy(mediaKind=AttachmentKind.DOCUMENT,
            mediaFilename="private.pdf")))
        assertEquals("Voice note",groupListPreview(message.copy(mediaKind=AttachmentKind.VOICE_NOTE)))
        assertEquals("private text",groupListPreview(message))
    }

    @Test fun replyPreviewUsesCurrentTerminalStateAndNeverRetainsCaption() {
        assertEquals("Original message unavailable",groupReplyPreview(null))
        assertEquals("Original message deleted",groupReplyPreview(message.copy(
            moderationState=GroupModerationState.DELETED_BY_SENDER)))
        assertEquals("Original message expired",groupReplyPreview(message.copy(
            expiryState=GroupExpiryState.EXPIRED)))
        assertEquals("Photo",groupReplyPreview(message.copy(mediaKind=AttachmentKind.IMAGE,
            mediaCaption="private caption")))
        assertEquals("private text",groupReplyPreview(message))
    }

    @Test fun memberCountAndTerminalGroupStatusUseProductLanguage() {
        assertEquals("1 member",memberCountLabel(1))
        assertEquals("5 members",memberCountLabel(5))
        assertEquals("Group state conflict",groupListStatus(GroupLocalStatus.FORKED))
        assertEquals("Read-only",groupListStatus(GroupLocalStatus.REMOVED))
    }
}
