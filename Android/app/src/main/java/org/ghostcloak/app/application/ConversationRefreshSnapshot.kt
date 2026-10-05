package org.ghostcloak.app.application

import org.ghostcloak.messaging.ContactStatus
import org.ghostcloak.messaging.Message

/** One fresh service read per visible contact; only the selected list survives the refresh. */
internal class ConversationRefreshSnapshot(
    val previews: Map<String, Message>,
    val messages: List<Message>,
    val selectedId: String?
) {
    override fun toString() = "ConversationRefreshSnapshot(redacted)"
    // A selection change during suspension must never publish the previous chat's messages.
    fun messagesForSelection(currentId: String?): List<Message> =
        if (currentId == selectedId) messages else emptyList()
}

internal suspend fun loadConversationRefreshSnapshot(
    contacts: List<ContactStatus>,
    selected: String?,
    messagesForUi: suspend (String) -> List<Message>
): ConversationRefreshSnapshot {
    val previews = linkedMapOf<String, Message>()
    var selectedMessages: List<Message> = emptyList()
    contacts.forEach { status ->
        val id = status.contact.remoteDeviceId
        val messages = messagesForUi(id)
        messages.lastOrNull()?.let { previews[id] = it }
        if (id == selected) selectedMessages = messages
    }
    return ConversationRefreshSnapshot(previews, selectedMessages, selected)
}
