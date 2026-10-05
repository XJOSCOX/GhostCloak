@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
package org.ghostcloak.messaging

import kotlinx.serialization.*
import kotlinx.serialization.cbor.Cbor
import org.ghostcloak.crypto.EndpointRecords
import org.ghostcloak.crypto.EndpointStorageFailure
import org.ghostcloak.identity.RandomIdentifiers

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
        put("app/request/$id",RequestRecord(hidden=requireRequestConfirmation() || read<Boolean>("app/force-hidden-request/$id")==true,
            acceptedAt=projectedServerTime(commit),
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
    private fun retainHistory(id:String) {
        put("app/retained-history/$id", messages(id).map { it.localId })
        markRead(id)
        NotificationLedger.clear(records,id)
    }
    private fun endAcceptedRelationship(id:String,state:RequestState,blocked:Boolean) {
        val c=contact(id)
        if(c.request || c.blocked) throw AppFailure(AppError.CONTACT_UNAVAILABLE)
        retainHistory(id)
        save(c.copy(request=true,blocked=blocked))
        put("app/request/$id",RequestRecord(state=state,grace=requestDeadline(),clockVersion=1))
        put("app/force-hidden-request/$id",true)
        cancelProfileIntent(id)
        records.remove("app/profile/remote/$id")
        records.remove("app/profile-peer/$id")
        records.remove("app/profile/ready/$id")
        records.remove("app/media-peer/$id")
        records.remove("app/reaction-peer/$id")
    }
    fun removeContact(id:String)=records.transaction {
        endAcceptedRelationship(id,RequestState.REJECTED,false)
    }
    private fun clearRequestPresentation(id:String) {
        val retained=read<List<String>>("app/retained-history/$id")?.toSet()
        if(retained==null) clear(id)
        else records.keys("app/message/$id/").forEach { key ->
            if(key.removePrefix("app/message/$id/") !in retained)
                delete(id,key.removePrefix("app/message/$id/"))
        }
    }
    fun finishRequest(id:String,state:RequestState)=records.transaction {
        val r=request(id); put("app/request/$id",r.copy(state=state,lastEnvelopeAt=serverNow() ?: r.lastEnvelopeAt))
        if(state!=RequestState.ACCEPTED) clearRequestPresentation(id)
    }
    fun block(id:String,blocked:Boolean)=records.transaction {
        val c=contact(id)
        if(blocked) {
            // Blocking ends access to any previously unopened or active View Once item too.
            // A later Unblock/Accept must not restore its key or body.
            records.keys("app/message/$id/").forEach { key ->
                val message=read<Message>(key) ?: throw EndpointStorageFailure()
                if(message.direction==Direction.INCOMING && message.viewOnceKind!=null &&
                    message.viewOnceState!=ViewOnceState.CONSUMED) eraseViewOnce(message)
            }
            if(!c.request && !c.blocked) endAcceptedRelationship(id,RequestState.BLOCKED,true)
            else {
                put("app/force-hidden-request/$id",true)
                if(!c.request) retainHistory(id)
                save(c.copy(request=true,blocked=true))
                cancelProfileIntent(id)
                records.remove("app/profile/remote/$id")
                records.remove("app/profile-peer/$id")
                records.remove("app/profile/ready/$id")
                if(c.request) finishRequest(id,RequestState.BLOCKED)
                else put("app/request/$id",RequestRecord(state=RequestState.BLOCKED,grace=requestDeadline(),clockVersion=1))
            }
        } else if(c.blocked) {
            // Also upgrades older blocked accepted rows: Unblock never restores acceptance.
            put("app/force-hidden-request/$id",true)
            if(!c.request) {
                retainHistory(id)
                put("app/request/$id",RequestRecord(state=RequestState.REJECTED,grace=requestDeadline(),clockVersion=1))
            }
            save(c.copy(request=true,blocked=false))
            if(request(id).state==RequestState.BLOCKED) finishRequest(id,RequestState.REJECTED)
        }
    }
    fun isBlocked(id:String)=records.transaction {contact(id).blocked}
    fun listBlocked()=records.transaction {
        contacts().filter {it.blocked}.sortedWith(compareBy<Contact> {
            it.blockedLabel.lowercase(java.util.Locale.ROOT)
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
    fun acceptRequest(id:String, onAccepted:()->Unit = {}) {
        val failure=records.transaction {
            expireRequests()
            val r=request(id)
            when {
                r.state!=RequestState.PENDING || requestExpired(id) -> "request_expired"
                r.grace.boot!=clock.now().boot && serverNow()==null -> "request_time_unavailable"
                else -> {save(contact(id).copy(request=false));finishRequest(id,RequestState.ACCEPTED)
                    records.write("app/profile/ready/$id",byteArrayOf(1));onAccepted();null}
            }
        }
        // Throw outside the transaction so rejecting Accept cannot roll back expiry cleanup.
        require(failure==null) {failure!!}
    }
    // Profile intent and acceptance are committed together in the encrypted endpoint store.
    fun profileIntent(id:String,submission:String)=records.transaction {
        val old=records.read("app/profile-sync/$id")?.decodeToString()
        if(old!=null && old!=submission && RandomIdentifiers.valid(old))
            records.write("app/profile-cancel/$old",byteArrayOf(1))
        records.write("app/profile-sync/$id",submission.encodeToByteArray())
    }
    private fun cancelProfileIntent(id:String) {
        records.read("app/profile-sync/$id")?.decodeToString()?.takeIf(RandomIdentifiers::valid)?.let {
            records.write("app/profile-cancel/$it",byteArrayOf(1))
        }
        records.remove("app/profile-sync/$id")
    }
    fun profileCancellations():Set<String> = records.transaction {
        records.keys("app/profile-cancel/").map {it.removePrefix("app/profile-cancel/")}.toSet()
    }
    fun profileCancellationDone(submission:String)=records.transaction {records.remove("app/profile-cancel/$submission")}
    fun profileReady(id:String):Boolean=records.transaction {
        isActiveContact(id) && records.read("app/profile/ready/$id")?.contentEquals(byteArrayOf(1))==true
    }
    fun peerAcceptedProfile(id:String)=records.transaction {
        if(isActiveContact(id)) records.write("app/profile/ready/$id",byteArrayOf(1))
    }
    fun profileIntents():Map<String,String> = records.transaction {
        records.keys("app/profile-sync/").associate { key ->
            val peer=key.removePrefix("app/profile-sync/")
            val submission=records.read(key)?.decodeToString() ?: throw EndpointStorageFailure()
            if(!RandomIdentifiers.valid(peer) || !RandomIdentifiers.valid(submission)) throw EndpointStorageFailure()
            peer to submission
        }
    }
    fun profileSynced(id:String,submission:String)=records.transaction {
        val key="app/profile-sync/$id"
        if(records.read(key)?.decodeToString()==submission) records.remove(key)
    }
    fun localProfile():LocalProfile=records.transaction {read<LocalProfile>("app/profile/local") ?: LocalProfile()}
    fun updateLocalProfile(about:String?=null,photo:ByteArray?=null,changePhoto:Boolean=false,
        sharing:Boolean?=null,nameChanged:Boolean=false):LocalProfile=records.transaction {
        val old=localProfile()
        val next=old.copy(revision=Math.addExact(old.revision,1),
            about=about?.let(ProfileRules::about) ?: old.about,
            photo=if(changePhoto) photo?.copyOf()?.also(ProfileRules::photo) else old.photo,
            sharing=sharing ?: old.sharing)
        if(!nameChanged && old.about==next.about && old.photo.contentEqualsNullable(next.photo) && old.sharing==next.sharing) return@transaction old
        put("app/profile/local",next)
        contacts().filter {profileReady(it.remoteDeviceId)}.forEach {
            if(next.sharing || profilePeer(it.remoteDeviceId)) profileIntent(it.remoteDeviceId,RandomIdentifiers.create())
            else cancelProfileIntent(it.remoteDeviceId)
        }
        next
    }
    private fun ByteArray?.contentEqualsNullable(other:ByteArray?):Boolean = when {
        this==null -> other==null
        other==null -> false
        else -> contentEquals(other)
    }
    fun remoteProfile(id:String):RemoteProfile?=records.transaction {
        if(!isActiveContact(id)) null else read<RemoteProfile>("app/profile/remote/$id")
    }
    fun acceptRemoteProfile(id:String,update:ProfileUpdate):Boolean=records.transaction {
        if(!isActiveContact(id) || update.revision <= (read<RemoteProfile>("app/profile/remote/$id")?.revision ?: 0L)) return@transaction false
        val current=contact(id)
        save(current.copy(displayName=if(update.sharing) update.displayName else ""))
        put("app/profile/remote/$id",RemoteProfile(update.revision,if(update.sharing) update.about else "",
            if(update.sharing) update.photo?.copyOf() else null))
        true
    }
    fun profilePeer(id:String):Boolean=records.transaction {records.read("app/profile-peer/$id")?.contentEquals(byteArrayOf(1))==true}
    fun profilePeer(id:String,supported:Boolean)=records.transaction {
        if(supported) records.write("app/profile-peer/$id",byteArrayOf(1)) else records.remove("app/profile-peer/$id")
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
        records.remove("app/media-peer/$id")
        records.remove("app/reaction-peer/$id")
        records.remove("app/profile-peer/$id")
    }
    fun attachmentPeer(id: String, supported: Boolean) = records.transaction {
        if (supported) records.write("app/attachment-peer/$id", byteArrayOf(1)) else records.remove("app/attachment-peer/$id")
    }
    fun mediaPeer(id:String):Boolean=records.transaction { records.read("app/media-peer/$id")?.contentEquals(byteArrayOf(1))==true }
    fun mediaPeer(id:String,supported:Boolean)=records.transaction {
        if(supported) records.write("app/media-peer/$id",byteArrayOf(1)) else records.remove("app/media-peer/$id")
    }
    fun reactionPeer(id:String):Boolean=records.transaction {records.read("app/reaction-peer/$id")?.contentEquals(byteArrayOf(1))==true}
    fun reactionPeer(id:String,supported:Boolean)=records.transaction {
        if(supported) records.write("app/reaction-peer/$id",byteArrayOf(1)) else records.remove("app/reaction-peer/$id")
    }
    private fun reactionKey(id:String,target:String,mine:Boolean):String {
        require(RandomIdentifiers.valid(id) && RandomIdentifiers.valid(target))
        return "app/reaction/$id/$target/${if(mine) "mine" else "peer"}"
    }
    fun reactionTarget(id:String,target:String):Message?=records.transaction {
        if(!isActiveContact(id)) return@transaction null
        messages(id).firstOrNull {it.envelopeId==target && !it.policyEvent && it.viewOnceKind==null &&
            it.state in setOf(MessageState.RECEIVED,MessageState.SERVER_ACCEPTED,MessageState.DELIVERED,
                MessageState.DELIVERED_LOCAL_SIMULATION)}
    }
    fun nextReactionSequence(id:String,target:String):Long=records.transaction {
        val current=read<ReactionRecord>(reactionKey(id,target,true))?.sequence ?: 0L
        Math.addExact(current,1)
    }
    fun applyReaction(id:String,update:ReactionUpdate,mine:Boolean):Boolean=records.transaction {
        if(reactionTarget(id,update.targetMessageId)==null || update.sequence<=0 ||
            (update.emoji!=null && update.emoji !in ConversationPayload.reactionEmoji)) return@transaction false
        val key=reactionKey(id,update.targetMessageId,mine)
        if(update.sequence<=(read<ReactionRecord>(key)?.sequence ?: 0L)) return@transaction false
        put(key,ReactionRecord(update.sequence,update.emoji));true
    }
    fun reactionSnapshot(id:String):Map<String,List<ReactionBadge>> = records.transaction {
        val prefix="app/reaction/$id/"
        records.keys(prefix).mapNotNull {key ->
            val value=read<ReactionRecord>(key) ?: throw EndpointStorageFailure()
            value.emoji?.let {emoji -> key.removePrefix(prefix).substringBeforeLast('/') to
                ReactionBadge(emoji,key.endsWith("/mine"))}
        }.sortedBy {if(it.second.mine) 0 else 1}.groupBy({it.first},{it.second})
    }
    fun contacts(): List<Contact> = records.transaction { records.keys("app/contact/").map { read<Contact>(it) ?: throw EndpointStorageFailure() } }
    fun contact(id: String) = contacts().firstOrNull { it.remoteDeviceId == id } ?: throw AppFailure(AppError.CONTACT_UNAVAILABLE)
    fun save(contact: Contact) = records.transaction { put("app/contact/${contact.remoteDeviceId}", contact) }
    private fun editableContact(id:String):Contact {
        val contact=contact(id)
        if(contact.request || contact.blocked || !isActiveContact(id)) throw AppFailure(AppError.BLOCKED)
        return contact
    }
    fun localAlias(id:String,value:String?)=records.transaction {
        val alias=value?.trim()?.takeIf { it.isNotEmpty() }
        if(alias!=null) TextRules.displayName(alias)
        save(editableContact(id).copy(localAlias=alias))
    }
    fun pinned(id:String,value:Boolean)=records.transaction { save(editableContact(id).copy(pinned=value)) }
    fun archived(id:String,value:Boolean)=records.transaction { save(editableContact(id).copy(archived=value)) }
    fun muted(id:String,value:Boolean)=records.transaction { save(editableContact(id).copy(muted=value)) }
    fun pending(id: String): String? = records.transaction { read<String>("app/pending-card/$id") }
    fun pending(id: String, card: String) = records.transaction { put("app/pending-card/$id", card) }
    fun clearPending(id: String) = records.transaction { records.remove("app/pending-card/$id") }
    fun card(id: String): String? = records.transaction { read<String>("app/card/$id") }
    fun card(id: String, text: String) = records.transaction { put("app/card/$id", text) }
    fun messages(id: String): List<Message> = records.transaction {
        expire(id)
        records.keys("app/message/$id/").map { read<Message>(it) ?: throw EndpointStorageFailure() }.sortedBy { it.timestamp }
    }
    /** Any interrupted presentation loses its key/body before normal startup can expose UI. */
    fun finalizeInterruptedViews() = records.transaction {
        records.keys("app/message/").forEach { key ->
            val message=read<Message>(key) ?: throw EndpointStorageFailure()
            if(message.viewOnceState==ViewOnceState.REVEALING) consumeViewOnce(message.conversationId,message.localId)
        }
    }
    /** Commit this transition before returning even text to the caller. */
    fun beginViewOnce(id:String,localId:String):Message = records.transaction {
        val message=read<Message>("app/message/$id/$localId") ?: throw AppFailure(AppError.CONTACT_UNAVAILABLE)
        val contact=read<Contact>("app/contact/$id") ?: throw AppFailure(AppError.CONTACT_UNAVAILABLE)
        if(message.direction!=Direction.INCOMING || message.viewOnceKind==null ||
            message.viewOnceState!=ViewOnceState.AVAILABLE || contact.blocked || contact.request ||
            !isActiveContact(id) || message.activeExpiry?.reached(clock.now())==true ||
            (message.viewOnceKind==ViewOnceKind.PHOTO && !hasAttachment(id,localId)))
            throw AppFailure(AppError.CONTACT_UNAVAILABLE)
        save(message.copy(viewOnceState=ViewOnceState.REVEALING))
        message
    }
    fun consumeViewOnce(id:String,localId:String) = records.transaction {
        val message=read<Message>("app/message/$id/$localId") ?: return@transaction
        if(message.viewOnceKind==null || message.viewOnceState==ViewOnceState.CONSUMED) return@transaction
        if(message.viewOnceState!=ViewOnceState.REVEALING) throw AppFailure(AppError.CONTACT_UNAVAILABLE)
        eraseViewOnce(message)
    }
    private fun eraseViewOnce(message:Message) {
        val id=message.conversationId;val localId=message.localId
        save(message.copy(body="",viewOnceState=ViewOnceState.CONSUMED))
        records.remove("app/attachment/$id/$localId")
        NotificationLedger.remove(records,id,localId)
        val readKey="app/read/$id"
        val seen=read<List<String>>(readKey).orEmpty()
        if(localId !in seen) put(readKey,seen+localId)
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
    fun acceptedOutgoing(id: String, localId: String, envelopeId: String? = null) = records.transaction {
        val message = read<Message>("app/message/$id/$localId") ?: return@transaction
        if (message.direction != Direction.OUTGOING || message.state in setOf(MessageState.DELIVERED,MessageState.EXPIRED_UNDELIVERED)) return@transaction
        save(message.copy(state = MessageState.SERVER_ACCEPTED, expiry = null,
            envelopeId = envelopeId ?: message.envelopeId,
            body=if(message.viewOnceKind!=null) "" else message.body))
        if(message.viewOnceKind==ViewOnceKind.PHOTO) records.remove("app/attachment/$id/$localId")
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
    fun unreadMessages(id: String): List<Message> = records.transaction {
        val seen = read<List<String>>("app/read/$id").orEmpty().toSet()
        messages(id).filter { it.direction == Direction.INCOMING && !it.policyEvent && it.localId !in seen }
    }
    fun unreadMessageIds(id: String): Set<String> = unreadMessages(id).map { it.localId }.toSet()
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
        read<Message>("app/message/$id/$localId")?.envelopeId?.let {target ->
            records.keys("app/reaction/$id/$target/").forEach(records::remove)
        }
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
        records.keys("app/reaction/$id/").forEach(records::remove)
        records.keys("app/attachment/$id/").forEach(records::remove)
        records.keys("app/message/$id/").forEach(records::remove)
        records.remove("app/read/$id")
        NotificationLedger.clear(records, id)
        records.remove("app/retained-history/$id")
    }
    fun capacity() = records.transaction { if (records.keys("app/message/").size >= 5000) throw AppFailure(AppError.LOCAL_CAPACITY) }
    fun retainedAttachmentReferences():Set<String> = records.transaction {
        records.keys("app/message/").mapNotNull { key ->
            val message=read<Message>(key) ?: throw EndpointStorageFailure()
            if(message.viewOnceKind!=null &&
                (message.viewOnceState==ViewOnceState.CONSUMED ||
                    (message.direction==Direction.OUTGOING &&
                        message.state in setOf(MessageState.SERVER_ACCEPTED,MessageState.DELIVERED)))) null
            else key.removePrefix("app/message/")
        }.toSet()
    }
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
                    if(message!=null && message.activeExpiry?.reached(clock.now())!=true &&
                        (message.viewOnceKind==null || message.viewOnceState==ViewOnceState.REVEALING))
                        put(id to localId,message.activeExpiry)
                }
            }
        }
    }
    fun attachmentAvailable(id:String,localId:String) = records.transaction {
        val message=read<Message>("app/message/$id/$localId")
        val contact=read<Contact>("app/contact/$id")
        message!=null && message.activeExpiry?.reached(clock.now())!=true && contact!=null && isActiveContact(id) && hasAttachment(id,localId) &&
            (message.viewOnceKind==null || message.viewOnceState==ViewOnceState.REVEALING)
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
        if(message.direction==Direction.INCOMING && !message.policyEvent) {
            val contact=read<Contact>("app/contact/${message.conversationId}")
            if(contact!=null && contact.archived && !contact.request && !contact.blocked && isActiveContact(message.conversationId))
                save(contact.copy(archived=false))
        }
        // Same transaction as authenticated content and deduplication; never enqueue on FETCH alone.
        NotificationLedger.accepted(records, message)
    }
    /** Security receipt only: no plaintext, descriptor, read state or notification ledger entry. */
    fun saveEnvelopeReceipt(sender:String,id:String,hash:ByteArray)=records.transaction {
        require(records.keys("app/accepted/").size<10000)
        records.write("app/accepted/$sender/$id",hash)
    }
}
