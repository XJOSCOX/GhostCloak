package org.ghostcloak.messaging

import kotlinx.serialization.Serializable
import org.ghostcloak.crypto.EndpointRecords
import org.ghostcloak.protocol.DeviceAuth
import org.ghostcloak.protocol.NetworkCodec
import java.security.MessageDigest

/** A6.2 wire objects are independent of the frozen membership v1 formats. */
@Serializable data class GroupGovernanceActivationProposalV1(
    val version:Int=1,val activationId:String,val groupId:String,val stateRevision:Long,
    val stateDigest:ByteArray,val baselineCertificateDigest:ByteArray,val memberSetDigest:ByteArray,
    val ownerId:String,val coordinatorId:String,val initialSequence:Long=0,
    val initialHeadVersion:Int=1,val initialHeadMaterial:ByteArray,
    val ownerSignature:ByteArray=byteArrayOf(),val coordinatorSignature:ByteArray=byteArrayOf(),
) { override fun toString()="GroupGovernanceActivationProposalV1(redacted)" }

@Serializable data class GroupGovernanceActivationAckV1(
    val version:Int=1,val proposalDigest:ByteArray,val activationId:String,val groupId:String,
    val stateDigest:ByteArray,val memberSetDigest:ByteArray,val approverId:String,
    val signature:ByteArray,
) { override fun toString()="GroupGovernanceActivationAckV1(redacted)" }

@Serializable data class GroupGovernanceActivationCommitV1(
    val version:Int=1,val proposal:GroupGovernanceActivationProposalV1,
    val acks:List<GroupGovernanceActivationAckV1>,val digest:ByteArray,
) { override fun toString()="GroupGovernanceActivationCommitV1(redacted)" }

@Serializable data class GroupGovernanceInstalledAckV1(
    val version:Int=1,val activationDigest:ByteArray,val groupId:String,val memberId:String,
    val stateRevision:Long,val stateDigest:ByteArray,val signature:ByteArray,
) { override fun toString()="GroupGovernanceInstalledAckV1(redacted)" }

@Serializable data class GroupGovernanceReadyV1(
    val version:Int=1,val commit:GroupGovernanceActivationCommitV1,
    val installed:List<GroupGovernanceInstalledAckV1>,val digest:ByteArray,
) { override fun toString()="GroupGovernanceReadyV1(redacted)" }

@Serializable data class GroupGovernanceEntryV1(
    val version:Int=1,val groupId:String,val activationDigest:ByteArray,val sequence:Long,
    val previousHeadDigest:ByteArray,val eventId:String,val preRevision:Long,
    val preDigest:ByteArray,val actorId:String,val action:GroupAction,
    val transitionDigest:ByteArray,val postRevision:Long,val postDigest:ByteArray,
    val transition:GroupTransition,val actorSignature:ByteArray,val coordinatorSignature:ByteArray,
) { override fun toString()="GroupGovernanceEntryV1(redacted)" }

@Serializable data class GroupGovernanceEntryAppliedAckV1(
    val version:Int=1,val groupId:String,val activationDigest:ByteArray,val sequence:Long,
    val entryDigest:ByteArray,val memberId:String,val signature:ByteArray,
) { override fun toString()="GroupGovernanceEntryAppliedAckV1(redacted)" }

