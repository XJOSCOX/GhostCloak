package org.ghostcloak.messaging

import kotlinx.serialization.Serializable
import org.ghostcloak.crypto.EndpointRecords
import org.ghostcloak.protocol.NetworkCodec

/** Local, encrypted-store-backed, dormant group authority. No transport or UI calls this class. */
@Serializable internal data class GroupRecord(
    val state:GroupState,
    val genesis:GroupGenesis,
    val events:List<GroupTransition> = emptyList(),
    val usedInvites:List<String> = emptyList(),
    val forked:Boolean = false
)

class GroupLedger(
    private val records:EndpointRecords,
    private val trustedPeer:GroupTrustedPeer,
    private val localMemberId:String
) {
    init { require(GroupIds.valid(localMemberId)) }
    private fun key(id:String):String { require(GroupIds.valid(id)); return "group/state/v1/$id" }
    private fun load(id:String):GroupRecord?=records.read(key(id))?.let {
        require(it.size<=GroupStatements.MAX_LEDGER_BYTES)
        NetworkCodec.decode<GroupRecord>(it,GroupStatements.MAX_LEDGER_BYTES).also {record ->
            GroupStatements.validate(record.state)
            GroupStatements.validate(record.genesis.state)
            require(record.state.groupId==id && record.genesis.state.groupId==id &&
                record.genesis.state.revision==1L && record.events.size<GroupStatements.MAX_EVENTS &&
                record.usedInvites.size<=GroupStatements.MAX_INVITES &&
                record.usedInvites.distinct().size==record.usedInvites.size)
        }
    }
    private fun save(record:GroupRecord) {
        val bytes=NetworkCodec.encode(record)
        require(bytes.size<=GroupStatements.MAX_LEDGER_BYTES)
        records.write(key(record.state.groupId),bytes)
    }
    fun state(id:String):GroupState?=records.transaction { load(id)?.state }
    fun isForked(id:String):Boolean=records.transaction { load(id)?.forked==true }
    fun lifecycle(id:String):GroupLifecycle?=state(id)?.lifecycle
    fun status(id:String):GroupLocalStatus?=records.transaction {
        val record=load(id) ?: return@transaction null
        when {
            record.forked -> GroupLocalStatus.FORKED
            record.state.lifecycle==GroupLifecycle.DISSOLVED -> GroupLocalStatus.DISSOLVED
            record.state.members.any {it.memberId==localMemberId} -> GroupLocalStatus.ACTIVE
            record.events.lastOrNull()?.change?.let {it.action==GroupAction.LEAVE && it.actorId==localMemberId}==true -> GroupLocalStatus.LEFT
            record.events.any {it.change.action==GroupAction.REMOVE && it.change.targetId==localMemberId} -> GroupLocalStatus.REMOVED
            else -> GroupLocalStatus.INVITED
        }
    }

    fun acceptGenesis(genesis:GroupGenesis):GroupApply=records.transaction {
        val state=genesis.state
        GroupStatements.validate(state)
        require(state.revision==1L)
        val owner=state.members.single()
        if (!trustedPeer.matches(owner) || !GroupStatements.verify(owner.authPublicKey,
                GroupStatements.genesis(state),genesis.ownerSignature)) return@transaction GroupApply.REJECTED
        val old=load(state.groupId)
        if (old!=null) return@transaction if (GroupStatements.digest(old.genesis.state).contentEquals(
                GroupStatements.digest(state))) GroupApply.DUPLICATE else GroupApply.FORKED.also {
                    save(old.copy(forked=true))
                }
        save(GroupRecord(state,genesis))
        GroupApply.ACCEPTED
    }

    fun apply(id:String,event:GroupTransition):GroupApply=records.transaction {
        val record=load(id) ?: return@transaction GroupApply.NEEDS_RESYNC
        applyInTransaction(record,event,true).first
    }

    /** A gap requires every signed intermediate event; no unverified snapshot fast-forward. */
    fun applySnapshot(id:String,snapshot:GroupSnapshot):GroupApply=records.transaction {
        var record=load(id) ?: return@transaction GroupApply.NEEDS_RESYNC
        if (record.forked) return@transaction GroupApply.FORKED
        if (snapshot.chain.size !in 1..GroupStatements.MAX_EVENTS ||
            snapshot.finalState.groupId!=id) return@transaction GroupApply.REJECTED
        for (event in snapshot.chain) {
            val (result,next)=applyInTransaction(record,event,false)
            if (result==GroupApply.FORKED) {
                save(record.copy(forked=true))
                return@transaction result
            }
            if ((result!=GroupApply.ACCEPTED && result!=GroupApply.REMOVED) || next==null) return@transaction result
            record=next
            if (record.state.members.none {it.memberId==localMemberId}) {
                save(record)
                return@transaction GroupApply.REMOVED
            }
        }
        if (!runCatching {GroupStatements.digest(record.state).contentEquals(
                GroupStatements.digest(snapshot.finalState))}.getOrDefault(false))
            return@transaction GroupApply.REJECTED
        save(record)
        GroupApply.ACCEPTED
    }

    private fun applyInTransaction(record:GroupRecord,event:GroupTransition,persist:Boolean):Pair<GroupApply,GroupRecord?> {
        val previous=record.state
        if (record.forked) return GroupApply.FORKED to null
        if (runCatching {GroupStatements.validateEventShape(event)}.isFailure) return GroupApply.REJECTED to null
        if (previous.lifecycle==GroupLifecycle.DISSOLVED ||
            (previous.members.none {it.memberId==localMemberId} &&
                !(event.change.action==GroupAction.ADD && event.change.added?.memberId==localMemberId)))
            return GroupApply.REJECTED to null
        val next=event.next
        if (next.groupId!=previous.groupId) return GroupApply.REJECTED to null
        val nextDigest=runCatching {GroupStatements.digest(next)}.getOrNull() ?: return GroupApply.REJECTED to null
        if (next.revision==previous.revision) {
            if (nextDigest.contentEquals(GroupStatements.digest(previous))) return GroupApply.DUPLICATE to null
            // Check a rival against this revision's original parent, not its accepted child.
            val parent=if (record.events.size==1) record.genesis.state else record.events.getOrNull(record.events.size-2)?.next
            val usedBeforeLast=record.usedInvites.toMutableSet().also { used ->
                record.events.lastOrNull()?.change?.invite?.inviteId?.let(used::remove)
            }
            if (parent!=null && next.previousDigest.contentEquals(GroupStatements.digest(parent)) &&
                authorizedSignatures(parent,event) && runCatching {
                    GroupStatements.digest(GroupRules.derive(parent,event.change,usedBeforeLast,trustedPeer))
                        .contentEquals(nextDigest)
                }.getOrDefault(false)) {
                if (persist) save(record.copy(forked=true))
                return GroupApply.FORKED to null
            }
            return GroupApply.REJECTED to null
        }
        if (next.revision<previous.revision) return GroupApply.STALE to null
        if (next.revision>previous.revision+1) return GroupApply.NEEDS_RESYNC to null
        if (!next.previousDigest.contentEquals(GroupStatements.digest(previous))) {
            if (authorizedSignatures(previous,event)) {
                if (persist) save(record.copy(forked=true))
                return GroupApply.FORKED to null
            }
            return GroupApply.REJECTED to null
        }
        if (!authorizedSignatures(previous,event)) return GroupApply.REJECTED to null
        if (record.events.any {it.change.eventId==event.change.eventId}) return GroupApply.REJECTED to null
        val derived=runCatching {GroupRules.derive(previous,event.change,record.usedInvites.toSet(),trustedPeer)}
            .getOrNull() ?: return GroupApply.REJECTED to null
        if (!GroupStatements.digest(derived).contentEquals(nextDigest)) return GroupApply.REJECTED to null
        if (record.events.size>=GroupStatements.MAX_EVENTS-1) return GroupApply.REJECTED to null
        val inviteId=event.change.invite?.inviteId
        val used=if(inviteId==null) record.usedInvites else record.usedInvites+inviteId
        if (used.size>GroupStatements.MAX_INVITES) return GroupApply.REJECTED to null
        val updated=record.copy(state=next,events=record.events+event,usedInvites=used)
        if (persist) save(updated)
        return (if(next.members.none {it.memberId==localMemberId}) GroupApply.REMOVED else GroupApply.ACCEPTED) to updated
    }

    private fun authorizedSignatures(previous:GroupState,event:GroupTransition):Boolean {
        val actor=previous.members.singleOrNull {it.memberId==event.change.actorId} ?: return false
        val coordinator=previous.members.singleOrNull {it.memberId==previous.coordinatorId} ?: return false
        if (!trustedPeer.matches(actor) || !trustedPeer.matches(coordinator)) return false
        val actorStatement=runCatching {GroupStatements.actor(previous,event.change,event.next)}.getOrNull() ?: return false
        if (!GroupStatements.verify(actor.authPublicKey,actorStatement,event.actorSignature) ||
            !GroupStatements.verify(coordinator.authPublicKey,
                GroupStatements.coordinator(previous,event.change,event.next),event.coordinatorSignature)) return false
        if (event.change.action==GroupAction.TRANSFER_OWNER) {
            val target=previous.members.singleOrNull {it.memberId==event.change.targetId} ?: return false
            if (!GroupStatements.verify(target.authPublicKey,
                    GroupStatements.transferAcceptance(previous,event.change,event.next),event.targetSignature)) return false
        } else if (event.targetSignature.isNotEmpty()) return false
        return true
    }
}

