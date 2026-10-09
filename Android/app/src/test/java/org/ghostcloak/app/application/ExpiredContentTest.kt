package org.ghostcloak.app.application

import org.ghostcloak.attachments.AttachmentKind
import org.ghostcloak.messaging.*
import org.junit.Assert.*
import org.junit.Test

class ExpiredContentTest {
    @Test fun staleLegacyQueuedDeadlineCannotHideOutgoingContent() {
        val message = Message("id", "peer", Direction.OUTGOING, "queued", 0, MessageState.SERVER_ACCEPTED,
            disappearingSeconds = 30, expiry = ExpiryDeadline(1, 1, 1))
        val visible = AppState(messages = listOf(message), previews = mapOf("peer" to message))
            .withoutExpired(ExpiryMoment(100_000, 100_000, 1))
        assertEquals(listOf(message), visible.messages); assertEquals(message, visible.previews["peer"])
    }
    @Test fun staleSnapshotNeverRendersExpiredPlaintextPreviewOrUnreadContribution() {
        val deadline = ExpiryDeadline(1000, 2000, 1)
        val message = Message("id", "contact", Direction.INCOMING, "secret", 1, MessageState.RECEIVED, expiry = deadline)
        val state = AppState(messages = listOf(message), previews = mapOf("contact" to message),
            unreadCount = 2, unreadByConversation = mapOf("contact" to 2), unreadExpiries = mapOf("contact" to listOf(deadline)))
        assertEquals(1, state.nextExpiryUiDelay(ExpiryMoment(999, 1999, 1)))
        assertEquals(1, state.withoutExpired(ExpiryMoment(999, 1999, 1)).messages.size)
        for (now in listOf(ExpiryMoment(1000, 100, 1), ExpiryMoment(0, 2000, 1))) {
            val visible = state.withoutExpired(now)
            assertTrue(visible.messages.isEmpty()); assertTrue(visible.previews.isEmpty()); assertEquals(1, visible.unreadCount)
        }
    }
    @Test fun staleGroupSnapshotScrubsExpiredTextAndMediaBeforeDisplay() {
        val deadline = ExpiryDeadline(1000, 2000, 1)
        val message = GroupChatMessage("group", "message", 1, "sender", false, "private text", 1,
            mediaKind = AttachmentKind.IMAGE, mediaCaption = "private caption",
            mediaFilename = "private.jpg", expiry = deadline, disappearingSeconds = 30,
            reactions = listOf(GroupReactionBadge("👍", 1, false)))
        val group = GroupMembershipTransport.Conversation("group", GroupLocalStatus.ACTIVE, 2,
            emptyMap(), listOf(message))
        val state = AppState(groups = listOf(group),unreadCount=1,
            groupUnreadByConversation=mapOf("group" to 1),
            groupUnreadExpiries=mapOf("group" to listOf(deadline)))
        assertEquals(1, state.nextExpiryUiDelay(ExpiryMoment(999, 1999, 1)))
        val expiredState=state.withoutExpired(ExpiryMoment(1000, 100, 1))
        val visible = expiredState.groups.single().messages.single()
        assertEquals(0,expiredState.unreadCount)
        assertEquals(0,expiredState.groupUnreadByConversation["group"])
        assertEquals(GroupExpiryState.EXPIRED, visible.expiryState)
        assertEquals("", visible.text)
        assertNull(visible.mediaCaption)
        assertNull(visible.mediaFilename)
        assertTrue(visible.reactions.isEmpty())
    }
}
