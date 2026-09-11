package org.ghostcloak.messaging

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.ghostcloak.crypto.*
import org.ghostcloak.identity.*
import org.ghostcloak.protocol.*
import org.ghostcloak.transport.*

/** Serialized application boundary. UI never receives the engine, records, or transport. */
class ConversationService(private val engine: SecureSessionEngine, private val repository: LocalRepository) {
    private val mutex = Mutex()
    private var identity: DeviceIdentity? = null
    private var exported: String? = null
    private var transport: EncryptedMessageTransport? = null
    private suspend fun <T> action(block: suspend () -> T): T = withContext(Dispatchers.IO) { mutex.withLock { block() } }
    suspend fun open(): DeviceIdentity? = action {
        if (repository.hasIdentity()) engine.createIdentity("Local").also { identity = it } else null
    }
    suspend fun create(username: String): DeviceIdentity = action {
        TextRules.username(username)
        engine.createIdentity(username).also { identity = it }
    }
    suspend fun rename(username: String): DeviceIdentity = action {
        TextRules.username(username)
        engine.renameLocalUser(username).also { identity = it; exported = null }
    }
    suspend fun attach(value: EncryptedMessageTransport) = action { transport = value }
    suspend fun exportCard(fresh: Boolean = false): String = action {
        if (fresh) exported = null
        exported ?: run {
            val me = identity ?: throw AppFailure(AppError.CONTACT_UNAVAILABLE)
            val b = engine.publicBundle()
            ContactCardCodec.encode(ContactCard(1, me.userId, me.username, me.deviceId, b.registrationId,
                b.identity, b.preKeyId, b.preKey, b.signedId, b.signedKey, b.signature, b.kyberId, b.kyberKey, b.kyberSignature))
                .also { exported = it }
        }
    }
    suspend fun importCard(text: String): Contact = action {
        val card = ContactCardCodec.decode(text)
        val me = identity ?: throw AppFailure(AppError.CONTACT_UNAVAILABLE)
        if (card.deviceId == me.deviceId || card.userId == me.userId || card.identity.contentEquals(me.publicKey))
            throw AppFailure(AppError.AMBIGUOUS_IDENTITY)
        val contacts = repository.contacts()
        val existing = contacts.firstOrNull { it.remoteDeviceId == card.deviceId }
        if (contacts.any { it.publicUserId == card.userId && it.remoteDeviceId != card.deviceId } ||
            (existing != null && existing.publicUserId != card.userId)) throw AppFailure(AppError.AMBIGUOUS_IDENTITY)
        if (existing == null && contacts.size >= 200) throw AppFailure(AppError.LOCAL_CAPACITY)
        if (contacts.any { it.remoteDeviceId != card.deviceId && repository.card(it.remoteDeviceId)?.let {
                saved -> ContactCardCodec.decode(saved).identity.contentEquals(card.identity) } == true })
            throw AppFailure(AppError.AMBIGUOUS_IDENTITY)
        if (existing != null && repository.card(card.deviceId)?.let {
                ContactCardCodec.decode(it).identity.contentEquals(card.identity) } == true)
            throw AppFailure(AppError.DUPLICATE_CONTACT)
        // Engine alone decides whether the public key matches a pin and persists CHANGED on rejection.
        try { engine.establishSession(card.bundle()) }
        catch (e: CryptoFailure) {
            if (e.error == CryptoError.IdentityChanged && existing != null) repository.pending(card.deviceId, text)
            throw e
        }
        if (existing != null) throw AppFailure(AppError.DUPLICATE_CONTACT)
        repository.card(card.deviceId, text)
        Contact(RandomIdentifiers.create(), card.userId, card.username, card.deviceId).also(repository::save)
    }
    suspend fun contacts(): List<ContactStatus> = action {
        repository.contacts().map { ContactStatus(it, engine.getRemoteIdentityStatus(it.remoteDeviceId), engine.getSessionLifecycle(it.remoteDeviceId)) }
    }
    suspend fun messages(id: String) = action { repository.contact(id); repository.messages(id) }
    suspend fun sendNetwork(id:String,body:String,outbox:DurableOutbox):Message=action {
        networkAllowed(id)
        repository.capacity()
        val bytes=TextRules.encode(body)
        val submission=try {outbox.enqueue(id,bytes)} finally {bytes.fill(0)}
        val message=Message(submission,id,Direction.OUTGOING,body,System.currentTimeMillis(),MessageState.PENDING)
        repository.save(message)
        val result=try {outbox.process(submission)} catch(_:org.ghostcloak.protocol.ApiFailure) {return@action message}
        message.copy(state=if(result.state==OutboxState.SERVER_ACCEPTED) MessageState.SERVER_ACCEPTED else MessageState.FAILED).also {
            repository.save(it);outbox.removeFinished(submission)
        }
    }
    private suspend fun networkAllowed(id:String) {
        if(repository.contact(id).blocked || repository.contact(id).request) throw AppFailure(AppError.BLOCKED)
        if(engine.getRemoteIdentityStatus(id)?.trustState==IdentityTrustState.CHANGED) throw CryptoFailure(CryptoError.IdentityChanged)
        if(engine.getSessionLifecycle(id)!=SessionLifecycle.ACTIVE) throw CryptoFailure(CryptoError.UnknownSession)
    }
    suspend fun retryNetwork(outbox:DurableOutbox)=action {
        for(id in outbox.pendingIds()) {
            val entry=outbox.get(id)
            try { networkAllowed(entry.deviceId) }
            catch (e: AppFailure) { if (e.error == AppError.BLOCKED) continue else throw e }
            catch (e: CryptoFailure) {
                if (e.error in setOf(CryptoError.IdentityChanged, CryptoError.UnknownSession, CryptoError.ReauthenticationRequired)) continue
                throw e
            }
            val result=outbox.process(id)
            repository.messages(entry.deviceId).firstOrNull {it.localId==id}?.let { message ->
                repository.save(message.copy(state=if(result.state==OutboxState.SERVER_ACCEPTED) MessageState.SERVER_ACCEPTED else MessageState.FAILED))
            }
            if(result.state in setOf(OutboxState.SERVER_ACCEPTED,OutboxState.FAILED)) outbox.removeFinished(id)
        }
    }
    suspend fun acceptNetwork(envelope:EncryptedEnvelope, sender:SenderProfile? = null)=action {
        val hash=org.ghostcloak.protocol.DeviceAuth.digest(org.ghostcloak.protocol.EnvelopeCodec.encode(envelope))
        if(repository.accepted(envelope.senderDeviceId,envelope.envelopeId,hash)) return@action
        val contacts = repository.contacts()
        val contact = contacts.firstOrNull { it.remoteDeviceId == envelope.senderDeviceId } ?: run {
            val profile = sender ?: throw AppFailure(AppError.CONTACT_UNAVAILABLE)
            requireApi(profile.deviceId == envelope.senderDeviceId && profile.deviceId != identity?.deviceId)
            requireApi(listOf(profile.accountId, profile.deviceId, profile.routingId).all(RandomIdentifiers::valid))
            requireApi(Usernames.normalize(profile.username) == profile.username)
            if (contacts.size >= 200) throw AppFailure(AppError.LOCAL_CAPACITY)
            if (contacts.any { it.publicUserId == profile.accountId }) throw AppFailure(AppError.AMBIGUOUS_IDENTITY)
            Contact(RandomIdentifiers.create(), profile.accountId, profile.username, profile.deviceId, request = true)
        }
        if(contact.blocked) throw AppFailure(AppError.BLOCKED)
        repository.capacity()
        engine.decryptAndCommit(envelope) { bytes ->
            val body=bytes.decodeToString(throwOnInvalidSequence=true)
            TextRules.encode(body).fill(0)
            repository.save(contact)
            repository.saveAccepted(Message(envelope.envelopeId,contact.remoteDeviceId,Direction.INCOMING,body,
                System.currentTimeMillis(),MessageState.RECEIVED,envelope.envelopeId),hash)
        }
    }
    suspend fun acceptRequest(id: String) = action {
        val contact = repository.contact(id)
        if (contact.blocked) throw AppFailure(AppError.BLOCKED)
        repository.save(contact.copy(request = false))
    }
    suspend fun deleteRequest(id: String) = action {
        val contact = repository.contact(id)
        require(contact.request)
        // Retain a blocked identity tombstone so polling cannot recreate the request.
        repository.save(contact.copy(blocked = true))
        repository.messages(id).forEach { repository.delete(id, it.localId) }
    }
    suspend fun queuedSubmissions() = action {
        repository.contacts().flatMap { repository.messages(it.remoteDeviceId) }
            .filter { it.state == MessageState.SERVER_ACCEPTED && System.currentTimeMillis() - it.timestamp < 604800000 }
            .map { it.localId }
    }
    suspend fun deliveryStatuses(statuses: List<DeliveryStatus>) = action {
        val delivered = statuses.filter { it.acknowledged }.map { it.submissionId }.toSet()
        repository.contacts().forEach { contact -> repository.messages(contact.remoteDeviceId)
            .filter { it.direction == Direction.OUTGOING && it.state == MessageState.SERVER_ACCEPTED && it.localId in delivered }
            .forEach { repository.save(it.copy(state = MessageState.DELIVERED)) } }
    }
    suspend fun fingerprint(id: String, pending: Boolean = false) = action {
        repository.contact(id)
        if (pending) engine.getPendingFingerprint(id) else engine.getRemoteFingerprint(id)
    }
    suspend fun verify(id: String, expected: String) = action { repository.contact(id); engine.verifyRemoteIdentity(id, expected) }
    suspend fun trustReplacement(id: String, expected: String) = action {
        repository.contact(id)
        val pending = repository.pending(id) ?: throw AppFailure(AppError.FRESH_CARD_REQUIRED)
        engine.trustNewIdentity(id, expected)
        // Explicit approval only. No auto-verification; the engine still validates the signed bundle.
        engine.reestablishSession(ContactCardCodec.decode(pending).bundle(), expected)
        repository.card(id, pending); repository.clearPending(id)
    }
    suspend fun block(id: String, blocked: Boolean) = action { repository.save(repository.contact(id).copy(blocked = blocked)) }
    suspend fun delete(id: String, localId: String) = action { repository.contact(id); repository.delete(id, localId) }
    suspend fun send(id: String, body: String): Message = action {
        val bytes = TextRules.encode(body)
        try {
            val contact = repository.contact(id)
            if (contact.blocked) throw AppFailure(AppError.BLOCKED)
            if (engine.getRemoteIdentityStatus(id)?.trustState == IdentityTrustState.CHANGED) throw CryptoFailure(CryptoError.IdentityChanged)
            if (engine.getSessionLifecycle(id) != SessionLifecycle.ACTIVE) throw CryptoFailure(CryptoError.UnknownSession)
            var message = Message(RandomIdentifiers.create(), id, Direction.OUTGOING, body, System.currentTimeMillis(), MessageState.PENDING)
            repository.save(message)
            try {
                val envelope = engine.encrypt(id, bytes)
                message = message.copy(state = MessageState.ENCRYPTED, envelopeId = envelope.envelopeId); repository.save(message)
                (transport ?: throw TransportFailure(TransportError.WrongRoute)).send(id, envelope)
                message = message.copy(state = MessageState.SENT_TO_TRANSPORT); repository.save(message)
            } catch (e: CryptoFailure) { repository.save(message.copy(state = MessageState.FAILED)); throw e }
            catch (e: TransportFailure) { repository.save(message.copy(state = MessageState.FAILED)); throw e }
            message
        } finally { bytes.fill(0) }
    }
    suspend fun receive(envelope: EncryptedEnvelope): Message = action {
        val contact = repository.contact(envelope.senderDeviceId)
        if (contact.blocked) throw AppFailure(AppError.BLOCKED)
        repository.capacity()
        val bytes = engine.decrypt(envelope)
        try {
            val body = try { bytes.decodeToString(throwOnInvalidSequence = true) }
                catch (e: java.nio.charset.CharacterCodingException) { throw AppFailure(AppError.INVALID_TEXT) }
            val validation = TextRules.encode(body)
            validation.fill(0)
            Message(envelope.envelopeId, contact.remoteDeviceId, Direction.INCOMING, body,
                System.currentTimeMillis(), MessageState.DELIVERED_LOCAL_SIMULATION).also(repository::save)
        } finally { bytes.fill(0) }
    }
    /** Local simulator acknowledgement only, after the receiving service persisted plaintext. */
    suspend fun delivered(id: String, localId: String, receivedEnvelopeId: String) = action {
        val message = repository.messages(id).first { it.localId == localId }
        if (message.state != MessageState.SENT_TO_TRANSPORT || message.envelopeId != receivedEnvelopeId)
            throw AppFailure(AppError.CONTACT_UNAVAILABLE)
        repository.save(message.copy(state = MessageState.DELIVERED_LOCAL_SIMULATION))
    }
}