/** Deterministic transition derivation; signatures never make an unauthorized change valid. */
object GroupRules {
    fun derive(previous:GroupState,change:GroupChange,usedInvites:Set<String>,trusted:GroupTrustedPeer):GroupState {
        GroupStatements.validate(previous)
        GroupStatements.validateChange(change)
        require(previous.lifecycle==GroupLifecycle.ACTIVE)
        val actor=previous.members.single {it.memberId==change.actorId}
        val target=previous.members.singleOrNull {it.memberId==change.targetId}
        fun owner()=require(actor.role==GroupRole.OWNER)
        fun privileged()=require(actor.role==GroupRole.OWNER || actor.role==GroupRole.ADMIN)
        fun pureTarget()=require(change.added==null && change.invite==null && change.newProfileRevision==null &&
            change.newProfileDigest==null && change.newTimer==null && change.targetId!=null)
        var members=previous.members
        var ownerId=previous.ownerId
        var coordinatorId=previous.coordinatorId
        var epoch=previous.epoch
        var profileRevision=previous.profileRevision
        var profileDigest=previous.profileDigest
        var timer=previous.disappearingSeconds
        var lifecycle=previous.lifecycle
        when(change.action) {
            GroupAction.ADD -> {
                privileged();require(change.targetId==null && change.newProfileRevision==null &&
                    change.newProfileDigest==null && change.newTimer==null && members.size<GroupStatements.MAX_MEMBERS)
                val added=requireNotNull(change.added)
                val invite=requireNotNull(change.invite)
                require(added.role==GroupRole.MEMBER && added.joinedEpoch==epoch+1 &&
                    GroupStatements.digestMember(added).contentEquals(GroupStatements.digestMember(invite.target)) &&
                    invite.inviterId==actor.memberId && trusted.matches(added) &&
                    GroupStatements.verifyInvite(invite,previous,usedInvites))
                require(members.none {it.memberId==added.memberId || it.accountId==added.accountId ||
                    it.deviceId==added.deviceId})
                members=members+added;epoch++
            }
            GroupAction.REMOVE -> {
                privileged();pureTarget();require(target!=null && target.memberId!=actor.memberId &&
                    target.role!=GroupRole.OWNER && (actor.role==GroupRole.OWNER || target.role==GroupRole.MEMBER))
                require(target.memberId!=coordinatorId)
                members=members.filterNot {it.memberId==target.memberId};epoch++
            }
            GroupAction.LEAVE -> {
                pureTarget();require(change.targetId==actor.memberId && actor.role!=GroupRole.OWNER &&
                    actor.memberId!=coordinatorId)
                members=members.filterNot {it.memberId==actor.memberId};epoch++
            }
            GroupAction.PROMOTE -> {
                owner();pureTarget();require(target?.role==GroupRole.MEMBER)
                members=members.map {if(it.memberId==target.memberId) it.copy(role=GroupRole.ADMIN) else it};epoch++
            }
            GroupAction.DEMOTE -> {
                owner();pureTarget();require(target?.role==GroupRole.ADMIN && target.memberId!=coordinatorId)
                members=members.map {if(it.memberId==target.memberId) it.copy(role=GroupRole.MEMBER) else it};epoch++
            }
            GroupAction.TRANSFER_OWNER -> {
                owner();pureTarget();require(target!=null && target.memberId!=actor.memberId)
                members=members.map {when(it.memberId) {
                    actor.memberId -> it.copy(role=GroupRole.ADMIN)
                    target.memberId -> it.copy(role=GroupRole.OWNER)
                    else -> it
                }}
                ownerId=target.memberId;epoch++
            }
            GroupAction.DELEGATE_COORDINATOR -> {
                owner();pureTarget();require(target!=null && target.memberId!=coordinatorId &&
                    target.role!=GroupRole.MEMBER)
                coordinatorId=target.memberId;epoch++
            }
            GroupAction.PROFILE -> {
                privileged();require(change.targetId==null && change.added==null && change.invite==null &&
                    change.newTimer==null && change.newProfileRevision==profileRevision+1 &&
                    change.newProfileDigest?.size==32)
                profileRevision++;profileDigest=change.newProfileDigest!!
            }
            GroupAction.TIMER -> {
                privileged();require(change.targetId==null && change.added==null && change.invite==null &&
                    change.newProfileRevision==null && change.newProfileDigest==null && change.newTimer!=null)
                DisappearingTimer.from(change.newTimer)
                require(change.newTimer!=timer);timer=change.newTimer
            }
            GroupAction.DISSOLVE -> {
                owner();require(change.targetId==null && change.added==null && change.invite==null &&
                    change.newProfileRevision==null && change.newProfileDigest==null && change.newTimer==null)
                lifecycle=GroupLifecycle.DISSOLVED;epoch++
            }
        }
        val next=previous.copy(revision=previous.revision+1,epoch=epoch,ownerId=ownerId,
            coordinatorId=coordinatorId,members=members.sortedBy {it.memberId},
            previousDigest=GroupStatements.digest(previous),profileRevision=profileRevision,
            profileDigest=profileDigest,disappearingSeconds=timer,lifecycle=lifecycle)
        GroupStatements.validate(next)
        return next
    }
}
