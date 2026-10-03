@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
package org.ghostcloak.messaging

import kotlinx.serialization.*
import kotlinx.serialization.cbor.Cbor
import org.ghostcloak.crypto.EndpointRecords
import org.ghostcloak.crypto.EndpointStorageFailure

/** Only the endpoint's encrypted store may back this repository in the application. */
class LocalRepository(private val records: EndpointRecords, val clock: ExpiryClock = ExpiryClock()) {
    private val format = Cbor { encodeDefaults = true; ignoreUnknownKeys = false }
    private inline fun <reified T> read(key: String): T? = records.read(key)?.let { bytes ->
        try { format.decodeFromByteArray<T>(bytes) }
        catch (e: SerializationException) { throw EndpointStorageFailure() }
        catch (e: IllegalStateException) { throw EndpointStorageFailure() }
        finally { bytes.fill(0) }
    }
    private inline fun <reified T> put(key: String, value: T) {
        val bytes = format.encodeToByteArray(value)
        try { records.write(key, bytes) } finally { bytes.fill(0) }
    }
    fun hasIdentity() = records.transaction { records.read("local/device") != null }
    fun requireRequestConfirmation():Boolean=records.transaction {read<Boolean>("app/request-privacy") ?: true}
    fun requireRequestConfirmation(value:Boolean)=records.transaction {
        // Freeze old request presentation before changing the default.
        contacts().filter {it.request}.forEach {request(it.remoteDeviceId)}
        put("app/request-privacy",value)
    }
    fun serverReference(time:Long)=records.transaction {
        require(time>0)
        val now=clock.now(); val previous=serverNow()
        put("app/server-reference",ServerReference(maxOf(time,previous ?: time),now.elapsed,now.boot))
        contacts().filter {it.request}.forEach { contact ->
            val key="app/request/${contact.remoteDeviceId}"
            request(contact.remoteDeviceId).takeIf {it.acceptedAt==null && it.grace.boot==now.boot}?.let { r ->
                val elapsedAge=(REQUEST_WINDOW-(r.grace.elapsed-now.elapsed)).coerceAtLeast(0)
                put(key,r.copy(acceptedAt=serverNow()!!-elapsedAge))
            }
        }
    }
    fun serverNow():Long?=records.transaction {
        projectedServerTime(clock.now())
    }
    private fun projectedServerTime(now:ExpiryMoment):Long? {
        val ref=read<ServerReference>("app/server-reference")
        return ref?.takeIf {it.boot==now.boot && now.elapsed>=it.elapsed}?.let {it.time+now.elapsed-it.elapsed}
    }
    fun request(id:String):RequestRecord=records.transaction {
        val key="app/request/$id"
        val old=read<RequestRecord>(key)
        if(old==null) {
            RequestRecord(hidden=true,acceptedAt=serverNow(),grace=requestDeadline(),clockVersion=1)
                .also {put(key,it)}
        } else if(old.clockVersion==0) {
            // Old code already persisted a commit-relative elapsed deadline. Recover it on
            // the same boot; after reboot retain the earlier server deadline conservatively.
            val now=clock.now()
            val anchor=if(old.state==RequestState.PENDING && old.grace.boot==now.boot)
                serverNow()?.let {it-(REQUEST_WINDOW-(old.grace.elapsed-now.elapsed)).coerceAtLeast(0)}
            else old.acceptedAt
            old.copy(acceptedAt=anchor,clockVersion=1).also {put(key,it)}
        } else old
    }
    private fun requestDeadline(now:ExpiryMoment=clock.now())=now.let {
        ExpiryDeadline(Math.addExact(it.wall,REQUEST_WINDOW),Math.addExact(it.elapsed,REQUEST_WINDOW),it.boot)
    }
    fun startRequest(id:String,serverEnqueuedAt:Long?)=records.transaction {
        // Staged at the end of authenticated decryptAndCommit; visible only after its
        // enclosing ratchet/replay/message transaction durably succeeds.
        val commit=clock.now()
        put("app/request/$id",RequestRecord(hidden=requireRequestConfirmation(),acceptedAt=projectedServerTime(commit),
            grace=requestDeadline(commit),lastEnvelopeAt=serverEnqueuedAt,clockVersion=1))
    }
    fun requestExpired(id:String):Boolean=records.transaction {
        val r=request(id);val now=clock.now();val server=serverNow()
        r.state==RequestState.EXPIRED || if(now.boot==r.grace.boot) now.elapsed>=r.grace.elapsed
            else if(r.acceptedAt!=null && server!=null) server>=Math.addExact(r.acceptedAt,REQUEST_WINDOW)
            else r.acceptedAt==null // Untimed reboot fails closed; never grants a fresh window.
    }
    fun requestHidden(id:String)=records.transaction {contact(id).request && request(id).hidden}
    fun requestActive(id:String)=records.transaction {request(id).state==RequestState.PENDING && !requestExpired(id)}
    fun relationshipState(id:String):RelationshipState=records.transaction {
        val c=contacts().firstOrNull {it.remoteDeviceId==id}
        when {
            c==null -> RelationshipState.UNKNOWN
            c.blocked -> RelationshipState.BLOCKED
            !c.request -> RelationshipState.ACCEPTED_CONTACT
            requestActive(id) -> RelationshipState.REQUEST_PENDING
            else -> RelationshipState.DORMANT_UNACCEPTED
        }
    }
    fun isActiveContact(id:String)=relationshipState(id)==RelationshipState.ACCEPTED_CONTACT
    fun activateRetainedContact(id:String,card:String)=records.transaction {
        val c=contact(id)
        if(c.blocked) throw AppFailure(AppError.BLOCKED)
        expireRequests()
        save(c.copy(request=false));card(id,card);finishRequest(id,RequestState.ACCEPTED)
    }
    fun finishRequest(id:String,state:RequestState)=records.transaction {
        val r=request(id); put("app/request/$id",r.copy(state=state,lastEnvelopeAt=serverNow() ?: r.lastEnvelopeAt))
        if(state!=RequestState.ACCEPTED) clear(id)
    }
    fun block(id:String,blocked:Boolean)=records.transaction {
        val c=contact(id)
        save(c.copy(blocked=blocked))
        if(c.request && blocked) finishRequest(id,RequestState.BLOCKED)
        // Commit suppression and its request lifecycle together, including explicit Unblock.
        if(c.request && !blocked && request(id).state==RequestState.BLOCKED)
            finishRequest(id,RequestState.REJECTED)
    }
    fun isBlocked(id:String)=records.transaction {contact(id).blocked}
    fun listBlocked()=records.transaction {
        contacts().filter {it.blocked}.sortedWith(compareBy<Contact> {
            it.displayName.lowercase(java.util.Locale.ROOT)
        }.thenBy {it.remoteDeviceId})
    }
    fun unblock(id:String)=block(id,false)
    fun restartRequestIfEligible(id:String,acceptedAt:Long?)=records.transaction {
        val r=request(id)
        // Called only inside authenticated new-envelope commit, after accepted-envelope replay checks.
        // Queue age does not shorten a newly committed recipient decision window.
        if(!contact(id).blocked && r.state in setOf(RequestState.REJECTED,RequestState.EXPIRED))
            startRequest(id,acceptedAt)
    }
    fun expireRequests():Int=records.transaction {
        val expired=contacts().filter {it.request && request(it.remoteDeviceId).state==RequestState.PENDING && requestExpired(it.remoteDeviceId)}
        expired.forEach {finishRequest(it.remoteDeviceId,RequestState.EXPIRED)}
        expired.size
    }
    fun acceptRequest(id:String) {
        val failure=records.transaction {
            expireRequests()
            val r=request(id)
            when {
                r.state!=RequestState.PENDING || requestExpired(id) -> "request_expired"
                r.grace.boot!=clock.now().boot && serverNow()==null -> "request_time_unavailable"
                else -> {save(contact(id).copy(request=false));finishRequest(id,RequestState.ACCEPTED);null}
            }
        }
        // Throw outside the transaction so rejecting Accept cannot roll back expiry cleanup.
        require(failure==null) {failure!!}
    }
    fun attachmentPeer(id: String): Boolean = records.transaction {
        records.read("app/attachment-peer/$id")?.contentEquals(byteArrayOf(1)) == true ||
            read<ExpiryDeadline>("app/directory-capability/$id")?.let { deadline ->
                val now=clock.now(); deadline.boot==now.boot && !deadline.reached(now)
            } == true
    }
    fun directoryCapability(id: String, issued: Long, remaining: Long) = records.transaction {
        val floor = read<Long>("app/capability-floor/$id") ?: 0L
        require(issued >= floor) { "capability_rollback" }
        val now = clock.now()
        val old=read<ExpiryDeadline>("app/capability-deadline/$id")
        if(issued==floor && old!=null && old.boot==now.boot) {
            put("app/directory-capability/$id",old); return@transaction
        }
        val wall=if(issued==floor && old!=null) minOf(old.wall,now.wall+remaining) else now.wall+remaining
        put("app/capability-floor/$id", issued)
        val deadline=ExpiryDeadline(wall,now.elapsed+(wall-now.wall).coerceAtLeast(0),now.boot)
        put("app/directory-capability/$id",deadline);put("app/capability-deadline/$id",deadline)
    }
    fun clearDirectoryCapability(id:String)=records.transaction { records.remove("app/directory-capability/$id") }
    fun resetCapabilities(id:String)=records.transaction {
        attachmentPeer(id,false); clearDirectoryCapability(id); records.remove("app/capability-floor/$id")
        records.remove("app/capability-deadline/$id")
    }
    fun attachmentPeer(id: String, supported: Boolean) = records.transaction {
        if (supported) records.write("app/attachment-peer/$id", byteArrayOf(1)) else records.remove("app/attachment-peer/$id")
    }
    fun contacts(): List<Contact> = records.transaction { records.keys("app/contact/").map { read<Contact>(it) ?: throw EndpointStorageFailure() } }
    fun contact(id: String) = contacts().firstOrNull { it.remoteDeviceId == id } ?: throw AppFailure(AppError.CONTACT_UNAVAILABLE)
    fun save(contact: Contact) = records.transaction { put("app/contact/${contact.remoteDeviceId}", contact) }
    fun pending(id: String): String? = records.transaction { read<String>("app/pending-card/$id") }
    fun pending(id: String, card: String) = records.transaction { put("app/pending-card/$id", card) }
    fun clearPending(id: String) = records.transaction { records.remove("app/pending-card/$id") }
    fun card(id: String): String? = records.transaction { read<String>("app/card/$id") }
    fun card(id: String, text: String) = records.transaction { put("app/card/$id", text) }
    fun messages(id: String): List<Message> = records.transaction {
        expire(id)
        records.keys("app/message/$id/").map { read<Message>(it) ?: throw EndpointStorageFailure() }.sortedBy { it.timestamp }
    }
    fun policy(id: String): Int = records.transaction { read<Int>("app/disappearing/$id")?.also { DisappearingTimer.from(it) } ?: 0 }
    fun policy(id: String, seconds: Int) = records.transaction { DisappearingTimer.from(seconds); put("app/disappearing/$id", seconds) }
    fun expire(conversationId: String? = null): Int = records.transaction {
        val now = clock.now()
        val prefix = if (conversationId == null) "app/message/" else "app/message/$conversationId/"
        val expired = records.keys(prefix).map { read<Message>(it) ?: throw EndpointStorageFailure() }
            .map { message ->
                // Upgrade old acceptance-started deadlines before any expiry deletion.
                if (message.direction == Direction.OUTGOING && message.state != MessageState.DELIVERED && message.expiry != null)
                    message.copy(expiry = null).also(::save) else message
            }.filter { it.activeExpiry?.reached(now) == true }
        expired.forEach { delete(it.conversationId, it.localId) }
        expired.size + expireRequests()
    }
    fun acceptedOutgoing(id: String, localId: String) = records.transaction {
        val message = read<Message>("app/message/$id/$localId") ?: return@transaction
        if (message.direction != Direction.OUTGOING || message.state in setOf(MessageState.DELIVERED,MessageState.EXPIRED_UNDELIVERED)) return@transaction
        save(message.copy(state = MessageState.SERVER_ACCEPTED, expiry = null))
    }
    fun deliveredOutgoing(id: String, localId: String) = records.transaction {
        val message = read<Message>("app/message/$id/$localId") ?: return@transaction
        if (message.direction != Direction.OUTGOING || message.state != MessageState.SERVER_ACCEPTED) return@transaction
        // A queued deadline from the previous implementation is not a delivery deadline.
        save(message.copy(state = MessageState.DELIVERED, expiry = if (!message.policyEvent && message.disappearingSeconds > 0)
            ExpiryDeadline.start(message.disappearingSeconds, clock.now()) else null))
    }
    fun expiredOutgoing(id:String,localId:String)=records.transaction {
        val message=read<Message>("app/message/$id/$localId") ?: return@transaction
        if(message.direction==Direction.OUTGOING && message.state==MessageState.SERVER_ACCEPTED)
            save(message.copy(state=MessageState.EXPIRED_UNDELIVERED,expiry=null))
    }
    fun unreadCount(id: String): Int = records.transaction {
        val seen = read<List<String>>("app/read/$id").orEmpty().toSet()
        messages(id).count { it.direction == Direction.INCOMING && !it.policyEvent && it.localId !in seen }
    }
    fun unreadMessageIds(id: String): Set<String> = records.transaction {
        val seen = read<List<String>>("app/read/$id").orEmpty().toSet()
        messages(id).filter { it.direction == Direction.INCOMING && !it.policyEvent && it.localId !in seen }.map { it.localId }.toSet()
    }
    fun markRead(id: String) = records.transaction {
        val seen = messages(id).filter { it.direction == Direction.INCOMING && !it.policyEvent }.map { it.localId }
        if (read<List<String>>("app/read/$id") != seen) put("app/read/$id", seen)
    }
    fun save(message: Message) = records.transaction {
        val key = "app/message/${message.conversationId}/${message.localId}"
        val existing = records.keys("app/message/")
        if (key !in existing && existing.size >= 5000) throw AppFailure(AppError.LOCAL_CAPACITY)
        put(key, message)
    }
    fun delete(id: String, localId: String) = records.transaction {
        records.remove("app/attachment/$id/$localId")
        records.remove("app/message/$id/$localId")
        val readKey = "app/read/$id"
        val remaining = read<List<String>>(readKey).orEmpty().filterNot { it == localId }
        if (remaining.isEmpty()) records.remove(readKey) else put(readKey, remaining)
        NotificationLedger.remove(records, id, localId)
        // Keep app/accepted and engine replay evidence. Deletion is not an unsend:
        // unfinished outbox delivery continues, without recreating visible history.
        // Receipt polling is derived from visible SERVER_ACCEPTED messages, not a separate ledger.
    }
    fun clear(id: String) = records.transaction {
        records.keys("app/attachment/$id/").forEach(records::remove)
        records.keys("app/message/$id/").forEach(records::remove)
        records.remove("app/read/$id")
        NotificationLedger.clear(records, id)
    }
    fun capacity() = records.transaction { if (records.keys("app/message/").size >= 5000) throw AppFailure(AppError.LOCAL_CAPACITY) }
    fun attachment(id:String,localId:String):org.ghostcloak.attachments.AttachmentDescriptor? = records.transaction {
        records.read("app/attachment/$id/$localId")?.let { bytes ->
            try { org.ghostcloak.attachments.AttachmentFormat.decode(bytes) } finally { bytes.fill(0) }
        }
    }
    fun hasAttachment(id:String,localId:String) = records.transaction { "app/attachment/$id/$localId" in records.keys("app/attachment/$id/") }
    /** UI access metadata only; no attachment key, descriptor or message body leaves this read. */
    fun attachmentAccessExpiries(allowedContacts:Set<String>):Map<Pair<String,String>,ExpiryDeadline?> = records.transaction {
        buildMap {
            records.keys("app/attachment/").forEach { key ->
                val reference=key.removePrefix("app/attachment/")
                val id=reference.substringBefore('/'); val localId=reference.substringAfter('/')
                if(id in allowedContacts) {
                    val message=read<Message>("app/message/$id/$localId")
                    if(message!=null && message.activeExpiry?.reached(clock.now())!=true)
                        put(id to localId,message.activeExpiry)
                }
            }
        }
    }
    fun attachmentAvailable(id:String,localId:String) = records.transaction {
        val message=read<Message>("app/message/$id/$localId")
        val contact=read<Contact>("app/contact/$id")
        message!=null && message.activeExpiry?.reached(clock.now())!=true && contact!=null && isActiveContact(id) && hasAttachment(id,localId)
    }
    fun attachment(id:String,localId:String,bytes:ByteArray) = records.transaction {
        org.ghostcloak.attachments.AttachmentFormat.decode(bytes)
        records.write("app/attachment/$id/$localId",bytes)
    }
    fun accepted(sender:String,id:String,hash:ByteArray):Boolean=records.transaction {
        val existing=records.read("app/accepted/$sender/$id") ?: return@transaction false
        require(java.security.MessageDigest.isEqual(existing,hash)) {"Envelope receipt conflict"}; true
    }
    fun saveAccepted(message:Message,hash:ByteArray)=records.transaction {
        saveEnvelopeReceipt(message.conversationId,requireNotNull(message.envelopeId),hash)
        save(message)
        // Same transaction as authenticated content and deduplication; never enqueue on FETCH alone.
        NotificationLedger.accepted(records, message)
    }
    /** Security receipt only: no plaintext, descriptor, read state or notification ledger entry. */
    fun saveEnvelopeReceipt(sender:String,id:String,hash:ByteArray)=records.transaction {
        require(records.keys("app/accepted/").size<10000)
        records.write("app/accepted/$sender/$id",hash)
    }
}
