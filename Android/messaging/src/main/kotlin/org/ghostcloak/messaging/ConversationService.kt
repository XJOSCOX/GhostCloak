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
    init { repository.finalizeInterruptedViews() }
    private val mutex = Mutex()
    private var identity: DeviceIdentity? = null
    private var exported: String? = null
    private var transport: EncryptedMessageTransport? = null
    private suspend fun <T> action(block: suspend () -> T): T = withContext(Dispatchers.IO) { mutex.withLock { block() } }
    suspend fun open(): DeviceIdentity? = action {
        if (repository.hasIdentity()) engine.createIdentity("Local").also { identity = it } else null
    }
    suspend fun create(displayName: String): DeviceIdentity = action {
        TextRules.displayName(displayName)
        engine.createIdentity(displayName).also { identity = it }
    }
    suspend fun rename(displayName: String): DeviceIdentity = action {
        TextRules.displayName(displayName)
        engine.renameLocalUser(displayName).also { identity = it; exported = null }
    }
    suspend fun attach(value: EncryptedMessageTransport) = action { transport = value }
    suspend fun exportCard(fresh: Boolean = false): String = action {
        if (fresh) exported = null
        exported ?: run {
            val me = identity ?: throw AppFailure(AppError.CONTACT_UNAVAILABLE)
            val b = engine.publicBundle()
            ContactCardCodec.encode(ContactCard(2, me.userId, GhostCloakIds.generate(), me.deviceId, b.registrationId,
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
        if (existing?.blocked == true) throw AppFailure(AppError.BLOCKED)
        if (existing != null && repository.isActiveContact(card.deviceId) && repository.card(card.deviceId)?.let {
                ContactCardCodec.decode(it).identity.contentEquals(card.identity) } == true)
            throw AppFailure(AppError.DUPLICATE_CONTACT)
        // Engine alone decides whether the public key matches a pin and persists CHANGED on rejection.
        try {
            if(existing != null && !repository.isActiveContact(card.deviceId)) engine.validateRetainedIdentity(card.bundle())
            else engine.establishSession(card.bundle())
        }
        catch (e: CryptoFailure) {
            if (e.error == CryptoError.IdentityChanged && existing != null) repository.pending(card.deviceId, text)
            throw e
        }
        if (existing != null) {
            if(repository.isActiveContact(card.deviceId)) throw AppFailure(AppError.DUPLICATE_CONTACT)
            repository.activateRetainedContact(card.deviceId,text)
            return@action repository.contact(card.deviceId)
        }
        repository.card(card.deviceId, text)
        Contact(RandomIdentifiers.create(), card.userId, GhostCloakIds.display(card.ghostCloakId), card.deviceId, ghostCloakId=card.ghostCloakId).also(repository::save)
    }
    suspend fun contacts(): List<ContactStatus> = action {
        repository.expireRequests()
        repository.contacts().filter { !it.blocked && (
            repository.isActiveContact(it.remoteDeviceId) ||
                repository.relationshipState(it.remoteDeviceId)==RelationshipState.REQUEST_PENDING
        ) }
            .map { ContactStatus(it, engine.getRemoteIdentityStatus(it.remoteDeviceId), engine.getSessionLifecycle(it.remoteDeviceId)) }
    }
    suspend fun blockedContacts():List<Contact> = action {repository.listBlocked()}
    suspend fun unblock(id:String) = action {repository.unblock(id)}
    suspend fun messages(id: String) = action { repository.contact(id); repository.messages(id) }
    suspend fun messagesForUi(id:String) = action {
        val contact = repository.contact(id)
        if(contact.request && (!repository.requestActive(id) || repository.requestHidden(id) || (repository.request(id).acceptedAt!=null && repository.serverNow()==null))) return@action emptyList<Message>()
        val reactions=if(contact.request || contact.blocked) emptyMap() else repository.reactionSnapshot(id)
        repository.messages(id).map { message ->
            repository.attachment(id,message.localId)?.let { descriptor ->
                val summary = if (contact.request) AttachmentSummary(false,"Attachment",0)
                    else AttachmentSummary(descriptor.kind == org.ghostcloak.attachments.AttachmentKind.IMAGE,
                        descriptor.filename ?: when(descriptor.kind) {
                            org.ghostcloak.attachments.AttachmentKind.IMAGE -> "Photo"
                            org.ghostcloak.attachments.AttachmentKind.VOICE_NOTE -> "Voice note"
                            else -> "Attachment" },
                        descriptor.plaintextLength, descriptor.kind in setOf(org.ghostcloak.attachments.AttachmentKind.IMAGE,
                            org.ghostcloak.attachments.AttachmentKind.DOCUMENT,org.ghostcloak.attachments.AttachmentKind.VOICE_NOTE),
                        descriptor.kind,descriptor.durationMillis,message.body.takeIf {it.isNotBlank()})
                message.copy(body = if (contact.request) "Attachment" else message.body.ifBlank {summary.filename}, attachment = summary)
            } ?: message
        }.map { message ->
            if(message.viewOnceKind!=null) message.copy(body=if(message.viewOnceState==ViewOnceState.CONSUMED ||
                message.viewOnceState==ViewOnceState.REVEALING) "View Once ${if(message.viewOnceKind==ViewOnceKind.PHOTO) "photo" else "message"} expired"
                else "View Once ${if(message.viewOnceKind==ViewOnceKind.PHOTO) "photo" else "message"}",
                attachment=null) else message
        }.map { if(contact.request) it.copy(disappearingSeconds=0,expiry=null) else
            it.copy(reactions=if(it.viewOnceKind!=null) emptyList() else reactions[it.envelopeId].orEmpty()) }
    }
    suspend fun attachmentPeer(id: String) = action { networkAllowed(id); repository.attachmentPeer(id) }
    suspend fun mediaPeer(id:String)=action { networkAllowed(id);repository.mediaPeer(id) }
    suspend fun reactionPeer(id:String)=action {networkAllowed(id);repository.reactionPeer(id)}
    suspend fun react(id:String,target:String,emoji:String?,outbox:DurableOutbox)=action {
        networkAllowed(id)
        require(repository.reactionPeer(id)) {"reaction_capability_required"}
        require(repository.reactionTarget(id,target)!=null) {"reaction_target_unavailable"}
        require(emoji==null || emoji in ConversationPayload.reactionEmoji)
        val update=ReactionUpdate(target,repository.nextReactionSequence(id,target),emoji)
        val bytes=ConversationPayload.encodeReaction(update)
        val submission=try {outbox.enqueue(id,bytes) {repository.applyReaction(id,update,true)}}
            finally {bytes.fill(0)}
        try {outbox.process(submission)} catch (_:ApiFailure) {return@action}
        outbox.removeFinished(submission)
    }
    /** Only a retained nonterminal outbox entry is retryable; it keeps its submission ID/ciphertext. */
    suspend fun retrySubmission(id:String,localId:String,outbox:DurableOutbox)=action {
        networkAllowed(id)
        val message=repository.messages(id).singleOrNull {it.localId==localId && it.direction==Direction.OUTGOING}
            ?: throw AppFailure(AppError.CONTACT_UNAVAILABLE)
        require(message.state in setOf(MessageState.PENDING,MessageState.ENCRYPTED,MessageState.SENT_TO_TRANSPORT))
        val entry=outbox.get(localId)
        require(entry.deviceId==id && entry.state !in setOf(OutboxState.FAILED,OutboxState.SERVER_ACCEPTED))
        val result=outbox.process(localId) {repository.acceptedOutgoing(id,it.submissionId,it.envelopeId)}
        if(result.state==OutboxState.FAILED) repository.save(message.copy(state=MessageState.FAILED))
        if(result.state in setOf(OutboxState.FAILED,OutboxState.SERVER_ACCEPTED)) outbox.removeFinished(localId)
    }
    suspend fun directoryCapability(entry: DirectoryEntry, time: Long, valid: Boolean) = action {
        val contact=repository.contact(entry.deviceId)
        requireApi(contact.publicUserId == entry.accountId,"capability_binding")
        if(valid) {
            val proof=entry.bundle.capability!!
            repository.directoryCapability(entry.deviceId,proof.issuedAt,proof.expiresAt-time)
        } else repository.clearDirectoryCapability(entry.deviceId)
    }
    suspend fun sendNetwork(id:String,body:String,outbox:DurableOutbox,viewOnce:Boolean=false,
        replyTo:ReplyReference?=null):Message=action {
        enqueueNetwork(id, body, repository.policy(id), false, outbox,viewOnce,replyTo)
    }
    /** Internal foundation API: caller must have confirmed upload and compatible peer support. */
    suspend fun sendAttachment(id:String,descriptor:org.ghostcloak.attachments.AttachmentDescriptor,
        outbox:DurableOutbox,peerSupportsAttachments:Boolean,viewOnce:Boolean=false,
        caption:String="",onEnqueued:(Message)->Unit = {}):Message=action {
        require(peerSupportsAttachments)
        if(viewOnce) require(descriptor.kind==org.ghostcloak.attachments.AttachmentKind.IMAGE)
        if(descriptor.kind==org.ghostcloak.attachments.AttachmentKind.VOICE_NOTE || caption.isNotBlank())
            require(repository.mediaPeer(id)) { "media_capability_required" }
        networkAllowed(id); repository.capacity(); descriptor.validate()
        val normalized=ConversationPayload.validateCaption(caption)
        val bytes=ConversationPayload.encodeAttachment(descriptor,identity?.displayName,viewOnce,normalized)
        lateinit var message:Message
        val submission=try { outbox.enqueue(id,bytes) { localId ->
            message=Message(localId,id,Direction.OUTGOING,normalized,repository.clock.now().wall,MessageState.PENDING,
                disappearingSeconds=descriptor.disappearingSeconds,
                viewOnceKind=if(viewOnce) ViewOnceKind.PHOTO else null)
            repository.save(message)
            val encoded=org.ghostcloak.attachments.AttachmentFormat.encode(descriptor)
            try { repository.attachment(id,localId,encoded) } finally { encoded.fill(0) }
            onEnqueued(message)
        } } finally { bytes.fill(0) }
        try { outbox.process(submission) { repository.acceptedOutgoing(id,it.submissionId,it.envelopeId) } }
        catch (_:ApiFailure) { return@action message }
        outbox.removeFinished(submission)
        repository.messages(id).firstOrNull {it.localId==submission} ?: message
    }
    suspend fun attachment(id:String,localId:String)=action {
        networkAllowed(id)
        require(repository.messages(id).any {it.localId==localId})
        repository.attachment(id,localId)
    }
    suspend fun beginViewOnce(id:String,localId:String):Message=action { repository.beginViewOnce(id,localId) }
    suspend fun consumeViewOnce(id:String,localId:String)=action { repository.consumeViewOnce(id,localId) }
    suspend fun setDisappearing(id: String, seconds: Int, outbox: DurableOutbox): Message = action {
        enqueueNetwork(id, "", seconds, true, outbox)
    }
    suspend fun policies() = action { repository.contacts().filter {!it.request}.associate { it.remoteDeviceId to repository.policy(it.remoteDeviceId) } }
    suspend fun requireRequestConfirmation()=action {repository.requireRequestConfirmation()}
    suspend fun requireRequestConfirmation(value:Boolean)=action {repository.requireRequestConfirmation(value)}
    suspend fun serverReference(time:Long)=action {repository.serverReference(time);repository.expireRequests()}
    suspend fun reconcileExpiry() = action { repository.expire() }
    private suspend fun enqueueNetwork(id: String, body: String, seconds: Int, control: Boolean, outbox: DurableOutbox,
        viewOnce:Boolean=false,replyTo:ReplyReference?=null): Message {
        networkAllowed(id)
        repository.capacity()
        if(replyTo!=null) {
            require(!viewOnce && !control && RandomIdentifiers.valid(replyTo.envelopeId))
            val target=repository.messages(id).singleOrNull { it.envelopeId==replyTo.envelopeId && !it.policyEvent }
                ?: throw AppFailure(AppError.CONTACT_UNAVAILABLE)
            val kind=when {
                target.viewOnceKind!=null -> ReplyKind.VIEW_ONCE
                repository.attachment(id,target.localId)!=null -> ReplyKind.ATTACHMENT
                target.disappearingSeconds>0 -> ReplyKind.DISAPPEARING
                else -> ReplyKind.TEXT
            }
            require(kind==replyTo.kind)
        }
        val bytes = ConversationPayload.encode(body, seconds, control,identity?.displayName,viewOnce,replyTo)
        lateinit var message: Message
        val submission = try { outbox.enqueue(id, bytes) { submission ->
            message = Message(submission, id, Direction.OUTGOING, if (control) ConversationPayload.policyText(seconds) else body,
                repository.clock.now().wall, MessageState.PENDING, disappearingSeconds = seconds, policyEvent = control,
                viewOnceKind=if(viewOnce) ViewOnceKind.TEXT else null,replyTo=replyTo)
            repository.save(message)
            if (control) repository.policy(id, seconds)
        } } finally { bytes.fill(0) }
        val result = try { outbox.process(submission) { repository.acceptedOutgoing(id, it.submissionId,it.envelopeId) } }
            catch (_: org.ghostcloak.protocol.ApiFailure) { return message }
        if (result.state == OutboxState.FAILED) repository.save(message.copy(state = MessageState.FAILED))
        outbox.removeFinished(submission)
        return repository.messages(id).firstOrNull { it.localId == submission } ?: message
    }
    private suspend fun networkAllowed(id:String) {
        if(!repository.isActiveContact(id)) throw AppFailure(AppError.BLOCKED)
        if(engine.getRemoteIdentityStatus(id)?.trustState==IdentityTrustState.CHANGED) throw CryptoFailure(CryptoError.IdentityChanged)
        if(engine.getSessionLifecycle(id)!=SessionLifecycle.ACTIVE) throw CryptoFailure(CryptoError.UnknownSession)
    }
    suspend fun retryNetwork(outbox:DurableOutbox)=action {
        for(id in outbox.pendingIds()) {
            val entry=outbox.get(id)
            if(repository.messages(entry.deviceId).any {it.localId==id && it.state==MessageState.EXPIRED_UNDELIVERED}) {
                outbox.removeExpired(id);continue
            }
            try { networkAllowed(entry.deviceId) }
            catch (e: AppFailure) { if (e.error == AppError.BLOCKED) continue else throw e }
            catch (e: CryptoFailure) {
                if (e.error in setOf(CryptoError.IdentityChanged, CryptoError.UnknownSession, CryptoError.ReauthenticationRequired)) continue
                throw e
            }
            val result=outbox.process(id) {
                repository.acceptedOutgoing(entry.deviceId, it.submissionId,it.envelopeId)
                repository.profileSynced(entry.deviceId,it.submissionId)
            }
            repository.messages(entry.deviceId).firstOrNull {it.localId==id}?.let { message ->
                repository.save(message.copy(state=if(result.state==OutboxState.SERVER_ACCEPTED) MessageState.SERVER_ACCEPTED else MessageState.FAILED))
            }
            if(result.state in setOf(OutboxState.SERVER_ACCEPTED,OutboxState.FAILED)) outbox.removeFinished(id)
        }
        // A failed pre-upload encryption or an interrupted enqueue retains the durable
        // acceptance intent. A new submission is safe only when its old entry is absent.
        val queued=outbox.pendingIds().toSet()
        for((peer,submission) in repository.profileIntents()) {
            if(submission in queued) continue
            try { networkAllowed(peer) } catch (_: AppFailure) { continue }
            catch (e: CryptoFailure) {
                if(e.error in setOf(CryptoError.IdentityChanged,CryptoError.UnknownSession,CryptoError.ReauthenticationRequired)) continue
                throw e
            }
            val me=identity ?: if(repository.hasIdentity()) engine.createIdentity("Local").also { identity=it }
                else throw AppFailure(AppError.CONTACT_UNAVAILABLE)
            val bytes=ConversationPayload.encodeProfile(me.displayName)
            try { outbox.enqueue(peer,bytes) { repository.profileIntent(peer,it) } }
            finally { bytes.fill(0) }
        }
    }
    suspend fun acceptNetwork(envelope:EncryptedEnvelope, sender:SenderProfile? = null, serverAcceptedAt:Long?=null)=action {
        val hash=org.ghostcloak.protocol.DeviceAuth.digest(org.ghostcloak.protocol.EnvelopeCodec.encode(envelope))
        if(repository.accepted(envelope.senderDeviceId,envelope.envelopeId,hash)) return@action
        val contacts = repository.contacts()
        val contact = contacts.firstOrNull { it.remoteDeviceId == envelope.senderDeviceId } ?: run {
            val profile = sender ?: throw AppFailure(AppError.CONTACT_UNAVAILABLE)
            requireApi(profile.deviceId == envelope.senderDeviceId && profile.deviceId != identity?.deviceId)
            requireApi(listOf(profile.accountId, profile.deviceId, profile.routingId).all(RandomIdentifiers::valid))
            requireApi(GhostCloakIds.valid(profile.ghostCloakId))
            if (contacts.size >= 200) throw AppFailure(AppError.LOCAL_CAPACITY)
            if (contacts.any { it.publicUserId == profile.accountId }) throw AppFailure(AppError.AMBIGUOUS_IDENTITY)
            Contact(RandomIdentifiers.create(), profile.accountId, GhostCloakIds.display(profile.ghostCloakId), profile.deviceId, request = true,ghostCloakId=profile.ghostCloakId)
        }
        if (sender != null) requireApi(sender.deviceId == contact.remoteDeviceId &&
            sender.accountId == contact.publicUserId && GhostCloakIds.valid(sender.ghostCloakId) &&
            (contact.ghostCloakId == null || contact.ghostCloakId == sender.ghostCloakId), "sender_mismatch")
        if(contact.blocked) {
            // Authenticate sender/bound envelope and atomically advance the ratchet and replay receipt.
            // Do not parse content, retain descriptors, create requests or enqueue notifications.
            engine.decryptAndCommit(envelope) {
                repository.saveEnvelopeReceipt(contact.remoteDeviceId,envelope.envelopeId,hash)
            }
            return@action
        }
        engine.decryptAndCommit(envelope) { bytes ->
            val content = ConversationPayload.decode(bytes)
            if(content.profileUpdate) {
                // Unknown and still-pending relationships do not learn a profile or
                // create a conversation. The authenticated receipt permits ACK.
                if(contacts.any {it.remoteDeviceId==contact.remoteDeviceId} && !contact.request)
                    repository.save(contact.copy(displayName=requireNotNull(content.displayName)))
                repository.saveEnvelopeReceipt(contact.remoteDeviceId,envelope.envelopeId,hash)
                return@decryptAndCommit
            }
            if(content.reaction!=null) {
                if(!contact.request && contacts.any {it.remoteDeviceId==contact.remoteDeviceId})
                    repository.applyReaction(contact.remoteDeviceId,content.reaction,false)
                repository.saveEnvelopeReceipt(contact.remoteDeviceId,envelope.envelopeId,hash)
                return@decryptAndCommit
            }
            // Defer controls until this side has explicitly accepted/added the contact.
            if (content.control && (contacts.none { it.remoteDeviceId == contact.remoteDeviceId } || contact.request))
                throw AppFailure(AppError.BLOCKED)
            repository.capacity()
            repository.expireRequests()
            repository.save(if(content.displayName!=null) contact.copy(displayName=content.displayName) else contact)
            // A later legacy message withdraws the claim (for example after a downgrade).
            if (content.attachment == null) {
                repository.attachmentPeer(contact.remoteDeviceId,content.supportsAttachments)
                repository.mediaPeer(contact.remoteDeviceId,content.supportsMedia)
                repository.reactionPeer(contact.remoteDeviceId,content.supportsReactions)
            }
            val now = repository.clock.now()
            repository.saveAccepted(Message(envelope.envelopeId,contact.remoteDeviceId,Direction.INCOMING,
                if (content.control) ConversationPayload.policyText(content.seconds) else content.body,
                now.wall,MessageState.RECEIVED,envelope.envelopeId, content.seconds,
                if (!content.control && content.seconds > 0) ExpiryDeadline.start(content.seconds, now) else null, content.control,
                viewOnceKind=content.viewOnceKind,
                viewOnceState=if(content.viewOnceKind!=null) ViewOnceState.AVAILABLE else null,
                replyTo=content.replyTo),hash)
            if (content.control) repository.policy(contact.remoteDeviceId, content.seconds)
            content.attachment?.let { encoded ->
                try { repository.attachment(contact.remoteDeviceId,envelope.envelopeId,encoded) }
                finally { encoded.fill(0) }
            }
            if(contact.request) {
                if(contacts.none {it.remoteDeviceId==contact.remoteDeviceId})
                    repository.startRequest(contact.remoteDeviceId,serverAcceptedAt)
                else repository.restartRequestIfEligible(contact.remoteDeviceId,serverAcceptedAt)
                val r=repository.request(contact.remoteDeviceId)
                if(r.state!=RequestState.PENDING || repository.requestExpired(contact.remoteDeviceId)) {
                    repository.finishRequest(contact.remoteDeviceId,if(r.state==RequestState.PENDING) RequestState.EXPIRED else r.state)
                }
            }
        }
    }
    suspend fun acceptRequest(id: String, outbox: DurableOutbox? = null) = action {
        val contact = repository.contact(id)
        if (contact.blocked) throw AppFailure(AppError.BLOCKED)
        if(contact.request) {
            if(outbox==null) repository.acceptRequest(id)
            else {
                val me=identity ?: if(repository.hasIdentity()) engine.createIdentity("Local").also { identity=it }
                    else throw AppFailure(AppError.CONTACT_UNAVAILABLE)
                val bytes=ConversationPayload.encodeProfile(me.displayName)
                try { outbox.enqueue(id,bytes) { submission ->
                    repository.acceptRequest(id) { repository.profileIntent(id,submission) }
                } } finally { bytes.fill(0) }
            }
        }
    }
    suspend fun deleteRequest(id: String) = action {
        val contact = repository.contact(id)
        require(contact.request)
        if(contact.blocked) throw AppFailure(AppError.BLOCKED)
        repository.finishRequest(id,RequestState.REJECTED)
    }
    suspend fun unreadCounts() = action { repository.contacts().filter { !it.blocked && (!it.request || repository.requestActive(it.remoteDeviceId)) }.associate { it.remoteDeviceId to repository.unreadCount(it.remoteDeviceId) } }
    suspend fun unreadCount() = unreadCounts().values.sum()
    suspend fun unreadExpiries() = action {
        repository.contacts().filter { !it.blocked && !it.request }.associate { contact ->
            val unread = repository.unreadMessageIds(contact.remoteDeviceId)
            contact.remoteDeviceId to repository.messages(contact.remoteDeviceId).filter { it.localId in unread }.mapNotNull { it.expiry }
        }
    }
    suspend fun markRead(id: String) = action { if(!repository.contact(id).request) repository.markRead(id) }
    suspend fun queuedSubmissions() = action {
        repository.contacts().flatMap { repository.messages(it.remoteDeviceId) }
            .filter { it.state == MessageState.SERVER_ACCEPTED }
            .map { it.localId }
    }
    suspend fun deliveryStatuses(statuses: List<DeliveryStatus>) = action {
        val delivered = statuses.filter { it.acknowledged }.map { it.submissionId }.toSet()
        require(statuses.none {it.acknowledged && it.expired})
        val expired=statuses.filter {it.expired}.map {it.submissionId}.toSet()
        repository.contacts().forEach { contact -> repository.messages(contact.remoteDeviceId)
            .filter {it.localId in expired}.forEach {repository.expiredOutgoing(it.conversationId,it.localId)} }
        repository.contacts().forEach { contact -> repository.messages(contact.remoteDeviceId)
            .filter { it.direction == Direction.OUTGOING && it.state == MessageState.SERVER_ACCEPTED && it.localId in delivered }
            .forEach { repository.deliveredOutgoing(it.conversationId, it.localId) } }
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
        repository.resetCapabilities(id)
        // Explicit approval only. No auto-verification; the engine still validates the signed bundle.
        engine.reestablishSession(ContactCardCodec.decode(pending).bundle(), expected)
        repository.card(id, pending); repository.clearPending(id)
    }
    suspend fun block(id: String, blocked: Boolean) = action { repository.block(id,blocked) }
    suspend fun setLocalAlias(id:String,value:String?)=action { repository.localAlias(id,value) }
    suspend fun setPinned(id:String,value:Boolean)=action { repository.pinned(id,value) }
    suspend fun setArchived(id:String,value:Boolean)=action { repository.archived(id,value) }
    suspend fun setMuted(id:String,value:Boolean)=action { repository.muted(id,value) }
    suspend fun removeContact(id:String) = action { repository.removeContact(id) }
    suspend fun delete(id: String, localId: String) = action { repository.contact(id); repository.delete(id, localId) }
    suspend fun clearConversation(id: String) = action { repository.contact(id); repository.clear(id) }
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