internal object GroupGovernanceV1 {
    const val MAX_PROPOSAL_BYTES=1024
    const val MAX_ACK_BYTES=512
    const val MAX_COMMIT_BYTES=4096
    const val MAX_READY_BYTES=6144
    const val MAX_ENTRY_BYTES=GroupStatements.MAX_EVENT_BYTES+1024
    private fun same(a:ByteArray,b:ByteArray)=MessageDigest.isEqual(a,b)
    private fun digest(bytes:ByteArray)=DeviceAuth.digest(bytes)
    private fun body(value:GroupGovernanceActivationProposalV1)=
        NetworkCodec.encode(value.copy(ownerSignature=byteArrayOf(),coordinatorSignature=byteArrayOf()))
    fun proposalStatement(value:GroupGovernanceActivationProposalV1,owner:Boolean)=
        GroupStatements.governanceStatement(if(owner) "GhostCloak.GroupGovernanceActivationOwner.v1"
            else "GhostCloak.GroupGovernanceActivationCoordinator.v1",body(value))
    fun proposalDigest(value:GroupGovernanceActivationProposalV1)=digest(NetworkCodec.encode(value))
    fun unsignedProposal(state:GroupState,certificateDigest:ByteArray,activationId:String):
        GroupGovernanceActivationProposalV1 {
        val stateDigest=GroupStatements.digest(state)
        return GroupGovernanceActivationProposalV1(activationId=activationId,groupId=state.groupId,
            stateRevision=state.revision,stateDigest=stateDigest,
            baselineCertificateDigest=certificateDigest,
            memberSetDigest=GroupAuthorityBaselineV1.memberSetDigest(
                GroupAuthorityBaselineV1.memberSet(state)),ownerId=state.ownerId,
            coordinatorId=state.coordinatorId,initialHeadMaterial=digest(
                "GhostCloak.GroupGovernanceInitialHead.v1".encodeToByteArray()+
                    activationId.encodeToByteArray()+stateDigest+certificateDigest))
    }
    fun verifyProposal(value:GroupGovernanceActivationProposalV1,state:GroupState,
        certificateDigest:ByteArray,requireOwner:Boolean=true):Boolean=runCatching {
        require(value.version==1 && GroupIds.valid(value.activationId) && value.groupId==state.groupId &&
            value.stateRevision==state.revision && state.lifecycle==GroupLifecycle.ACTIVE &&
            value.ownerId==state.ownerId && value.coordinatorId==state.coordinatorId &&
            value.initialSequence==0L && value.initialHeadVersion==1 &&
            value.stateDigest.size==32 && value.baselineCertificateDigest.size==32 &&
            value.memberSetDigest.size==32 && value.initialHeadMaterial.size==32 &&
            same(value.stateDigest,GroupStatements.digest(state)) &&
            same(value.baselineCertificateDigest,certificateDigest) &&
            same(value.memberSetDigest,GroupAuthorityBaselineV1.memberSetDigest(
                GroupAuthorityBaselineV1.memberSet(state))) &&
            same(value.initialHeadMaterial,unsignedProposal(state,certificateDigest,
                value.activationId).initialHeadMaterial) &&
            NetworkCodec.encode(value).size<=MAX_PROPOSAL_BYTES)
        val owner=state.members.single {it.memberId==state.ownerId}
        val coordinator=state.members.single {it.memberId==state.coordinatorId}
        require(GroupStatements.verify(coordinator.authPublicKey,proposalStatement(value,false),
            value.coordinatorSignature))
        if(requireOwner) require(GroupStatements.verify(owner.authPublicKey,
            proposalStatement(value,true),value.ownerSignature))
        true
    }.getOrDefault(false)
    fun unsignedAck(proposal:GroupGovernanceActivationProposalV1,memberId:String)=
        GroupGovernanceActivationAckV1(proposalDigest=proposalDigest(proposal),
            activationId=proposal.activationId,groupId=proposal.groupId,
            stateDigest=proposal.stateDigest,memberSetDigest=proposal.memberSetDigest,
            approverId=memberId,signature=byteArrayOf())
    fun ackStatement(value:GroupGovernanceActivationAckV1)=GroupStatements.governanceStatement(
        "GhostCloak.GroupGovernanceActivationAck.v1",NetworkCodec.encode(value.copy(signature=byteArrayOf())))
    fun verifyAck(value:GroupGovernanceActivationAckV1,
        proposal:GroupGovernanceActivationProposalV1,state:GroupState):Boolean=runCatching {
        require(value.version==1 && value.groupId==proposal.groupId &&
            value.activationId==proposal.activationId && same(value.proposalDigest,proposalDigest(proposal)) &&
            same(value.stateDigest,proposal.stateDigest) &&
            same(value.memberSetDigest,proposal.memberSetDigest) &&
            NetworkCodec.encode(value).size<=MAX_ACK_BYTES)
        val member=state.members.single {it.memberId==value.approverId}
        require(GroupStatements.verify(member.authPublicKey,ackStatement(value),value.signature))
        true
    }.getOrDefault(false)
    fun commit(proposal:GroupGovernanceActivationProposalV1,acks:Collection<GroupGovernanceActivationAckV1>):
        GroupGovernanceActivationCommitV1 {
        val ordered=acks.sortedBy {it.approverId}
        return GroupGovernanceActivationCommitV1(proposal=proposal,acks=ordered,
            digest=digest(NetworkCodec.encode(proposal)+NetworkCodec.encode(ordered)))
    }
    fun verifyCommit(value:GroupGovernanceActivationCommitV1,state:GroupState,
        certificateDigest:ByteArray):Boolean=runCatching {
        require(value.version==1 && verifyProposal(value.proposal,state,certificateDigest) &&
            value.acks.size==state.members.size &&
            value.acks.map {it.approverId}==state.members.map {it.memberId}.sorted() &&
            value.acks.all {verifyAck(it,value.proposal,state)} &&
            same(value.digest,commit(value.proposal,value.acks).digest) &&
            NetworkCodec.encode(value).size<=MAX_COMMIT_BYTES)
        true
    }.getOrDefault(false)
    fun unsignedInstalled(commit:GroupGovernanceActivationCommitV1,memberId:String)=
        GroupGovernanceInstalledAckV1(activationDigest=commit.digest,groupId=commit.proposal.groupId,
            memberId=memberId,stateRevision=commit.proposal.stateRevision,
            stateDigest=commit.proposal.stateDigest,signature=byteArrayOf())
    fun installedStatement(value:GroupGovernanceInstalledAckV1)=GroupStatements.governanceStatement(
        "GhostCloak.GroupGovernanceInstalledAck.v1",NetworkCodec.encode(value.copy(signature=byteArrayOf())))
    fun verifyInstalled(value:GroupGovernanceInstalledAckV1,
        commit:GroupGovernanceActivationCommitV1,state:GroupState):Boolean=runCatching {
        require(value.version==1 && value.groupId==state.groupId &&
            same(value.activationDigest,commit.digest) &&
            value.stateRevision==state.revision && same(value.stateDigest,GroupStatements.digest(state)) &&
            NetworkCodec.encode(value).size<=MAX_ACK_BYTES)
        val member=state.members.single {it.memberId==value.memberId}
        require(GroupStatements.verify(member.authPublicKey,installedStatement(value),value.signature))
        true
    }.getOrDefault(false)
    fun ready(commit:GroupGovernanceActivationCommitV1,
        installed:Collection<GroupGovernanceInstalledAckV1>):GroupGovernanceReadyV1 {
        val ordered=installed.sortedBy {it.memberId}
        return GroupGovernanceReadyV1(commit=commit,installed=ordered,
            digest=digest(NetworkCodec.encode(commit)+NetworkCodec.encode(ordered)))
    }
    fun verifyReady(value:GroupGovernanceReadyV1,state:GroupState,
        baselineDigest:ByteArray):Boolean=runCatching {
        require(value.version==1 && verifyCommit(value.commit,state,baselineDigest) &&
            value.installed.size==state.members.size &&
            value.installed.map {it.memberId}==state.members.map {it.memberId}.sorted() &&
            value.installed.all {verifyInstalled(it,value.commit,state)} &&
            same(value.digest,ready(value.commit,value.installed).digest) &&
            NetworkCodec.encode(value).size<=MAX_READY_BYTES)
        true
    }.getOrDefault(false)
    fun entryBody(value:GroupGovernanceEntryV1)=NetworkCodec.encode(value.copy(
        actorSignature=byteArrayOf(),coordinatorSignature=byteArrayOf()))
    fun actorStatement(value:GroupGovernanceEntryV1)=GroupStatements.governanceStatement(
        "GhostCloak.GroupGovernanceActor.v1",entryBody(value))
    fun coordinatorStatement(value:GroupGovernanceEntryV1)=GroupStatements.governanceStatement(
        "GhostCloak.GroupGovernanceCoordinator.v1",entryBody(value))
    fun entryDigest(value:GroupGovernanceEntryV1)=digest(NetworkCodec.encode(value))
    fun verifyEntry(value:GroupGovernanceEntryV1,pre:GroupState,
        head:GovernanceHeadFoundationV1):Boolean=runCatching {
        require(value.version==1 && value.groupId==pre.groupId &&
            same(value.activationDigest,head.activationDigest) && value.sequence==head.sequence+1 &&
            same(value.previousHeadDigest,head.headDigest) && value.preRevision==pre.revision &&
            same(value.preDigest,GroupStatements.digest(pre)) &&
            value.eventId==value.transition.change.eventId &&
            value.actorId==value.transition.change.actorId && value.action==value.transition.change.action &&
            same(value.transitionDigest,digest(NetworkCodec.encode(value.transition))) &&
            value.postRevision==value.transition.next.revision &&
            same(value.postDigest,GroupStatements.digest(value.transition.next)) &&
            NetworkCodec.encode(value).size<=MAX_ENTRY_BYTES)
        val actor=pre.members.single {it.memberId==value.actorId}
        val coordinator=pre.members.single {it.memberId==pre.coordinatorId}
        require(GroupStatements.verify(actor.authPublicKey,actorStatement(value),value.actorSignature) &&
            GroupStatements.verify(coordinator.authPublicKey,coordinatorStatement(value),
                value.coordinatorSignature))
        true
    }.getOrDefault(false)
    fun unsignedApplied(entry:GroupGovernanceEntryV1,memberId:String)=
        GroupGovernanceEntryAppliedAckV1(groupId=entry.groupId,
            activationDigest=entry.activationDigest,sequence=entry.sequence,
            entryDigest=entryDigest(entry),memberId=memberId,signature=byteArrayOf())
    fun appliedStatement(value:GroupGovernanceEntryAppliedAckV1)=GroupStatements.governanceStatement(
        "GhostCloak.GroupGovernanceEntryAppliedAck.v1",
        NetworkCodec.encode(value.copy(signature=byteArrayOf())))
    fun verifyApplied(value:GroupGovernanceEntryAppliedAckV1,entry:GroupGovernanceEntryV1):Boolean=
        runCatching {
            require(value.version==1 && value.groupId==entry.groupId &&
                same(value.activationDigest,entry.activationDigest) && value.sequence==entry.sequence &&
                same(value.entryDigest,entryDigest(entry)) &&
                NetworkCodec.encode(value).size<=MAX_ACK_BYTES)
            val member=entry.transition.next.members.single {it.memberId==value.memberId}
            require(GroupStatements.verify(member.authPublicKey,appliedStatement(value),value.signature))
            true
        }.getOrDefault(false)
}

