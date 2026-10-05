package org.ghostcloak.messaging

import kotlinx.serialization.Serializable
import org.ghostcloak.identity.RemoteIdentityStatus
import org.ghostcloak.identity.SessionLifecycle
import org.ghostcloak.protocol.GhostCloakIds

@Serializable
data class Contact(val contactId: String, val publicUserId: String, val displayName: String,
    val remoteDeviceId: String, val blocked: Boolean = false, val request: Boolean = false,
    val ghostCloakId: String? = null, val localAlias: String? = null,
    val pinned: Boolean = false, val archived: Boolean = false, val muted: Boolean = false) {
    val visibleName: String get() = if (request) ghostCloakId?.let(GhostCloakIds::display) ?: "Message request"
        else localAlias ?: displayName.takeIf { it.isNotBlank() } ?: ghostCloakId?.let(GhostCloakIds::display) ?: "Unknown contact"
    val blockedLabel: String get() = if (request) localAlias ?: ghostCloakId?.let(GhostCloakIds::display) ?: visibleName
        else localAlias ?: displayName
    override fun toString() = "Contact(redacted)"
}
data class ContactStatus(val contact: Contact, val identity: RemoteIdentityStatus?, val session: SessionLifecycle?,
    val sharedAbout:String?=null, val sharedPhoto:ByteArray?=null)
@Serializable enum class Direction { INCOMING, OUTGOING }
@Serializable enum class MessageState { PENDING, ENCRYPTED, SENT_TO_TRANSPORT, DELIVERED_LOCAL_SIMULATION, FAILED, SERVER_ACCEPTED, RECEIVED, DELIVERED, EXPIRED_UNDELIVERED }
@Serializable enum class ViewOnceKind { TEXT, PHOTO }
@Serializable enum class ViewOnceState { AVAILABLE, REVEALING, CONSUMED }
@Serializable enum class ReplyKind { TEXT, ATTACHMENT, VIEW_ONCE, DISAPPEARING }
@Serializable enum class DeleteRequestStatus { PENDING, SENT, FAILED }
@Serializable enum class EditRequestStatus { PENDING, SENT, FAILED }
/** An encrypted-envelope identifier, scoped to the current conversation. Never a server lookup key. */
@Serializable data class ReplyReference(val envelopeId: String, val kind: ReplyKind)
@Serializable data class ReactionRecord(val sequence: Long, val emoji: String?)
data class ReactionBadge(val emoji: String, val mine: Boolean)
@Serializable
data class Message(val localId: String, val conversationId: String, val direction: Direction,
    val body: String, val timestamp: Long, val state: MessageState, val envelopeId: String? = null,
    val disappearingSeconds: Int = 0, val expiry: ExpiryDeadline? = null, val policyEvent: Boolean = false,
    val viewOnceKind: ViewOnceKind? = null, val viewOnceState: ViewOnceState? = null,
    @kotlinx.serialization.Transient val attachment: AttachmentSummary? = null,
    val replyTo: ReplyReference? = null,
    val deleted: Boolean = false, val deleteStatus: DeleteRequestStatus? = null,
    val deleteSubmissionId: String? = null,
    val editRevision: Long = 0, val editStatus: EditRequestStatus? = null,
    val editSubmissionId: String? = null,
    @kotlinx.serialization.Transient val reactions: List<ReactionBadge> = emptyList()) {
    val activeExpiry: ExpiryDeadline? get() = if (direction == Direction.OUTGOING && state != MessageState.DELIVERED) null else expiry
    override fun toString() = "Message(redacted)"
}

/** Presentation only; never contains a blob capability, key, or remote identifier. */
data class AttachmentSummary(val photo: Boolean, val filename: String, val bytes: Long, val supported: Boolean = true,
    val kind:org.ghostcloak.attachments.AttachmentKind?=null,val durationMillis:Long?=null,
    val caption:String?=null) {
    override fun toString() = "AttachmentSummary(redacted)"
}
enum class AppError { INVALID_DISPLAY_NAME, INVALID_CARD, DUPLICATE_CONTACT, AMBIGUOUS_IDENTITY, EMPTY_MESSAGE,
    MESSAGE_TOO_LARGE, INVALID_TEXT, CONTACT_UNAVAILABLE, BLOCKED, LOCAL_CAPACITY, FRESH_CARD_REQUIRED }
class AppFailure(val error: AppError) : RuntimeException(error.name)

object TextRules {
    fun displayName(value: String) {
        val bytes = value.encodeToByteArray()
        if (value.isBlank() || value != value.trim() || value.codePointCount(0,value.length) !in 1..32 ||
            bytes.size > 128 || bytes.decodeToString() != value || value.any {Character.isISOControl(it)})
            throw AppFailure(AppError.INVALID_DISPLAY_NAME)
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
