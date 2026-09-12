package org.ghostcloak.app.application

import org.ghostcloak.messaging.*
import org.junit.Assert.*
import org.junit.Test

class ExpiredContentTest {
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
}
