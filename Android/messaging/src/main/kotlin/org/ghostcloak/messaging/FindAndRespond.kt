package org.ghostcloak.messaging

import java.text.Normalizer
import java.util.Locale

/** Operates only on the caller's already-authorized, expiring UI snapshot. No storage or network reads. */
object ConversationSearch {
    fun matches(messages: List<Message>, query: String, visible: Boolean): List<Int> {
        if (!visible || query.isBlank()) return emptyList()
        val needle = fold(query)
        return messages.indices.filter { index ->
            val message = messages[index]
            !message.policyEvent && message.viewOnceKind == null &&
                (message.attachment==null || message.attachment.caption!=null) &&
                fold(message.body).contains(needle)
        }
    }

    private fun fold(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).replace("ß", "ss").replace('ς', 'σ')
}

object ReplyPresentation {
    fun reference(message: Message): ReplyReference? {
        val id = message.envelopeId ?: return null
        return ReplyReference(id, when {
            message.viewOnceKind != null -> ReplyKind.VIEW_ONCE
            message.attachment != null -> ReplyKind.ATTACHMENT
            message.disappearingSeconds > 0 -> ReplyKind.DISAPPEARING
            else -> ReplyKind.TEXT
        })
    }

    fun preview(reference: ReplyReference, conversationId: String, messages: List<Message>): String {
        if (reference.kind == ReplyKind.VIEW_ONCE) return "View Once message"
        val original = messages.firstOrNull {
            it.conversationId == conversationId && it.envelopeId == reference.envelopeId && !it.policyEvent
        } ?: return if (reference.kind == ReplyKind.DISAPPEARING) "Original message expired" else "Original message unavailable"
        if (original.viewOnceKind != null) return "View Once message"
        if (original.attachment != null || reference.kind == ReplyKind.ATTACHMENT)
            return original.attachment?.caption?.take(100)?.replace('\n',' ') ?: "Attachment"
        return original.body.take(100).replace('\n', ' ')
    }
}
