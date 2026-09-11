package org.ghostcloak.messaging

import kotlinx.serialization.Serializable
import org.ghostcloak.identity.RemoteIdentityStatus
import org.ghostcloak.identity.SessionLifecycle

@Serializable
data class Contact(val contactId: String, val publicUserId: String, val displayName: String,
    val remoteDeviceId: String, val blocked: Boolean = false, val request: Boolean = false) {
    override fun toString() = "Contact(redacted)"
}
data class ContactStatus(val contact: Contact, val identity: RemoteIdentityStatus?, val session: SessionLifecycle?)
@Serializable enum class Direction { INCOMING, OUTGOING }
@Serializable enum class MessageState { PENDING, ENCRYPTED, SENT_TO_TRANSPORT, DELIVERED_LOCAL_SIMULATION, FAILED, SERVER_ACCEPTED, RECEIVED, DELIVERED }
@Serializable
data class Message(val localId: String, val conversationId: String, val direction: Direction,
    val body: String, val timestamp: Long, val state: MessageState, val envelopeId: String? = null) {
    override fun toString() = "Message(redacted)"
}
enum class AppError { INVALID_USERNAME, INVALID_CARD, DUPLICATE_CONTACT, AMBIGUOUS_IDENTITY, EMPTY_MESSAGE,
    MESSAGE_TOO_LARGE, INVALID_TEXT, CONTACT_UNAVAILABLE, BLOCKED, LOCAL_CAPACITY, FRESH_CARD_REQUIRED }
class AppFailure(val error: AppError) : RuntimeException(error.name)

object TextRules {
    fun username(value: String) {
        if (!value.matches(Regex("[A-Za-z0-9_]{1,32}"))) throw AppFailure(AppError.INVALID_USERNAME)
    }
    fun encode(value: String): ByteArray {
        if (value.isBlank()) throw AppFailure(AppError.EMPTY_MESSAGE)
        if (value.length > org.ghostcloak.protocol.EnvelopeCodec.MAX_BODY) throw AppFailure(AppError.MESSAGE_TOO_LARGE)
        val bytes = value.encodeToByteArray()
        if (bytes.decodeToString() != value) { bytes.fill(0); throw AppFailure(AppError.INVALID_TEXT) }
        if (bytes.size > org.ghostcloak.protocol.EnvelopeCodec.MAX_BODY) {
            bytes.fill(0); throw AppFailure(AppError.MESSAGE_TOO_LARGE)
        }
        return bytes
    }
}