@Serializable internal data class PendingGovernanceActivationV1(
    val proposal:GroupGovernanceActivationProposalV1,
    val acks:List<GroupGovernanceActivationAckV1> = emptyList(),
    val installed:List<GroupGovernanceInstalledAckV1> = emptyList(),
)
@Serializable internal data class PendingGovernanceEntryV1(
    val entry:GroupGovernanceEntryV1,
    val applied:List<GroupGovernanceEntryAppliedAckV1> = emptyList(),
)

/** Protected and bounded. Callers use EndpointRecords transactions for cross-record atomicity. */
internal class GroupGovernanceStore(private val records:EndpointRecords) {
    private fun key(id:String,kind:String):String {require(GroupIds.valid(id));return "app/group/governance-v1/$kind/$id"}
    private inline fun <reified T> read(id:String,kind:String,max:Int):T?=records.transaction {
        records.read(key(id,kind))?.let {NetworkCodec.decode<T>(it,max)}
    }
    private inline fun <reified T> write(id:String,kind:String,value:T,max:Int)=records.transaction {
        val bytes=NetworkCodec.encode(value)
        require(bytes.size in 1..max)
        val path=key(id,kind)
        require(records.read(path)!=null || records.keys("app/group/governance-v1/$kind/").size<64)
        records.write(path,bytes)
    }
    fun pending(id:String)=read<PendingGovernanceActivationV1>(id,"pending",MAX_PENDING)
    fun savePending(value:PendingGovernanceActivationV1) {
        require(value.acks.size<=GroupStatements.MAX_MEMBERS && value.installed.size<=GroupStatements.MAX_MEMBERS)
        write(value.proposal.groupId,"pending",value,MAX_PENDING)
    }
    fun pendingGroups()=records.transaction {records.keys("app/group/governance-v1/pending/").take(64)
        .map {it.removePrefix("app/group/governance-v1/pending/")}.filter(GroupIds::valid)}
    fun own(id:String)=read<GroupGovernanceActivationAckV1>(id,"own",GroupGovernanceV1.MAX_ACK_BYTES)
    fun saveOwn(value:GroupGovernanceActivationAckV1)=write(value.groupId,"own",value,GroupGovernanceV1.MAX_ACK_BYTES)
    fun commit(id:String)=read<GroupGovernanceActivationCommitV1>(id,"commit",GroupGovernanceV1.MAX_COMMIT_BYTES)
    fun saveCommit(value:GroupGovernanceActivationCommitV1)=write(value.proposal.groupId,"commit",value,GroupGovernanceV1.MAX_COMMIT_BYTES)
    fun installed(id:String)=read<GroupGovernanceInstalledAckV1>(id,"installed",GroupGovernanceV1.MAX_ACK_BYTES)
    fun saveInstalled(value:GroupGovernanceInstalledAckV1)=write(value.groupId,"installed",value,GroupGovernanceV1.MAX_ACK_BYTES)
    fun ready(id:String)=read<GroupGovernanceReadyV1>(id,"ready",GroupGovernanceV1.MAX_READY_BYTES)
    fun saveReady(value:GroupGovernanceReadyV1)=write(value.commit.proposal.groupId,"ready",value,GroupGovernanceV1.MAX_READY_BYTES)
    fun outstanding(id:String)=read<PendingGovernanceEntryV1>(id,"entry",MAX_PENDING)
    fun saveOutstanding(value:PendingGovernanceEntryV1) {
        require(value.applied.size<=GroupStatements.MAX_MEMBERS)
        write(value.entry.groupId,"entry",value,MAX_PENDING)
    }
    fun clearOutstanding(id:String)=records.transaction {records.remove(key(id,"entry"))}
    fun clearSent(id:String,phase:String)=records.transaction {
        require((phase.startsWith("entry-") && phase.removePrefix("entry-").toLongOrNull()!=null) ||
            (phase.startsWith("entry-ack-") && phase.removePrefix("entry-ack-").toLongOrNull()!=null))
        records.keys("app/group/governance-v1/sent/$id/$phase/").forEach(records::remove)
    }
    fun sent(id:String,phase:String,recipient:String):Boolean=records.transaction {
        records.read(sentKey(id,phase,recipient))!=null
    }
    fun markSent(id:String,phase:String,recipient:String,outboxId:String)=records.transaction {
        val path=sentKey(id,phase,recipient)
        require(records.read(path)!=null || records.keys("app/group/governance-v1/sent/$id/").size<64)
        records.write(path,outboxId.encodeToByteArray())
    }
    private fun sentKey(id:String,phase:String,recipient:String):String {
        require(phase in setOf("capability","owner-request","owner-response","proposal","ack","commit",
            "installed","ready") ||
            (phase.startsWith("entry-") && phase.removePrefix("entry-").toLongOrNull()!=null) ||
            (phase.startsWith("entry-ack-") && phase.removePrefix("entry-ack-").toLongOrNull()!=null))
        require(GroupIds.valid(id) && org.ghostcloak.identity.RandomIdentifiers.valid(recipient))
        return "app/group/governance-v1/sent/$id/$phase/$recipient"
    }
    fun outstandingGroups()=records.transaction {
        records.keys("app/group/governance-v1/entry/").take(64)
            .map {it.removePrefix("app/group/governance-v1/entry/")}.filter(GroupIds::valid)
    }
    companion object {const val MAX_PENDING=12_000}
}
