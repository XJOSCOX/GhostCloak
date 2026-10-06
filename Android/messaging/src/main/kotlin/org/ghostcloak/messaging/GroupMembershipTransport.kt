package org.ghostcloak.messaging

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import org.ghostcloak.crypto.EndpointRecords
import org.ghostcloak.crypto.SecureSessionEngine
import org.ghostcloak.crypto.CryptoFailure
import org.ghostcloak.crypto.CryptoError
import org.ghostcloak.identity.RandomIdentifiers
import org.ghostcloak.protocol.ApiFailure
import org.ghostcloak.protocol.DeviceAuth
import org.ghostcloak.protocol.NetworkCodec
import java.security.MessageDigest

/** Signed membership and text fan-out over existing authenticated pairwise Signal sessions. */
class GroupMembershipTransport(
    private val records:EndpointRecords,
    private val repository:LocalRepository,
    private val engine:SecureSessionEngine,
    private val network:EndpointNetworkState,
    private val authority:GroupAuthorityResolver,
    private val outbox:DurableOutbox,
) {
    private val acceptanceMutex = Mutex()
    private val textMutex = Mutex()
    private val chats=GroupChatStore(records)
    data class Conversation(val groupId:String,val status:GroupLocalStatus,val memberCount:Int,
        val memberDevices:Map<String,String>,val messages:List<GroupChatMessage>,val invitationPending:Boolean=false)
    // Retry scheduling is only a transport throttle. The durable marker, not this clock,
    // is the authority for whether the group still needs a signed chain.
    private var lastResyncRequestNanos = 0L
    @Serializable private data class Incoming(val sender:String,val offer:GroupControl,val status:InviteState)
    @Serializable private data class Outgoing(val target:String,val offer:GroupControl,val used:Boolean=false)
    private enum class InviteState { OFFERED, ACCEPTING, DECLINED, EXPIRED }
    data class Invitation(val id:String,val senderDeviceId:String,val memberCount:Int,val accepting:Boolean,
        val groupId:String)
    private fun memberKey(id:String):String {require(GroupIds.valid(id));return "app/group/member/$id"}
    private fun incomingKey(id:String):String {require(GroupIds.valid(id));return "app/group/incoming/$id"}
    private fun outgoingKey(id:String):String {require(GroupIds.valid(id));return "app/group/outgoing/$id"}
    private fun admissionKey(id:String):String {require(GroupIds.valid(id));return "app/group/admission/$id"}
    private fun noticeKey(id:String):String {require(GroupIds.valid(id));return "app/group/notice/$id"}
    private fun fanoutKey(id:String,revision:Long,device:String):String {
        require(GroupIds.valid(id) && revision in 1..GroupStatements.MAX_EVENTS.toLong() && RandomIdentifiers.valid(device))
        return "app/group/fanout/$id/$revision/$device"
    }
    private fun localMember(id:String):String?=records.transaction {records.read(memberKey(id))?.decodeToString()}
    private fun ledger(id:String,trusted:GroupTrustedPeer):GroupLedger=GroupLedger(records,trusted,
        localMember(id) ?: throw ApiFailure(409,"group_unavailable"))
    private suspend fun ownMember(role:GroupRole,epoch:Long,memberId:String=GroupIds.create()):GroupMember {
        val identity=records.transaction {
            val account=network.accountId();val device=network.ownDevice()
            account to device
        }
        // This is the existing local Signal identity; no new identity or group key is created.
        val signal=engine.createIdentity("Local").publicKey
        return GroupMember(memberId,identity.first,identity.second,network.registeredGroupPublicKey(),
            DeviceAuth.digest(signal),role,epoch)
    }
    private suspend fun trusted(members:Collection<GroupMember>):GroupTrustedPeer {
        val ownAccount=network.accountId();val ownDevice=network.ownDevice()
        val ownKey=network.registeredGroupPublicKey()
        val ownIdentity=engine.createIdentity("Local")
        val ownDigest=DeviceAuth.digest(ownIdentity.publicKey)
        val valid=HashMap<String,GroupMember>()
        for(member in members.distinctBy {it.deviceId}) {
            val matches=if(member.deviceId==ownDevice) member.accountId==ownAccount &&
                MessageDigest.isEqual(member.authPublicKey,ownKey) &&
                MessageDigest.isEqual(member.signalIdentityDigest,ownDigest)
            else {
                if(!repository.isActiveContact(member.deviceId)) false
                else authority.resolveTrustedGroupAuthority(member.deviceId).let {binding ->
                    binding.accountId==member.accountId && binding.deviceId==member.deviceId &&
                        MessageDigest.isEqual(binding.authPublicKey,member.authPublicKey) &&
                        MessageDigest.isEqual(binding.identityDigest,member.signalIdentityDigest)
                }
            }
            if(!matches) throw ApiFailure(409,"group_binding_unavailable")
            valid[member.memberId]=member
        }
        return GroupTrustedPeer {member -> valid[member.memberId]?.let {
            it.accountId==member.accountId && it.deviceId==member.deviceId &&
                MessageDigest.isEqual(it.authPublicKey,member.authPublicKey) &&
                MessageDigest.isEqual(it.signalIdentityDigest,member.signalIdentityDigest)
        }==true}
    }
    private suspend fun send(target:String,control:GroupControl,committed:(String)->Unit = {}):String {
        if(!repository.isActiveContact(target) || !repository.groupPeer(target))
            throw ApiFailure(409,"group_peer_unavailable")
        val plaintext=ConversationPayload.encodeGroup(control)
        return try {outbox.enqueue(target,plaintext,committed)} finally {plaintext.fill(0)}
    }

    /** Creates a group with a one-member genesis, then offers the first one-use invitation. */
    suspend fun createAndInvite(targetDeviceId:String):String {
        if(targetDeviceId==network.ownDevice() || !repository.isActiveContact(targetDeviceId) ||
            !repository.groupPeer(targetDeviceId))
            throw ApiFailure(409,"group_peer_unavailable")
        val owner=ownMember(GroupRole.OWNER,1)
        val group=GroupState(GroupIds.create(),1,1,owner.memberId,owner.memberId,listOf(owner),ByteArray(32))
        val genesis=GroupGenesis(group,network.signGroupStatement(GroupStatements.genesis(group)))
        val binding=authority.resolveTrustedGroupAuthority(targetDeviceId)
        val target=GroupMember(GroupIds.create(),binding.accountId,binding.deviceId,binding.authPublicKey,
            binding.identityDigest,GroupRole.MEMBER,2)
        val trusted=trusted(listOf(owner,target))
        val inviteId=GroupIds.create()
        val admissionBody=GroupStatements.admission(group,inviteId,target)
        val proof=GroupAdmission(group,inviteId,target,network.signGroupStatement(admissionBody),
            network.signGroupStatement(admissionBody))
        val unsigned=GroupInvite(inviteId,group.groupId,GroupStatements.digest(group),1,1,owner.memberId,
            target,byteArrayOf(),byteArrayOf())
        val offer=unsigned.copy(inviterSignature=network.signGroupStatement(GroupStatements.invite(unsigned)))
        check(GroupStatements.verifyAdmission(proof,trusted) && GroupStatements.verifyOffer(offer,group,trusted))
        val control=GroupControl(kind=GroupControlKind.INVITE,groupId=group.groupId,inviteId=inviteId,
            state=group,admission=proof,invite=offer)
        send(targetDeviceId,control) {
            check(records.keys("app/group/member/").size<64 && records.keys("app/group/outgoing/").size<128)
            records.write(memberKey(group.groupId),owner.memberId.toByteArray())
            check(GroupLedger(records,trusted,owner.memberId).acceptGenesis(genesis)==GroupApply.ACCEPTED)
            records.write(outgoingKey(inviteId),NetworkCodec.encode(Outgoing(targetDeviceId,control)))
        }
        return group.groupId
    }

    /** Invites a directly accepted, group-capable peer to an already active local group. */
    suspend fun invite(groupId:String,targetDeviceId:String):String {
        if(targetDeviceId==network.ownDevice() || !repository.isActiveContact(targetDeviceId) ||
            !repository.groupPeer(targetDeviceId))
            throw ApiFailure(409,"group_peer_unavailable")
        val localId=localMember(groupId) ?: throw ApiFailure(404,"group_unavailable")
        val state=GroupLedger(records,GroupTrustedPeer {false},localId).state(groupId)
            ?: throw ApiFailure(404,"group_unavailable")
        if(state.members.size>=GroupStatements.MAX_MEMBERS || state.members.any {it.deviceId==targetDeviceId} ||
            state.lifecycle!=GroupLifecycle.ACTIVE ||
            state.coordinatorId!=localId ||
            GroupLedger(records,GroupTrustedPeer {false},localId).isForked(groupId))
            throw ApiFailure(409,"group_unavailable")
        val binding=authority.resolveTrustedGroupAuthority(targetDeviceId)
        if(state.members.any {it.accountId==binding.accountId}) throw ApiFailure(409,"group_unavailable")
        val target=GroupMember(GroupIds.create(),binding.accountId,binding.deviceId,binding.authPublicKey,
            binding.identityDigest,GroupRole.MEMBER,state.epoch+1)
        val trusted=trusted(state.members+target)
        val inviteId=GroupIds.create()
        val body=GroupStatements.admission(state,inviteId,target)
        val ownSignature=network.signGroupStatement(body)
        val proof=GroupAdmission(state,inviteId,target,
            if(state.ownerId==localId) ownSignature else byteArrayOf(),ownSignature)
        val unsigned=GroupInvite(inviteId,groupId,GroupStatements.digest(state),state.revision,state.epoch,
            localId,target,byteArrayOf(),byteArrayOf())
        val offer=unsigned.copy(inviterSignature=network.signGroupStatement(GroupStatements.invite(unsigned)))
        check(GroupStatements.verifyOffer(offer,state,trusted))
        val coSign=state.ownerId!=localId
        if(!coSign) check(GroupStatements.verifyAdmission(proof,trusted))
        val control=GroupControl(kind=if(coSign) GroupControlKind.ADMISSION_REQUEST else GroupControlKind.INVITE,
            groupId=groupId,inviteId=inviteId,
            state=state,admission=proof,invite=offer)
        val recipient=if(coSign) state.members.single {it.memberId==state.ownerId}.deviceId else targetDeviceId
        send(recipient,control) {
            check(records.keys("app/group/outgoing/").size<128)
            if(coSign) records.write(admissionKey(inviteId),NetworkCodec.encode(Outgoing(targetDeviceId,control)))
            else records.write(outgoingKey(inviteId),NetworkCodec.encode(Outgoing(targetDeviceId,control)))
        }
        return inviteId
    }

    fun invitations():List<Invitation> = records.transaction {
        records.keys("app/group/incoming/").mapNotNull {key ->
            val record=NetworkCodec.decode<Incoming>(records.read(key) ?: return@mapNotNull null,16_384)
            if(record.status==InviteState.DECLINED || record.status==InviteState.EXPIRED) null else Invitation(key.removePrefix("app/group/incoming/"),
                record.sender,record.offer.state!!.members.size,record.status==InviteState.ACCEPTING,
                record.offer.groupId)
        }
    }
    /** 0 = unseen, 1 = reserved before OS publication, 2 = shown or foreground-suppressed. */
    fun pendingInvitationNotices():List<Pair<String,Boolean>> = records.transaction {
        records.keys("app/group/incoming/").mapNotNull {key ->
            val id=key.removePrefix("app/group/incoming/")
            val invite=records.read(key)?.let {NetworkCodec.decode<Incoming>(it,16_384)}
            if(invite?.status!=InviteState.OFFERED) return@mapNotNull null
            val stage=records.read(noticeKey(id))?.firstOrNull()?.toInt() ?: 0
            if(stage==2) null else id to (stage==1)
        }
    }
    fun markInvitationNotice(id:String,complete:Boolean)=records.transaction {
        require(records.read(incomingKey(id))!=null)
        records.write(noticeKey(id),byteArrayOf(if(complete) 2 else 1))
    }
    fun status(groupId:String):GroupLocalStatus? = localMember(groupId)?.let { memberId ->
        GroupLedger(records,GroupTrustedPeer {false},memberId).status(groupId)
    }
    fun state(groupId:String):GroupState? = localMember(groupId)?.let { memberId ->
        GroupLedger(records,GroupTrustedPeer {false},memberId).state(groupId)
    }
    fun forkedCount():Int = records.transaction {
        records.keys("app/group/member/").count { key ->
            val id=key.removePrefix("app/group/member/")
            val member=records.read(key)?.decodeToString() ?: return@count false
            GroupLedger(records,GroupTrustedPeer {false},member).isForked(id)
        }
    }
    fun conversations():List<Conversation> {
        val visibleInvitations=invitations().map {it.groupId}.toSet()
        return chats.groups().mapNotNull {id ->
        val current=state(id) ?: return@mapNotNull null
        val status=status(id) ?: return@mapNotNull null
        if(status==GroupLocalStatus.INVITED && id !in visibleInvitations) return@mapNotNull null
        val pending=records.transaction {
            records.keys("app/group/outgoing/").any { key ->
                val offer=NetworkCodec.decode<Outgoing>(records.read(key) ?: return@any false,16_384)
                offer.offer.groupId==id && !offer.used
            } || records.keys("app/group/admission/").any { key ->
                val offer=NetworkCodec.decode<Outgoing>(records.read(key) ?: return@any false,16_384)
                offer.offer.groupId==id
            }
        }
        Conversation(id,status,current.members.size,current.members.associate {it.memberId to it.deviceId},chats.messages(id),pending)
        }
    }
    fun conversation(id:String):Conversation?=conversations().firstOrNull {it.groupId==id}
    /** Persist one logical message and its immutable recipient set before any pairwise enqueue. */
    suspend fun sendText(groupId:String,text:String):String = textMutex.withLock {
        val localId=localMember(groupId) ?: throw ApiFailure(409,"group_unavailable")
        val ledger=GroupLedger(records,GroupTrustedPeer {false},localId)
        val current=ledger.state(groupId) ?: throw ApiFailure(409,"group_unavailable")
        if(ledger.status(groupId)!=GroupLocalStatus.ACTIVE || current.lifecycle!=GroupLifecycle.ACTIVE ||
            current.members.size<2 || current.members.none {it.memberId==localId})
            throw ApiFailure(409,"group_unavailable")
        trusted(listOf(current.members.single {it.memberId==localId}))
        val logicalId=GroupIds.create()
        val payload=GroupText(groupId=groupId,epoch=current.epoch,senderMemberId=localId,
            logicalId=logicalId,text=text)
        GroupTextCodec.validate(payload)
        chats.create(GroupChatMessage(groupId,logicalId,current.epoch,localId,true,text,
            chats.nextOrder(),current.members.filter {it.memberId!=localId}.map {GroupRecipient(it.deviceId)}))
        logicalId
    }
    /** Internal membership foundation; no group chat or role-management UI is exposed. */
    suspend fun removeMember(groupId:String,targetMemberId:String) = textMutex.withLock {
        val localId=localMember(groupId) ?: throw ApiFailure(404,"group_unavailable")
        val ledger=GroupLedger(records,GroupTrustedPeer {false},localId)
        val current=ledger.state(groupId) ?: throw ApiFailure(404,"group_unavailable")
        if(ledger.isForked(groupId) || current.coordinatorId!=localId ||
            current.members.none {it.memberId==targetMemberId}) throw ApiFailure(409,"group_unavailable")
        val trusted=trusted(current.members)
        val change=GroupChange(GroupIds.create(),GroupAction.REMOVE,localId,targetId=targetMemberId)
        val next=try {GroupRules.derive(current,change,emptySet(),trusted)}
            catch(_:IllegalArgumentException) {throw ApiFailure(409,"group_unavailable")}
        val event=GroupTransition(change,next,network.signGroupStatement(GroupStatements.actor(current,change,next)),
            network.signGroupStatement(GroupStatements.coordinator(current,change,next)))
        val result=GroupLedger(records,trusted,localId).apply(groupId,event) {
            val removedDevice=current.members.single {it.memberId==targetMemberId}.deviceId
            chats.markRemoved(groupId,removedDevice)
            chats.cancelStale(groupId,next.epoch)
            records.keys("app/group/fanout/$groupId/").filter {it.endsWith("/$removedDevice")}
                .forEach(records::remove)
            for(member in next.members.filter {it.memberId!=localId})
                records.write(fanoutKey(groupId,next.revision,member.deviceId),GroupControlCodec.encode(
                    GroupControl(kind=GroupControlKind.STATE_UPDATE,groupId=groupId,transition=event)))
        }
        if(result!=GroupApply.ACCEPTED) throw ApiFailure(409,"group_unavailable")
        flushFanout()
    }
    suspend fun accept(inviteId:String) {
        val incoming=records.transaction {records.read(incomingKey(inviteId))?.let {
            NetworkCodec.decode<Incoming>(it,16_384)
        }} ?: throw ApiFailure(404,"group_invite_unavailable")
        if(incoming.status in setOf(InviteState.DECLINED,InviteState.EXPIRED)) throw ApiFailure(409,"group_invite_unavailable")
        if(incoming.status==InviteState.ACCEPTING) return
        val offer=incoming.offer
        val invite=offer.invite ?: throw ApiFailure(409,"group_invite_unavailable")
        val state=offer.state ?: throw ApiFailure(409,"group_invite_unavailable")
        if(offer.admission?.let {GroupStatements.digest(it.state).contentEquals(GroupStatements.digest(state))}!=true)
            throw ApiFailure(409,"group_invite_unavailable")
        val trusted=trusted(state.members+invite.target)
        if(!GroupStatements.verifyOffer(invite,state,trusted)) throw ApiFailure(409,"group_invite_unavailable")
        val signed=invite.copy(targetAcceptance=network.signGroupStatement(GroupStatements.acceptance(invite)))
        val control=GroupControl(kind=GroupControlKind.ACCEPT,groupId=state.groupId,inviteId=inviteId,invite=signed)
        send(incoming.sender,control) {
            val latest=records.read(incomingKey(inviteId))?.let {NetworkCodec.decode<Incoming>(it,16_384)}
                ?: throw ApiFailure(409,"group_invite_unavailable")
            require(latest.status==InviteState.OFFERED)
            records.write(incomingKey(inviteId),NetworkCodec.encode(latest.copy(status=InviteState.ACCEPTING)))
        }
    }
    fun decline(inviteId:String)=records.transaction {
        val old=records.read(incomingKey(inviteId))?.let {NetworkCodec.decode<Incoming>(it,16_384)}
            ?: throw ApiFailure(404,"group_invite_unavailable")
        if(old.status==InviteState.ACCEPTING) throw ApiFailure(409,"group_invite_unavailable")
        records.write(incomingKey(inviteId),NetworkCodec.encode(old.copy(status=InviteState.DECLINED)))
    }

    /** Pending plaintext never reaches UI before fresh P13.2A binding checks succeed. */
    suspend fun processPending() = textMutex.withLock {
        for((id,pending) in repository.pendingGroupControls()) {
            if(!repository.isActiveContact(pending.senderDeviceId)) {
                repository.finishGroupControl(id);continue
            }
            try {
                when(pending.control.kind) {
                    GroupControlKind.INVITE -> receiveInvite(pending.senderDeviceId,pending.control)
                    GroupControlKind.ACCEPT -> receiveAcceptance(pending.senderDeviceId,pending.control)
                    GroupControlKind.INVITE_EXPIRED -> receiveInviteExpired(pending.senderDeviceId,pending.control)
                    GroupControlKind.STATE_UPDATE -> receiveState(pending.senderDeviceId,pending.control)
                    GroupControlKind.RESYNC_REQUEST -> receiveResyncRequest(pending.senderDeviceId,pending.control)
                    GroupControlKind.RESYNC_RESPONSE -> receiveResyncResponse(pending.senderDeviceId,pending.control)
                    GroupControlKind.ADMISSION_REQUEST -> receiveAdmissionRequest(pending.senderDeviceId,pending.control)
                    GroupControlKind.ADMISSION_RESPONSE -> receiveAdmissionResponse(pending.senderDeviceId,pending.control)
                }
                repository.finishGroupControl(id)
            } catch(e:CancellationException) {throw e}
            catch(e:ApiFailure) {
                // A binding lookup, auth renewal, or backend outage must not silently consume
                // an already authenticated control. A later sync retries it after trust repair.
                if(e.status in setOf(401,429,500,502,503,504)) continue
                repository.finishGroupControl(id)
            } catch(_:IllegalArgumentException) {repository.finishGroupControl(id)}
        }
        flushFanout()
        retryMarkedResync()
        processPendingTexts()
        flushTexts()
    }
    private suspend fun processPendingTexts() {
        for((envelopeId,pending) in chats.pending()) {
            val value=pending.text
            val localId=localMember(value.groupId)
            if(localId==null) {chats.discardPending(envelopeId);continue}
            val ledger=GroupLedger(records,GroupTrustedPeer {false},localId)
            val current=ledger.state(value.groupId)
            if(current==null || ledger.status(value.groupId)!=GroupLocalStatus.ACTIVE) {
                chats.discardPending(envelopeId);continue
            }
            if(value.epoch>current.epoch) {
                records.transaction {records.write("app/group/resync/${value.groupId}",byteArrayOf(1))}
                continue // Only a complete signed resync chain can make this message displayable.
            }
            if(value.epoch!=current.epoch || !GroupMessageContext(value.groupId,value.epoch,
                    value.senderMemberId,value.logicalId).allowedBy(current)) {
                chats.discardPending(envelopeId);continue
            }
            val sender=current.members.singleOrNull {it.memberId==value.senderMemberId}
            if(sender==null || sender.deviceId!=pending.senderDeviceId ||
                !repository.isActiveContact(sender.deviceId)) {
                chats.discardPending(envelopeId);continue
            }
            try {trusted(listOf(sender))}
            catch(e:CancellationException) {throw e}
            catch(_:ApiFailure) {continue} // Keep ciphertext-local pending record for later trust repair.
            chats.accept(envelopeId,pending)
        }
    }
    private suspend fun flushTexts() {
        var budget=8
        for(groupId in chats.groups()) {
            if(budget<=0) break
            val localId=localMember(groupId) ?: continue
            val ledger=GroupLedger(records,GroupTrustedPeer {false},localId)
            val current=ledger.state(groupId) ?: continue
            if(ledger.status(groupId)!=GroupLocalStatus.ACTIVE) continue
            for(message in chats.messages(groupId).filter {it.outgoing}) {
                if(budget<=0) break
                // Epoch changes never reinterpret queued old-epoch text as current content.
                if(message.epoch!=current.epoch) {chats.cancelStale(groupId,current.epoch);continue}
                for(recipient in message.recipients) {
                    if(budget<=0) break
                    if(recipient.state !in setOf(GroupRecipientState.PENDING,GroupRecipientState.QUEUED)) continue
                    // Include trust failures and offline peers in the bound; no single
                    // recipient may monopolize a sync pass.
                    budget--
                    if(recipient.state==GroupRecipientState.PENDING) {
                        if(current.members.none {it.deviceId==recipient.deviceId} ||
                            !repository.isActiveContact(recipient.deviceId) || !repository.groupPeer(recipient.deviceId)) {
                            chats.markUnavailable(groupId,message.logicalId,recipient.deviceId);continue
                        }
                        val member=current.members.single {it.deviceId==recipient.deviceId}
                        try {trusted(listOf(member))} catch(e:CancellationException) {throw e}
                        catch(_:ApiFailure) {continue}
                        val payload=ConversationPayload.encodeGroupText(GroupText(groupId=groupId,
                            epoch=message.epoch,senderMemberId=localId,logicalId=message.logicalId,text=message.text))
                        try {outbox.enqueue(recipient.deviceId,payload) {id ->
                            chats.markQueued(groupId,message.logicalId,recipient.deviceId,id)
                        }} catch(e:CancellationException) {throw e}
                        catch(_:ApiFailure) {continue}
                        finally {payload.fill(0)}
                    }
                    val slot=chats.message(groupId,message.logicalId)?.recipients?.singleOrNull {
                        it.deviceId==recipient.deviceId
                    } ?: continue
                    if(slot.state!=GroupRecipientState.QUEUED || slot.outboxId==null) continue
                    val currentMember=current.members.singleOrNull {it.deviceId==recipient.deviceId} ?: continue
                    try {trusted(listOf(currentMember))} catch(e:CancellationException) {throw e}
                    catch(_:ApiFailure) {continue}
                    val result=try {outbox.process(slot.outboxId)}
                        catch(e:CancellationException) {throw e}
                        catch(e:ApiFailure) {if(e.status==429) return else continue}
                        catch(e:CryptoFailure) {
                            if(e.error in setOf(CryptoError.IdentityChanged,CryptoError.UnknownSession,
                                    CryptoError.ReauthenticationRequired)) continue
                            throw e
                        }
                    if(result.state in setOf(OutboxState.SERVER_ACCEPTED,OutboxState.FAILED,OutboxState.SUBMISSION_EXPIRED)) {
                        chats.markResult(slot.outboxId,result.state==OutboxState.SERVER_ACCEPTED)
                        outbox.removeFinished(slot.outboxId)
                    }
                }
            }
        }
    }
    /** Delegated coordinator asks the online owner to co-sign this exact current-state admission. */
    private suspend fun receiveAdmissionRequest(sender:String,control:GroupControl) {
        val state=control.state ?: throw ApiFailure(400,"group_invalid")
        val proof=control.admission ?: throw ApiFailure(400,"group_invalid")
        val invite=control.invite ?: throw ApiFailure(400,"group_invalid")
        val localId=localMember(state.groupId) ?: throw ApiFailure(409,"group_invalid")
        val ledger=GroupLedger(records,GroupTrustedPeer {false},localId)
        val current=ledger.state(state.groupId) ?: throw ApiFailure(409,"group_invalid")
        if(localId!=current.ownerId || current.coordinatorId==localId ||
            current.members.single {it.memberId==current.coordinatorId}.deviceId!=sender ||
            ledger.isForked(state.groupId) || current.members.size>=GroupStatements.MAX_MEMBERS ||
            !GroupStatements.digest(current).contentEquals(GroupStatements.digest(state)) ||
            proof.ownerSignature.isNotEmpty() ||
            !repository.isActiveContact(invite.target.deviceId) || !repository.groupPeer(invite.target.deviceId))
            throw ApiFailure(409,"group_invalid")
        val trusted=trusted(state.members+invite.target)
        val coordinator=state.members.single {it.memberId==state.coordinatorId}
        val body=GroupStatements.admission(state,proof.inviteId,proof.target)
        if(invite.inviterId!=coordinator.memberId ||
            !GroupStatements.verify(coordinator.authPublicKey,body,proof.coordinatorSignature) ||
            !GroupStatements.verifyOffer(invite,state,trusted)) throw ApiFailure(409,"group_invalid")
        val signed=proof.copy(ownerSignature=network.signGroupStatement(body))
        check(GroupStatements.verifyAdmission(signed,trusted))
        send(sender,control.copy(kind=GroupControlKind.ADMISSION_RESPONSE,admission=signed))
    }
    private suspend fun receiveAdmissionResponse(sender:String,control:GroupControl) {
        val id=control.inviteId ?: throw ApiFailure(400,"group_invalid")
        val pending=records.transaction {records.read(admissionKey(id))?.let {
            NetworkCodec.decode<Outgoing>(it,16_384)
        }} ?: throw ApiFailure(409,"group_invalid")
        val original=pending.offer
        val state=control.state ?: throw ApiFailure(400,"group_invalid")
        val proof=control.admission ?: throw ApiFailure(400,"group_invalid")
        val invite=control.invite ?: throw ApiFailure(400,"group_invalid")
        val localId=localMember(state.groupId) ?: throw ApiFailure(409,"group_invalid")
        val ledger=GroupLedger(records,GroupTrustedPeer {false},localId)
        val current=ledger.state(state.groupId) ?: throw ApiFailure(409,"group_invalid")
        if(current.coordinatorId!=localId || ledger.isForked(state.groupId) ||
            current.members.single {it.memberId==current.ownerId}.deviceId!=sender ||
            !GroupStatements.digest(current).contentEquals(GroupStatements.digest(state)) ||
            !NetworkCodec.encode(original.invite!!).contentEquals(NetworkCodec.encode(invite)) ||
            !NetworkCodec.encode(original.admission!!).contentEquals(
                NetworkCodec.encode(proof.copy(ownerSignature=byteArrayOf()))) ||
            pending.target!=invite.target.deviceId) throw ApiFailure(409,"group_invalid")
        val trusted=trusted(state.members+invite.target)
        if(!GroupStatements.verifyAdmission(proof,trusted) || !GroupStatements.verifyOffer(invite,state,trusted))
            throw ApiFailure(409,"group_invalid")
        val finished=control.copy(kind=GroupControlKind.INVITE)
        send(pending.target,finished) {
            records.remove(admissionKey(id))
            records.write(outgoingKey(id),NetworkCodec.encode(Outgoing(pending.target,finished)))
        }
    }
    private suspend fun receiveInvite(sender:String,control:GroupControl) {
        val invite=control.invite ?: throw ApiFailure(400,"group_invalid")
        val state=control.state ?: throw ApiFailure(400,"group_invalid")
        val proof=control.admission ?: throw ApiFailure(400,"group_invalid")
        val own=ownMember(GroupRole.MEMBER,state.epoch+1,invite.target.memberId)
        if(!GroupStatements.digestMember(own).contentEquals(GroupStatements.digestMember(invite.target)) ||
            proof.target.memberId!=own.memberId ||
            state.members.none {it.memberId==invite.inviterId && it.deviceId==sender})
            throw ApiFailure(409,"group_invalid")
        val trusted=trusted(state.members+invite.target)
        if(!GroupStatements.verifyAdmission(proof,trusted) || !GroupStatements.verifyOffer(invite,state,trusted))
            throw ApiFailure(409,"group_invalid")
        records.transaction {
            val prior=records.read(incomingKey(invite.inviteId))
            if(prior!=null) return@transaction
            if(records.keys("app/group/incoming/").any {key ->
                    records.read(key)?.let {NetworkCodec.decode<Incoming>(it,16_384)}?.let {old ->
                        old.offer.groupId==state.groupId && old.status in setOf(InviteState.OFFERED,InviteState.ACCEPTING)
                    }==true
                }) throw ApiFailure(409,"group_invalid")
            if(records.keys("app/group/incoming/").size>=128) throw ApiFailure(429,"group_capacity")
            records.write(memberKey(state.groupId),own.memberId.toByteArray())
            if(GroupLedger(records,trusted,own.memberId).acceptAdmission(proof)!=GroupApply.ACCEPTED)
                throw ApiFailure(409,"group_invalid")
            records.write(incomingKey(invite.inviteId),NetworkCodec.encode(Incoming(sender,control,InviteState.OFFERED)))
        }
    }
    private fun receiveInviteExpired(sender:String,control:GroupControl) = records.transaction {
        val id=control.inviteId ?: throw ApiFailure(400,"group_invalid")
        val current=records.read(incomingKey(id))?.let {NetworkCodec.decode<Incoming>(it,16_384)}
            ?: return@transaction
        if(current.sender!=sender || current.offer.groupId!=control.groupId)
            throw ApiFailure(409,"group_invalid")
        if(current.status in setOf(InviteState.OFFERED,InviteState.ACCEPTING))
            records.write(incomingKey(id),NetworkCodec.encode(current.copy(status=InviteState.EXPIRED)))
    }
    private suspend fun receiveAcceptance(sender:String,control:GroupControl) = acceptanceMutex.withLock {
        val invite=control.invite ?: throw ApiFailure(400,"group_invalid")
        val outgoing=records.transaction {records.read(outgoingKey(invite.inviteId))?.let {
            NetworkCodec.decode<Outgoing>(it,16_384)
        }} ?: throw ApiFailure(409,"group_invalid")
        if(outgoing.used || outgoing.target!=sender || invite.target.deviceId!=sender ||
            !MessageDigest.isEqual(GroupStatements.digestMember(invite.target),
                GroupStatements.digestMember(outgoing.offer.invite!!.target)) ||
            !MessageDigest.isEqual(DeviceAuth.digest(GroupStatements.invite(invite)),
                DeviceAuth.digest(GroupStatements.invite(outgoing.offer.invite))) ||
            !MessageDigest.isEqual(invite.inviterSignature,outgoing.offer.invite.inviterSignature))
            throw ApiFailure(409,"group_invalid")
        val localId=localMember(control.groupId) ?: throw ApiFailure(409,"group_invalid")
        val current=GroupLedger(records,GroupTrustedPeer {false},localId).state(control.groupId)
            ?: throw ApiFailure(409,"group_invalid")
        val trusted=trusted(current.members+invite.target)
        if(current.coordinatorId!=localId || invite.inviterId!=localId ||
            GroupLedger(records,trusted,localId).inviteUsed(control.groupId,invite.inviteId))
            throw ApiFailure(409,"group_invalid")
        if(!GroupStatements.verifyInvite(invite,current,emptySet()) ||
            current.members.size>=GroupStatements.MAX_MEMBERS) {
            send(sender,GroupControl(kind=GroupControlKind.INVITE_EXPIRED,groupId=control.groupId,
                inviteId=invite.inviteId)) {
                records.write(outgoingKey(invite.inviteId),NetworkCodec.encode(outgoing.copy(used=true)))
            }
            return
        }
        val change=GroupChange(GroupIds.create(),GroupAction.ADD,localId,added=invite.target,invite=invite)
        val next=GroupRules.derive(current,change,emptySet(),trusted)
        val event=GroupTransition(change,next,network.signGroupStatement(GroupStatements.actor(current,change,next)),
            network.signGroupStatement(GroupStatements.coordinator(current,change,next)))
        val result=GroupLedger(records,trusted,localId).apply(control.groupId,event) {
            chats.cancelStale(control.groupId,next.epoch)
            records.write(outgoingKey(invite.inviteId),NetworkCodec.encode(outgoing.copy(used=true)))
            for(member in next.members.filter {it.memberId!=localId})
                records.write(fanoutKey(next.groupId,next.revision,member.deviceId),
                    GroupControlCodec.encode(GroupControl(kind=GroupControlKind.STATE_UPDATE,
                        groupId=next.groupId,transition=event)))
        }
        if(result!=GroupApply.ACCEPTED) throw ApiFailure(409,"group_invalid")
    }
    private suspend fun receiveState(sender:String,control:GroupControl) {
        val event=control.transition ?: throw ApiFailure(400,"group_invalid")
        val localId=localMember(control.groupId) ?: throw ApiFailure(409,"group_invalid")
        val local=GroupLedger(records,GroupTrustedPeer {false},localId)
        val prior=local.state(control.groupId) ?: throw ApiFailure(409,"group_invalid")
        if(prior.members.none {it.memberId==prior.coordinatorId && it.deviceId==sender})
            throw ApiFailure(409,"group_invalid")
        val candidates=prior.members+listOfNotNull(event.change.added)
        val trusted=trusted(candidates)
        val result=GroupLedger(records,trusted,localId).apply(control.groupId,event) {
            chats.cancelStale(control.groupId,event.next.epoch)
        }
        if(result==GroupApply.NEEDS_RESYNC) requestResync(control.groupId,prior)
        else if(result==GroupApply.FORKED) records.transaction {
            records.write("app/group/resync/${control.groupId}",byteArrayOf(1))
        }
        else if(result==GroupApply.ACCEPTED && event.change.action==GroupAction.ADD &&
            event.change.added?.memberId==localId) records.transaction {
            records.remove(incomingKey(event.change.invite!!.inviteId))
        }
    }
    private suspend fun requestResync(groupId:String,prior:GroupState) {
        val coordinator=prior.members.single {it.memberId==prior.coordinatorId}
        if(coordinator.deviceId==network.ownDevice()) return
        records.transaction {records.write("app/group/resync/$groupId",byteArrayOf(1))}
        send(coordinator.deviceId,GroupControl(kind=GroupControlKind.RESYNC_REQUEST,groupId=groupId,
            fromRevision=prior.revision,fromDigest=GroupStatements.digest(prior)))
        lastResyncRequestNanos=System.nanoTime()
    }
    private suspend fun retryMarkedResync() {
        val now=System.nanoTime()
        if(lastResyncRequestNanos!=0L && now-lastResyncRequestNanos in 0 until 60_000_000_000L) return
        for(key in records.transaction {records.keys("app/group/resync/")}) {
            val groupId=key.removePrefix("app/group/resync/")
            val memberId=runCatching {localMember(groupId)}.getOrNull() ?: continue
            val ledger=GroupLedger(records,GroupTrustedPeer {false},memberId)
            if(ledger.isForked(groupId)) continue // A valid fork has no in-group winner.
            val current=ledger.state(groupId) ?: continue
            try {requestResync(groupId,current); return}
            catch(e:CancellationException) {throw e}
            catch(_:ApiFailure) {continue} // Keep the durable marker and fail closed.
        }
    }
    private suspend fun receiveResyncRequest(sender:String,control:GroupControl) {
        val localId=localMember(control.groupId) ?: throw ApiFailure(409,"group_invalid")
        val ledger=GroupLedger(records,GroupTrustedPeer {false},localId)
        val current=ledger.state(control.groupId) ?: throw ApiFailure(409,"group_invalid")
        if(ledger.isForked(control.groupId) || current.coordinatorId!=localId ||
            current.members.none {it.deviceId==sender} ||
            !MessageDigest.isEqual(ledger.digestAtRevision(control.groupId,control.fromRevision!!)
                ?: throw ApiFailure(409,"group_invalid"),control.fromDigest)) throw ApiFailure(409,"group_invalid")
        val remaining=ledger.transitionsAfter(control.groupId,control.fromRevision)
            ?: throw ApiFailure(409,"group_invalid")
        if(remaining.isEmpty()) return
        var batch=emptyList<GroupTransition>()
        for(event in remaining) {
            val candidate=batch+event
            val response=GroupControl(kind=GroupControlKind.RESYNC_RESPONSE,groupId=control.groupId,
                state=event.next,chain=candidate,headRevision=current.revision)
            if(runCatching {ConversationPayload.encodeGroup(response)}.isFailure) break
            batch=candidate
        }
        if(batch.isEmpty()) throw ApiFailure(409,"group_invalid")
        send(sender,GroupControl(kind=GroupControlKind.RESYNC_RESPONSE,groupId=control.groupId,
            state=batch.last().next,chain=batch,headRevision=current.revision))
    }
    private suspend fun receiveResyncResponse(sender:String,control:GroupControl) {
        val localId=localMember(control.groupId) ?: throw ApiFailure(409,"group_invalid")
        val prior=GroupLedger(records,GroupTrustedPeer {false},localId).state(control.groupId)
            ?: throw ApiFailure(409,"group_invalid")
        if(prior.members.none {it.memberId==prior.coordinatorId && it.deviceId==sender})
            throw ApiFailure(409,"group_invalid")
        val members=prior.members+control.chain.flatMap {it.next.members}
        val trusted=trusted(members)
        val ledger=GroupLedger(records,trusted,localId)
        val result=ledger.applySnapshot(control.groupId,GroupSnapshot(control.state!!,control.chain))
        if(result!=GroupApply.ACCEPTED) throw ApiFailure(409,"group_invalid")
        chats.cancelStale(control.groupId,control.state.epoch)
        if(control.state.revision<control.headRevision!!) requestResync(control.groupId,control.state)
        else records.transaction {records.remove("app/group/resync/${control.groupId}")}
    }
    private suspend fun flushFanout() {
        val pending=records.transaction {records.keys("app/group/fanout/")}
        var staged=0
        for(key in pending) {
            if(staged>=16) break
            val device=key.substringAfterLast('/')
            if(!repository.isActiveContact(device) || !repository.groupPeer(device)) continue
            val bytes=records.transaction {records.read(key)} ?: continue
            val control=GroupControlCodec.decode(bytes)
            try {
                send(device,control) {records.remove(key)}
                staged++
            } catch(e:CancellationException) {throw e}
            catch(_:ApiFailure) {
                // Leave this recipient's intent durable; another member's state update
                // must not be held behind its unavailable or changed pairwise session.
            }
        }
    }
}
