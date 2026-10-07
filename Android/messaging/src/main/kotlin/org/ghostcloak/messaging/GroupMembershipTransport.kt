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
    private val currentAuthority=GroupCurrentAuthority(records)
    private val admissionV2=AdmissionV2Store(records)
    private val historicalAuthority=HistoricalGroupAuthority(records)
    private val baselineV1=GroupAuthorityBaselineStore(records)
    private val governanceV1=GroupGovernanceStore(records)
    private val governanceJournal=GroupGovernanceJournalV1(records)
    private val governedAdmission=GovernedAdmissionStore(records)
    private val policyCheckpoints=GroupGovernancePolicyCheckpointStoreV1(records)
    private val governedChanges=GroupGovernedChangeStoreV1(records)
    private val inviteDelegations=GroupInviteDelegationStoreV1(records)
    data class Conversation(val groupId:String,val status:GroupLocalStatus,val memberCount:Int,
        val memberDevices:Map<String,String>,val messages:List<GroupChatMessage>,
        val invitationPending:Boolean=false,val sendRestriction:String?=null,
        val info:GroupInfo?=null)
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
    private fun baselineState(groupId:String):Pair<GroupState,String> {
        val localId=localMember(groupId) ?: throw ApiFailure(409,"group_unavailable")
        val ledger=GroupLedger(records,GroupTrustedPeer {false},localId)
        val current=ledger.state(groupId) ?: throw ApiFailure(409,"group_unavailable")
        if(ledger.status(groupId)!=GroupLocalStatus.ACTIVE || ledger.isForked(groupId) ||
            current.lifecycle!=GroupLifecycle.ACTIVE ||
            current.members.none {it.memberId==localId}) throw ApiFailure(409,"group_unavailable")
        return current to localId
    }
    private fun governanceState(groupId:String,unactivated:Boolean=true):Pair<GroupState,String> {
        val (current,localId)=baselineState(groupId)
        val active=baselineV1.active(groupId) ?: throw ApiFailure(409,"group_governance_unavailable")
        val certificate=baselineV1.certificate(groupId) ?: throw ApiFailure(409,"group_governance_unavailable")
        if(baselineV1.pending(groupId)!=null || admissionV2.pending(groupId)!=null ||
            admissionV2.ownKeys().any {it.first==groupId} ||
            active.stateRevision!=current.revision ||
            !MessageDigest.isEqual(active.stateDigest,GroupStatements.digest(current)) ||
            !MessageDigest.isEqual(active.memberSetDigest,GroupAuthorityBaselineV1.memberSetDigest(
                GroupAuthorityBaselineV1.memberSet(current))) ||
            !MessageDigest.isEqual(active.certificateDigest,certificate.digest) ||
            !GroupAuthorityBaselineV1.verifyCertificate(certificate,current) ||
            (unactivated && GroupLedger(records,GroupTrustedPeer {false},localId)
                .governanceBarrier(groupId)!=null))
            throw ApiFailure(409,"group_governance_unavailable")
        return current to localId
    }
    private fun allGovernancePeers(state:GroupState,localId:String)=state.members.all {
        it.memberId==localId || repository.governanceV1Peer(it.deviceId)
    }
    private fun allTextV2Peers(state:GroupState,localId:String)=state.members.all {
        it.memberId==localId || repository.governanceTextV2Peer(it.deviceId)
    }
    private fun allModerationPeers(state:GroupState,localId:String)=state.members.all {
        it.memberId==localId || repository.groupModerationPeer(it.deviceId)
    }
    /** Only the coordinator retires a fully acknowledged prior entry before the next mutation. */
    private fun retireCompletedGovernanceEntry(groupId:String,state:GroupState,localId:String) {
        if(localId!=state.coordinatorId) return
        val membership=governanceV1.outstanding(groupId)
        if(membership!=null) {
            if(membership.applied.map {it.memberId}.sorted()!=state.members.map {it.memberId}.sorted())
                throw ApiFailure(409,"group_governance_entry_pending")
            governanceV1.clearSent(groupId,"entry-${membership.entry.sequence}")
            governanceV1.clearSent(groupId,"entry-ack-${membership.entry.sequence}")
            governanceV1.clearOutstanding(groupId)
        }
        val policy=governanceV1.policyOutstanding(groupId)
        if(policy!=null) {
            if(policy.applied.map {it.memberId}.sorted()!=state.members.map {it.memberId}.sorted())
                throw ApiFailure(409,"group_governance_entry_pending")
            governanceV1.clearSent(groupId,"entry-${policy.entry.sequence}")
            governanceV1.clearSent(groupId,"entry-ack-${policy.entry.sequence}")
            governanceV1.clearPolicyOutstanding(groupId)
        }
    }
    private suspend fun probeMissingGovernancePeers(state:GroupState,localId:String) {
        for(member in state.members.filter {it.memberId!=localId &&
            (!repository.governanceV1Peer(it.deviceId) ||
                !repository.governanceTextV2Peer(it.deviceId) ||
                !repository.groupModerationPeer(it.deviceId))})
            sendGovernanceOnce(state.groupId,
                if(repository.governanceV1Peer(member.deviceId)) "text-v2-capability" else "capability",
                member.deviceId,
                GroupControl(kind=GroupControlKind.RESYNC_REQUEST,
                groupId=state.groupId,fromRevision=state.revision,
                fromDigest=GroupStatements.digest(state)))
    }
    private fun governanceCoordinator(state:GroupState)=state.members.single {
        it.memberId==state.coordinatorId
    }.deviceId
    private fun governanceOwner(state:GroupState)=state.members.single {
        it.memberId==state.ownerId
    }.deviceId
    private fun noOutgoingInvitation(groupId:String)=records.transaction {
        records.keys("app/group/outgoing/").none {key ->
            records.read(key)?.let {NetworkCodec.decode<Outgoing>(it,16_384)}?.let {
                it.offer.groupId==groupId && !it.used
            }==true
        } && records.keys("app/group/admission/").none {key ->
            records.read(key)?.let {NetworkCodec.decode<Outgoing>(it,16_384)}?.let {
                it.offer.groupId==groupId
            }==true
        } && admissionV2.pending(groupId)==null && governedAdmission.pending(groupId)==null
    }
    /** Internal protocol entry point; no group-management UI is enabled in A6.2. */
    suspend fun beginGovernanceActivation(groupId:String)=textMutex.withLock {
        val (current,localId)=governanceState(groupId)
        if(current.coordinatorId!=localId || governanceV1.pending(groupId)!=null ||
            governanceV1.commit(groupId)!=null || !noOutgoingInvitation(groupId))
            throw ApiFailure(409,"group_governance_unavailable")
        if(!allGovernancePeers(current,localId) || !allTextV2Peers(current,localId)) {
            probeMissingGovernancePeers(current,localId)
            throw ApiFailure(409,"group_governance_capability_pending")
        }
        verifyBaselinePeers(current,localId)
        val baseline=checkNotNull(baselineV1.active(groupId))
        val unsigned=GroupGovernanceV1.unsignedProposal(current,baseline.certificateDigest,GroupIds.create())
        val coordinatorSigned=unsigned.copy(coordinatorSignature=network.signGroupStatement(
            GroupGovernanceV1.proposalStatement(unsigned,false)))
        val proposal=if(current.ownerId==localId) coordinatorSigned.copy(
            ownerSignature=network.signGroupStatement(GroupGovernanceV1.proposalStatement(unsigned,true)))
            else coordinatorSigned
        if(!GroupGovernanceV1.verifyProposal(proposal,current,baseline.certificateDigest,
                requireOwner=current.ownerId==localId)) throw ApiFailure(409,"group_invalid")
        records.transaction {
            val (latest,id)=governanceState(groupId)
            require(id==localId && MessageDigest.isEqual(GroupStatements.digest(latest),
                proposal.stateDigest) && governanceV1.pending(groupId)==null &&
                noOutgoingInvitation(groupId))
            governanceV1.savePending(PendingGovernanceActivationV1(proposal))
        }
        flushGovernanceV1()
    }
    private suspend fun receiveGovernanceOwnerRequest(sender:String,control:GroupControl) {
        val proposal=control.governanceProposalV1 ?: throw ApiFailure(400,"group_invalid")
        val (current,localId)=governanceState(control.groupId)
        val baseline=checkNotNull(baselineV1.active(control.groupId))
        if(current.ownerId!=localId || current.coordinatorId==localId ||
            governanceCoordinator(current)!=sender ||
            !noOutgoingInvitation(control.groupId) ||
            proposal.ownerSignature.isNotEmpty() ||
            !GroupGovernanceV1.verifyProposal(proposal,current,baseline.certificateDigest,false))
            throw ApiFailure(409,"group_invalid")
        if(!allGovernancePeers(current,localId) || !allTextV2Peers(current,localId)) {
            probeMissingGovernancePeers(current,localId)
            throw ApiFailure(503,"group_governance_capability_pending")
        }
        val existing=governanceV1.pending(control.groupId)
        if(existing!=null && !MessageDigest.isEqual(
                GroupGovernanceV1.proposalDigest(existing.proposal.copy(ownerSignature=byteArrayOf())),
                GroupGovernanceV1.proposalDigest(proposal)))
            throw ApiFailure(409,"group_governance_pending")
        val signed=existing?.proposal ?: proposal.copy(ownerSignature=network.signGroupStatement(
            GroupGovernanceV1.proposalStatement(proposal,true)))
        if(!GroupGovernanceV1.verifyProposal(signed,current,baseline.certificateDigest))
            throw ApiFailure(409,"group_invalid")
        records.transaction {governanceV1.savePending(PendingGovernanceActivationV1(signed))}
        flushGovernanceV1()
    }
    private suspend fun receiveGovernanceOwnerResponse(sender:String,control:GroupControl) {
        val signed=control.governanceProposalV1 ?: throw ApiFailure(400,"group_invalid")
        val (current,localId)=governanceState(control.groupId)
        val baseline=checkNotNull(baselineV1.active(control.groupId))
        val pending=governanceV1.pending(control.groupId) ?: throw ApiFailure(409,"group_invalid")
        if(localId!=current.coordinatorId || governanceOwner(current)!=sender ||
            pending.proposal.activationId!=signed.activationId ||
            !MessageDigest.isEqual(GroupGovernanceV1.proposalDigest(
                pending.proposal.copy(ownerSignature=byteArrayOf())),
                GroupGovernanceV1.proposalDigest(signed.copy(ownerSignature=byteArrayOf()))) ||
            !GroupGovernanceV1.verifyProposal(signed,current,baseline.certificateDigest))
            throw ApiFailure(409,"group_invalid")
        if(pending.proposal.ownerSignature.isNotEmpty() &&
            !NetworkCodec.encode(pending.proposal).contentEquals(NetworkCodec.encode(signed)))
            throw ApiFailure(409,"group_invalid")
        if(pending.proposal.ownerSignature.isEmpty())
            governanceV1.savePending(pending.copy(proposal=signed))
        flushGovernanceV1()
    }
    private suspend fun receiveGovernanceProposal(sender:String,control:GroupControl) {
        val proposal=control.governanceProposalV1 ?: throw ApiFailure(400,"group_invalid")
        val (current,localId)=governanceState(control.groupId)
        val baseline=checkNotNull(baselineV1.active(control.groupId))
        if(governanceCoordinator(current)!=sender ||
            !noOutgoingInvitation(control.groupId) ||
            !GroupGovernanceV1.verifyProposal(proposal,current,baseline.certificateDigest))
            throw ApiFailure(409,"group_invalid")
        if(!allGovernancePeers(current,localId) || !allTextV2Peers(current,localId)) {
            probeMissingGovernancePeers(current,localId)
            throw ApiFailure(503,"group_governance_capability_pending")
        }
        val old=governanceV1.pending(control.groupId)
        if(old!=null && !MessageDigest.isEqual(GroupGovernanceV1.proposalDigest(old.proposal),
                GroupGovernanceV1.proposalDigest(proposal)))
            throw ApiFailure(409,"group_governance_pending")
        val own=governanceV1.own(control.groupId)
        if(own!=null && !MessageDigest.isEqual(own.proposalDigest,
                GroupGovernanceV1.proposalDigest(proposal)))
            throw ApiFailure(409,"group_governance_pending")
        if(own==null) {
            verifyBaselinePeers(current,localId)
            val unsigned=GroupGovernanceV1.unsignedAck(proposal,localId)
            val signed=unsigned.copy(signature=network.signGroupStatement(
                GroupGovernanceV1.ackStatement(unsigned)))
            if(!GroupGovernanceV1.verifyAck(signed,proposal,current)) throw ApiFailure(409,"group_invalid")
            records.transaction {
                val (latest,id)=governanceState(control.groupId)
                require(id==localId && MessageDigest.isEqual(GroupStatements.digest(latest),
                    proposal.stateDigest))
                governanceV1.saveOwn(signed)
                governanceV1.savePending(old ?: PendingGovernanceActivationV1(proposal))
            }
        }
        flushGovernanceV1()
    }
    private suspend fun receiveGovernanceAck(sender:String,control:GroupControl) {
        val ack=control.governanceAckV1 ?: throw ApiFailure(400,"group_invalid")
        val (current,localId)=governanceState(control.groupId)
        val pending=governanceV1.pending(control.groupId) ?: throw ApiFailure(409,"group_invalid")
        if(localId!=current.coordinatorId ||
            current.members.singleOrNull {it.memberId==ack.approverId}?.deviceId!=sender ||
            !GroupGovernanceV1.verifyAck(ack,pending.proposal,current))
            throw ApiFailure(409,"group_invalid")
        val old=pending.acks.singleOrNull {it.approverId==ack.approverId}
        if(old!=null && !NetworkCodec.encode(old).contentEquals(NetworkCodec.encode(ack)))
            throw ApiFailure(409,"group_invalid")
        if(old==null) governanceV1.savePending(pending.copy(acks=pending.acks+ack))
        flushGovernanceV1()
    }
    private suspend fun installGovernanceCommit(commit:GroupGovernanceActivationCommitV1,
        current:GroupState,localId:String) {
        val own=governanceV1.own(current.groupId) ?: throw ApiFailure(503,"group_evidence_pending")
        if(commit.acks.singleOrNull {it.approverId==localId}?.let {
                NetworkCodec.encode(it).contentEquals(NetworkCodec.encode(own))}!=true)
            throw ApiFailure(409,"group_invalid")
        val unsigned=GroupGovernanceV1.unsignedInstalled(commit,localId)
        val installed=unsigned.copy(signature=network.signGroupStatement(
            GroupGovernanceV1.installedStatement(unsigned)))
        val ledger=GroupLedger(records,GroupTrustedPeer {false},localId)
        val result=ledger.installGovernanceBarrierForFutureActivation(current.groupId,commit.digest) {
            chats.cancelStaleHead(current.groupId,
                checkNotNull(ledger.governanceHead(current.groupId)),
                GroupGovernancePolicyRulesV1.initial())
            governanceV1.saveCommit(commit)
            governanceV1.saveInstalled(installed)
            val pending=governanceV1.pending(current.groupId)
            if(pending!=null) governanceV1.savePending(pending.copy(installed=
                pending.installed.filterNot {it.memberId==localId}+installed))
        }
        if(result!=GroupApply.ACCEPTED) throw ApiFailure(409,"group_invalid")
    }
    private suspend fun receiveGovernanceCommit(sender:String,control:GroupControl) {
        val commit=control.governanceCommitV1 ?: throw ApiFailure(400,"group_invalid")
        val existing=governanceV1.commit(control.groupId)
        if(existing!=null) {
            if(!NetworkCodec.encode(existing).contentEquals(NetworkCodec.encode(commit)))
                throw ApiFailure(409,"group_invalid")
            flushGovernanceV1();return
        }
        val (current,localId)=governanceState(control.groupId)
        val baseline=checkNotNull(baselineV1.active(control.groupId))
        if(governanceCoordinator(current)!=sender ||
            !GroupGovernanceV1.verifyCommit(commit,current,baseline.certificateDigest))
            throw ApiFailure(409,"group_invalid")
        if(!allGovernancePeers(current,localId) || !allTextV2Peers(current,localId)) {
            probeMissingGovernancePeers(current,localId)
            throw ApiFailure(503,"group_governance_capability_pending")
        }
        val pending=governanceV1.pending(control.groupId)
        if(pending!=null && !MessageDigest.isEqual(
                GroupGovernanceV1.proposalDigest(pending.proposal),
                GroupGovernanceV1.proposalDigest(commit.proposal)))
            throw ApiFailure(409,"group_governance_pending")
        installGovernanceCommit(commit,current,localId)
        flushGovernanceV1()
    }
    private suspend fun receiveGovernanceInstalled(sender:String,control:GroupControl) {
        val installed=control.governanceInstalledV1 ?: throw ApiFailure(400,"group_invalid")
        val commit=governanceV1.commit(control.groupId) ?: throw ApiFailure(503,"group_evidence_pending")
        val (current,localId)=baselineState(control.groupId)
        if(localId!=current.coordinatorId ||
            current.members.singleOrNull {it.memberId==installed.memberId}?.deviceId!=sender ||
            !GroupGovernanceV1.verifyInstalled(installed,commit,current))
            throw ApiFailure(409,"group_invalid")
        val pending=governanceV1.pending(control.groupId) ?: throw ApiFailure(409,"group_invalid")
        val old=pending.installed.singleOrNull {it.memberId==installed.memberId}
        if(old!=null && !NetworkCodec.encode(old).contentEquals(NetworkCodec.encode(installed)))
            throw ApiFailure(409,"group_invalid")
        if(old==null) governanceV1.savePending(pending.copy(installed=pending.installed+installed))
        flushGovernanceV1()
    }
    private suspend fun receiveGovernanceReady(sender:String,control:GroupControl) {
        val ready=control.governanceReadyV1 ?: throw ApiFailure(400,"group_invalid")
        val existing=governanceV1.ready(control.groupId)
        if(existing!=null) {
            if(!NetworkCodec.encode(existing).contentEquals(NetworkCodec.encode(ready)))
                throw ApiFailure(409,"group_invalid")
            return
        }
        val (current,localId)=baselineState(control.groupId)
        val baseline=baselineV1.active(control.groupId) ?: throw ApiFailure(409,"group_invalid")
        val commit=governanceV1.commit(control.groupId) ?: throw ApiFailure(503,"group_evidence_pending")
        val barrier=GroupLedger(records,GroupTrustedPeer {false},localId).governanceBarrier(control.groupId)
            ?: throw ApiFailure(503,"group_evidence_pending")
        val own=governanceV1.installed(control.groupId) ?: throw ApiFailure(503,"group_evidence_pending")
        if(governanceCoordinator(current)!=sender ||
            !MessageDigest.isEqual(barrier.activationDigest,commit.digest) ||
            !MessageDigest.isEqual(ready.commit.digest,commit.digest) ||
            ready.installed.singleOrNull {it.memberId==localId}?.let {
                NetworkCodec.encode(it).contentEquals(NetworkCodec.encode(own))}!=true ||
            !GroupGovernanceV1.verifyReady(ready,current,baseline.certificateDigest))
            throw ApiFailure(409,"group_invalid")
        governanceV1.saveReady(ready)
    }
    private suspend fun ensureCoordinatorGovernanceAck(proposal:GroupGovernanceActivationProposalV1,
        current:GroupState,localId:String) {
        val old=governanceV1.own(current.groupId)
        if(old!=null) {
            if(!MessageDigest.isEqual(old.proposalDigest,GroupGovernanceV1.proposalDigest(proposal)))
                throw ApiFailure(409,"group_governance_pending")
            return
        }
        val unsigned=GroupGovernanceV1.unsignedAck(proposal,localId)
        val signed=unsigned.copy(signature=network.signGroupStatement(
            GroupGovernanceV1.ackStatement(unsigned)))
        if(!GroupGovernanceV1.verifyAck(signed,proposal,current)) throw ApiFailure(409,"group_invalid")
        records.transaction {
            val (latest,id)=governanceState(current.groupId)
            require(id==localId && MessageDigest.isEqual(GroupStatements.digest(latest),
                proposal.stateDigest))
            val pending=governanceV1.pending(current.groupId) ?: error("governance_pending_missing")
            require(MessageDigest.isEqual(GroupGovernanceV1.proposalDigest(pending.proposal),
                GroupGovernanceV1.proposalDigest(proposal)))
            governanceV1.saveOwn(signed)
            governanceV1.savePending(pending.copy(acks=pending.acks+signed))
        }
    }
    private suspend fun sendGovernanceOnce(groupId:String,phase:String,recipient:String,
        control:GroupControl) {
        if(governanceV1.sent(groupId,phase,recipient)) return
        try {send(recipient,control) {id ->governanceV1.markSent(groupId,phase,recipient,id)}}
        catch(e:CancellationException) {throw e}
        catch(_:ApiFailure) { /* Exact protected control remains for retry. */ }
    }
    private suspend fun flushGovernanceV1() {
        for(groupId in governanceV1.pendingGroups()) {
            val pending=governanceV1.pending(groupId) ?: continue
            val (current,localId)=runCatching {baselineState(groupId)}.getOrNull() ?: continue
            val proposal=pending.proposal
            if(current.revision!=proposal.stateRevision ||
                !MessageDigest.isEqual(GroupStatements.digest(current),proposal.stateDigest)) continue
            val coordinator=governanceCoordinator(current)
            if(localId==current.coordinatorId) {
                if(proposal.ownerSignature.isEmpty()) {
                    sendGovernanceOnce(groupId,"owner-request",governanceOwner(current),
                        GroupControl(kind=GroupControlKind.GOVERNANCE_OWNER_SIGN_REQUEST,
                            groupId=groupId,governanceProposalV1=proposal))
                    continue
                }
                ensureCoordinatorGovernanceAck(proposal,current,localId)
                val latest=governanceV1.pending(groupId) ?: continue
                val commit=governanceV1.commit(groupId)
                if(commit==null) {
                    if(latest.acks.size==current.members.size) {
                        val candidate=GroupGovernanceV1.commit(proposal,latest.acks)
                        val baseline=baselineV1.active(groupId) ?: continue
                        if(!GroupGovernanceV1.verifyCommit(candidate,current,baseline.certificateDigest))
                            throw ApiFailure(409,"group_invalid")
                        installGovernanceCommit(candidate,current,localId)
                    } else for(member in current.members.filter {it.memberId!=localId &&
                        latest.acks.none {ack ->ack.approverId==it.memberId}})
                        sendGovernanceOnce(groupId,"proposal",member.deviceId,
                            GroupControl(kind=GroupControlKind.GOVERNANCE_ACTIVATION_PROPOSAL,
                                groupId=groupId,governanceProposalV1=proposal))
                }
                val installedCommit=governanceV1.commit(groupId)
                if(installedCommit!=null) {
                    for(member in current.members.filter {it.memberId!=localId})
                        sendGovernanceOnce(groupId,"commit",member.deviceId,
                            GroupControl(kind=GroupControlKind.GOVERNANCE_ACTIVATION_COMMIT,
                                groupId=groupId,governanceCommitV1=installedCommit))
                    val installed=governanceV1.pending(groupId)?.installed.orEmpty()
                    if(governanceV1.ready(groupId)==null && installed.size==current.members.size) {
                        val baseline=baselineV1.active(groupId) ?: continue
                        val ready=GroupGovernanceV1.ready(installedCommit,installed)
                        if(!GroupGovernanceV1.verifyReady(ready,current,baseline.certificateDigest))
                            throw ApiFailure(409,"group_invalid")
                        governanceV1.saveReady(ready)
                    }
                    val ready=governanceV1.ready(groupId)
                    if(ready!=null) for(member in current.members.filter {it.memberId!=localId})
                        sendGovernanceOnce(groupId,"ready",member.deviceId,
                            GroupControl(kind=GroupControlKind.GOVERNANCE_ACTIVATION_READY,
                                groupId=groupId,governanceReadyV1=ready))
                }
            } else {
                if(localId==current.ownerId && proposal.ownerSignature.isNotEmpty() &&
                    governanceV1.own(groupId)==null)
                    sendGovernanceOnce(groupId,"owner-response",coordinator,
                        GroupControl(kind=GroupControlKind.GOVERNANCE_OWNER_SIGN_RESPONSE,
                            groupId=groupId,governanceProposalV1=proposal))
                val own=governanceV1.own(groupId)
                if(own!=null) sendGovernanceOnce(groupId,"ack",coordinator,
                    GroupControl(kind=GroupControlKind.GOVERNANCE_ACTIVATION_ACK,
                        groupId=groupId,governanceAckV1=own))
                val installed=governanceV1.installed(groupId)
                if(installed!=null) sendGovernanceOnce(groupId,"installed",coordinator,
                    GroupControl(kind=GroupControlKind.GOVERNANCE_ACTIVATION_INSTALLED_ACK,
                        groupId=groupId,governanceInstalledV1=installed))
            }
        }
        flushGovernanceEntries()
    }
    /** Internal A6.2 proof action. Management UI remains disabled. */
    suspend fun changeGroupTimerGoverned(groupId:String,seconds:Int)=textMutex.withLock {
        DisappearingTimer.from(seconds)
        val (current,localId)=baselineState(groupId)
        val ledger=GroupLedger(records,GroupTrustedPeer {false},localId)
        val barrier=ledger.governanceBarrier(groupId) ?: throw ApiFailure(409,"group_governance_unavailable")
        if(ledger.governanceJournalStatus(groupId)!=GovernanceJournalStatus.COMPLETE)
            throw ApiFailure(409,"group_governance_legacy_incomplete")
        val ready=governanceV1.ready(groupId)
        val join=governedAdmission.join(groupId)
        if(current.coordinatorId!=localId ||
            ((ready==null || !MessageDigest.isEqual(ready.commit.digest,barrier.activationDigest) ||
                governanceV1.commit(groupId)==null) &&
                (join==null || !MessageDigest.isEqual(join.checkpoint.activationDigest,
                    barrier.activationDigest))))
            throw ApiFailure(409,"group_governance_unavailable")
        val head=ledger.governanceHead(groupId) ?: throw ApiFailure(409,"group_governance_unavailable")
        if(head.sequence>=GroupGovernanceJournalV1.MAX_ENTRIES)
            throw ApiFailure(409,"group_capacity")
        retireCompletedGovernanceEntry(groupId,current,localId)
        val peers=trusted(current.members,groupId)
        val change=GroupChange(GroupIds.create(),GroupAction.TIMER,localId,newTimer=seconds)
        val next=try {GroupRules.derive(current,change,emptySet(),peers)}
            catch(_:IllegalArgumentException) {throw ApiFailure(409,"group_invalid")}
        val event=GroupTransition(change,next,
            network.signGroupStatement(GroupStatements.actor(current,change,next)),
            network.signGroupStatement(GroupStatements.coordinator(current,change,next)))
        val unsigned=GroupGovernanceEntryV1(groupId=groupId,
            activationDigest=head.activationDigest,sequence=head.sequence+1,
            previousHeadDigest=head.headDigest,eventId=change.eventId,preRevision=current.revision,
            preDigest=GroupStatements.digest(current),actorId=localId,action=change.action,
            transitionDigest=DeviceAuth.digest(NetworkCodec.encode(event)),
            postRevision=next.revision,postDigest=GroupStatements.digest(next),
            transition=event,actorSignature=byteArrayOf(),coordinatorSignature=byteArrayOf())
        val entry=unsigned.copy(actorSignature=network.signGroupStatement(
            GroupGovernanceV1.actorStatement(unsigned)),coordinatorSignature=network.signGroupStatement(
            GroupGovernanceV1.coordinatorStatement(unsigned)))
        if(!GroupGovernanceV1.verifyEntry(entry,current,head)) throw ApiFailure(409,"group_invalid")
        val unsignedAck=GroupGovernanceV1.unsignedApplied(entry,localId)
        val ack=unsignedAck.copy(signature=network.signGroupStatement(
            GroupGovernanceV1.appliedStatement(unsignedAck)))
        if(!GroupGovernanceV1.verifyApplied(ack,entry)) throw ApiFailure(409,"group_invalid")
        val result=GroupLedger(records,peers,localId).applyGovernedTransition(groupId,head,event,
            GroupGovernanceV1.entryDigest(entry)) {
            governanceJournal.append(entry,barrier,
                checkNotNull(GroupLedger(records,peers,localId).governanceHead(groupId)))
            val nextLedger=GroupLedger(records,peers,localId)
            chats.cancelStaleHead(groupId,nextLedger.governanceHead(groupId),
                nextLedger.governancePolicy(groupId))
            governanceV1.saveOutstanding(PendingGovernanceEntryV1(entry,listOf(ack)))
        }
        if(result!=GroupApply.ACCEPTED) throw ApiFailure(409,"group_invalid")
        flushGovernanceEntries()
    }
    suspend fun setPostingModeGoverned(groupId:String,mode:GroupPostingModeV1)=textMutex.withLock {
        proposePolicyChange(groupId,GroupPolicyActionV1.SET_POSTING_MODE,null,mode)
    }
    suspend fun restrictMemberGoverned(groupId:String,memberId:String)=textMutex.withLock {
        proposePolicyChange(groupId,GroupPolicyActionV1.RESTRICT_MEMBER,memberId,null)
    }
    suspend fun unrestrictMemberGoverned(groupId:String,memberId:String)=textMutex.withLock {
        proposePolicyChange(groupId,GroupPolicyActionV1.UNRESTRICT_MEMBER,memberId,null)
    }
    suspend fun removeGroupMessageGoverned(groupId:String,logicalId:String)=textMutex.withLock {
        proposePolicyChange(groupId,GroupPolicyActionV1.REMOVE_GROUP_MESSAGE,null,null,logicalId)
    }
    private suspend fun proposePolicyChange(groupId:String,action:GroupPolicyActionV1,
        targetId:String?,mode:GroupPostingModeV1?,logicalId:String?=null) {
        val (current,localId)=baselineState(groupId)
        val head=activeGovernanceHead(groupId) ?: throw ApiFailure(409,"group_governance_unavailable")
        reconcileGovernedChange(groupId,current,head)
        val ledger=GroupLedger(records,GroupTrustedPeer {false},localId)
        val policy=ledger.governancePolicy(groupId) ?: throw ApiFailure(409,"group_governance_unavailable")
        if(!allTextV2Peers(current,localId))
            throw ApiFailure(409,"group_governance_capability_pending")
        if(action==GroupPolicyActionV1.REMOVE_GROUP_MESSAGE &&
            !allModerationPeers(current,localId)) {
            probeMissingGovernancePeers(current,localId)
            throw ApiFailure(409,"group_moderation_capability_pending")
        }
        if(localId==current.coordinatorId)
            retireCompletedGovernanceEntry(groupId,current,localId)
        if(head.sequence>=GroupGovernanceJournalV1.MAX_ENTRIES)
            throw ApiFailure(409,"group_management_history_full")
        if(governanceJournal.marker(groupId)!=null ||
            governedChanges.own(groupId)!=null || governedChanges.prepared(groupId)!=null ||
            governedChanges.outgoingTransfer(groupId)!=null)
            throw ApiFailure(409,"group_governance_entry_pending")
        if(action==GroupPolicyActionV1.REMOVE_GROUP_MESSAGE &&
            chats.isModerated(groupId,checkNotNull(logicalId)))
            throw ApiFailure(409,"group_message_already_removed")
        val next=try {GroupGovernancePolicyRulesV1.apply(policy,current,localId,action,targetId,mode,logicalId)}
            catch(_:IllegalArgumentException) {throw ApiFailure(409,"group_policy_denied")}
        val unsigned=GroupGovernancePolicyEntryV1(groupId=groupId,
            activationDigest=head.activationDigest,sequence=head.sequence+1,
            previousHeadDigest=head.headDigest,eventId=GroupIds.create(),
            stateRevision=current.revision,stateDigest=GroupStatements.digest(current),
            actorId=localId,action=action,targetMemberId=targetId,postingMode=mode,
            targetLogicalId=logicalId,
            prePolicyDigest=GroupGovernancePolicyRulesV1.digest(head.activationDigest,policy),
            postPolicyDigest=GroupGovernancePolicyRulesV1.digest(head.activationDigest,next),
            actorSignature=byteArrayOf(),coordinatorSignature=byteArrayOf())
        val actorSigned=unsigned.copy(actorSignature=network.signGroupStatement(
            GroupGovernancePolicyRulesV1.actorStatement(unsigned)))
        if(localId!=current.coordinatorId) {
            send(governanceCoordinator(current),GroupControl(
                kind=GroupControlKind.GOVERNANCE_POLICY_PROPOSAL_V1,
                groupId=groupId,governancePolicyEntryV1=actorSigned))
            return
        }
        val entry=actorSigned.copy(coordinatorSignature=network.signGroupStatement(
            GroupGovernancePolicyRulesV1.coordinatorStatement(actorSigned)))
        applyPolicyEntry(entry,current,localId,ledger)
        flushGovernanceEntries()
    }
    private suspend fun receivePolicyProposal(sender:String,control:GroupControl) {
        val proposal=control.governancePolicyEntryV1 ?: throw ApiFailure(400,"group_invalid")
        val localId=localMember(control.groupId) ?: throw ApiFailure(409,"group_invalid")
        val ledger=GroupLedger(records,GroupTrustedPeer {false},localId)
        val current=ledger.state(control.groupId) ?: throw ApiFailure(409,"group_invalid")
        val head=activeGovernanceHead(control.groupId) ?: throw ApiFailure(409,"group_invalid")
        val policy=ledger.governancePolicy(control.groupId) ?: throw ApiFailure(409,"group_invalid")
        if(!allTextV2Peers(current,localId))
            throw ApiFailure(409,"group_governance_capability_pending")
        if(proposal.action==GroupPolicyActionV1.REMOVE_GROUP_MESSAGE &&
            !allModerationPeers(current,localId))
            throw ApiFailure(409,"group_moderation_capability_pending")
        retireCompletedGovernanceEntry(control.groupId,current,localId)
        if(proposal.action==GroupPolicyActionV1.REMOVE_GROUP_MESSAGE) {
            val target=proposal.targetLogicalId ?: throw ApiFailure(409,"group_invalid")
            if(chats.isModerated(control.groupId,target))
                throw ApiFailure(409,"group_message_already_removed")
        }
        if(localId!=current.coordinatorId || current.members.singleOrNull {
                it.memberId==proposal.actorId}?.deviceId!=sender ||
            governedChanges.prepared(control.groupId)!=null ||
            governedChanges.own(control.groupId)!=null ||
            proposal.coordinatorSignature.isNotEmpty() ||
            proposal.sequence!=head.sequence+1 ||
            !MessageDigest.isEqual(proposal.previousHeadDigest,head.headDigest) ||
            !MessageDigest.isEqual(proposal.prePolicyDigest,
                GroupGovernancePolicyRulesV1.digest(head.activationDigest,policy)))
            throw ApiFailure(409,"group_invalid")
        val actor=current.members.single {it.memberId==proposal.actorId}
        if(!GroupStatements.verify(actor.authPublicKey,
                GroupGovernancePolicyRulesV1.actorStatement(proposal),proposal.actorSignature))
            throw ApiFailure(409,"group_invalid")
        val entry=proposal.copy(coordinatorSignature=network.signGroupStatement(
            GroupGovernancePolicyRulesV1.coordinatorStatement(proposal)))
        applyPolicyEntry(entry,current,localId,ledger)
        flushGovernanceEntries()
    }
    private fun applyPolicyEntry(entry:GroupGovernancePolicyEntryV1,current:GroupState,
        localId:String,ledger:GroupLedger) {
        val head=ledger.governanceHead(entry.groupId) ?: throw ApiFailure(409,"group_invalid")
        val policy=ledger.governancePolicy(entry.groupId) ?: throw ApiFailure(409,"group_invalid")
        if(!GroupGovernancePolicyRulesV1.verifyEntry(entry,current,head,policy))
            throw ApiFailure(409,"group_invalid")
        val unsignedAck=GroupGovernanceV1.unsignedPolicyApplied(entry,localId)
        val ack=unsignedAck.copy(signature=network.signGroupStatement(
            GroupGovernanceV1.appliedStatement(unsignedAck)))
        if(!GroupGovernanceV1.verifyPolicyApplied(ack,entry,current))
            throw ApiFailure(409,"group_invalid")
        val result=ledger.applyGovernedPolicy(entry) {
            chats.cancelStaleHead(entry.groupId,ledger.governanceHead(entry.groupId),
                ledger.governancePolicy(entry.groupId))
            governanceV1.savePolicyOutstanding(PendingGovernancePolicyEntryV1(entry,listOf(ack)))
        }
        if(result!=GroupApply.ACCEPTED) throw ApiFailure(409,"group_invalid")
    }
    private suspend fun receivePolicyEntry(sender:String,control:GroupControl) {
        val entry=control.governancePolicyEntryV1 ?: throw ApiFailure(400,"group_invalid")
        val localId=localMember(control.groupId) ?: throw ApiFailure(409,"group_invalid")
        val ledger=GroupLedger(records,GroupTrustedPeer {false},localId)
        val current=ledger.state(control.groupId) ?: throw ApiFailure(409,"group_invalid")
        val head=activeGovernanceHead(control.groupId) ?: throw ApiFailure(409,"group_invalid")
        if(governanceCoordinator(current)!=sender) throw ApiFailure(409,"group_invalid")
        if(entry.sequence>head.sequence+1) {
            requestGovernanceResync(control.groupId);return
        }
        if(entry.sequence<=head.sequence) {
            val result=ledger.markGovernancePolicyForkIfValid(entry)
            if(result==GroupApply.DUPLICATE) return
            if(result==GroupApply.FORKED) throw ApiFailure(409,"group_forked")
            throw ApiFailure(409,"group_invalid")
        }
        if(!allTextV2Peers(current,localId))
            throw ApiFailure(503,"group_governance_capability_pending")
        applyPolicyEntry(entry,current,localId,ledger)
        flushGovernanceEntries()
    }
    private suspend fun receivePolicyAck(sender:String,control:GroupControl) {
        val ack=control.governanceEntryAckV1 ?: throw ApiFailure(400,"group_invalid")
        val pending=governanceV1.policyOutstanding(control.groupId) ?: throw ApiFailure(409,"group_invalid")
        val current=state(control.groupId) ?: throw ApiFailure(409,"group_invalid")
        val localId=localMember(control.groupId) ?: throw ApiFailure(409,"group_invalid")
        if(localId!=current.coordinatorId || current.members.singleOrNull {
                it.memberId==ack.memberId}?.deviceId!=sender ||
            !GroupGovernanceV1.verifyPolicyApplied(ack,pending.entry,current))
            throw ApiFailure(409,"group_invalid")
        val old=pending.applied.singleOrNull {it.memberId==ack.memberId}
        if(old!=null && !NetworkCodec.encode(old).contentEquals(NetworkCodec.encode(ack)))
            throw ApiFailure(409,"group_invalid")
        if(old==null) governanceV1.savePolicyOutstanding(pending.copy(applied=pending.applied+ack))
    }
    private suspend fun receiveGovernanceEntry(sender:String,control:GroupControl) {
        val entry=control.governanceEntryV1 ?: throw ApiFailure(400,"group_invalid")
        val localId=localMember(control.groupId) ?: throw ApiFailure(409,"group_unavailable")
        val ledger=GroupLedger(records,GroupTrustedPeer {false},localId)
        val current=ledger.state(control.groupId) ?: throw ApiFailure(409,"group_unavailable")
        val ready=governanceV1.ready(control.groupId)
        val join=governedAdmission.join(control.groupId)
        val barrier=ledger.governanceBarrier(control.groupId) ?: throw ApiFailure(503,"group_evidence_pending")
        if(ledger.governanceJournalStatus(control.groupId)!=GovernanceJournalStatus.COMPLETE)
            throw ApiFailure(409,"group_governance_legacy_incomplete")
        if((ready==null || !MessageDigest.isEqual(ready.commit.digest,barrier.activationDigest)) &&
            (join==null || !MessageDigest.isEqual(join.checkpoint.activationDigest,
                barrier.activationDigest)))
            throw ApiFailure(409,"group_invalid")
        val old=governanceV1.outstanding(control.groupId)
        if(old!=null && MessageDigest.isEqual(GroupGovernanceV1.entryDigest(old.entry),
                GroupGovernanceV1.entryDigest(entry)) && old.applied.any {it.memberId==localId}) {
            flushGovernanceEntries();return
        }
        val head=ledger.governanceHead(control.groupId) ?: throw ApiFailure(409,"group_invalid")
        if(entry.sequence<=head.sequence) {
            val result=ledger.markGovernanceForkIfValid(entry,
                historicalPeer={member,revision ->
                    historicalAuthority.matchesAt(control.groupId,member,revision)
                },admissionEvidence=admissionV2.evidence(control.groupId,entry.eventId))
            if(result==GroupApply.DUPLICATE) {flushGovernanceEntries();return}
            if(result==GroupApply.FORKED) throw ApiFailure(409,"group_forked")
            throw ApiFailure(409,"group_invalid")
        }
        if(current.members.singleOrNull {it.memberId==current.coordinatorId}?.deviceId!=sender)
            throw ApiFailure(409,"group_invalid")
        if(entry.sequence>head.sequence+1) {
            requestGovernanceResync(control.groupId)
            return
        }
        if(!GroupGovernanceV1.verifyEntry(entry,current,head))
            throw ApiFailure(409,"group_invalid")
        val candidate=entry.transition.change.added
        val certificate=if(entry.action==GroupAction.ADD) {
            val cert=admissionV2.evidence(control.groupId,entry.eventId)
            if(cert==null) {
                send(sender,GroupControl(kind=GroupControlKind.ADMISSION_V2_EVIDENCE_REQUEST,
                    groupId=control.groupId,evidenceEventId=entry.eventId))
                throw ApiFailure(503,"group_admission_evidence_pending")
            }
            if(candidate==null || !AdmissionV2.verifyCertificate(cert,current) ||
                cert.proposal.eventId!=entry.eventId ||
                cert.proposal.inviteId!=entry.transition.change.invite?.inviteId ||
                !MessageDigest.isEqual(cert.proposal.candidateDigest,
                    GroupStatements.digestMember(candidate)) ||
                !admissionV2.matchesOwnApproval(current,cert,localId))
                throw ApiFailure(409,"group_invalid")
            cert
        } else null
        val peers=trusted(current.members+listOfNotNull(candidate),control.groupId,historicalOnly=true)
        val remains=entry.transition.next.members.any {it.memberId==localId}
        // Departures and dissolution close the current group-scoped transport
        // authority. Enqueue their exact terminal entry under the pre-state binding.
        if(localId==current.coordinatorId && entry.action in setOf(
                GroupAction.REMOVE,GroupAction.LEAVE,GroupAction.DISSOLVE)) {
            val recipients=if(entry.action==GroupAction.DISSOLVE)
                current.members.filter {it.memberId!=localId}
            else listOf(current.members.singleOrNull {
                it.memberId==entry.transition.change.targetId
            } ?: throw ApiFailure(409,"group_invalid")).filter {it.memberId!=localId}
            for(departing in recipients) {
                val phase="entry-${entry.sequence}"
                if(!governanceV1.sent(control.groupId,phase,departing.deviceId)) {
                    try {
                        val id=send(departing.deviceId,GroupControl(
                            kind=GroupControlKind.GOVERNANCE_ENTRY,groupId=control.groupId,
                            governanceEntryV1=entry)) {outboxId ->
                            governanceV1.markSent(control.groupId,phase,departing.deviceId,outboxId)
                        }
                        // Group-scoped retries cease when membership changes. Attempt this
                        // terminal ciphertext now, under the still-valid pre-state binding.
                        if(!repository.isActiveContact(departing.deviceId))
                            try {outbox.process(id)} catch(e:CancellationException) {throw e}
                            catch(_:ApiFailure) { /* Best effort; no terminal ACK is required. */ }
                            catch(_:CryptoFailure) { /* A broken pairwise session is not bypassed. */ }
                    } catch(e:CancellationException) {throw e}
                    catch(_:ApiFailure) { /* Best effort terminal evidence. */ }
                }
            }
        }
        val ack=if(remains) GroupGovernanceV1.unsignedApplied(entry,localId).let {unsigned ->
            unsigned.copy(signature=network.signGroupStatement(
                GroupGovernanceV1.appliedStatement(unsigned)))
        } else null
        val result=GroupLedger(records,peers,localId).applyGovernedTransition(control.groupId,
            head,entry.transition,GroupGovernanceV1.entryDigest(entry),
            historicalPeer={member,revision ->
                historicalAuthority.matchesAt(control.groupId,member,revision)
            }) {
            governanceJournal.append(entry,barrier,
                checkNotNull(GroupLedger(records,peers,localId).governanceHead(control.groupId)))
            val nextLedger=GroupLedger(records,peers,localId)
            chats.cancelStaleHead(control.groupId,nextLedger.governanceHead(control.groupId),
                nextLedger.governancePolicy(control.groupId))
            if(candidate!=null && certificate!=null) {
                currentAuthority.anchor(entry.transition.next,candidate,
                    DeviceAuth.digest(NetworkCodec.encode(entry.transition)))
                historicalAuthority.record(current,entry.transition,certificate)
                admissionV2.saveEvidence(control.groupId,entry.eventId,certificate)
                admissionV2.removeOwn(control.groupId,certificate.proposal.inviteId)
            }
            currentAuthority.removeDeparted(entry.transition.next)
            chats.cancelStale(control.groupId,entry.transition.next.epoch)
            if(entry.action in setOf(GroupAction.REMOVE,GroupAction.LEAVE)) {
                val departing=current.members.single {it.memberId==entry.transition.change.targetId}
                chats.markRemoved(control.groupId,departing.deviceId)
            }
            if(ack!=null) governanceV1.saveOutstanding(PendingGovernanceEntryV1(entry,listOf(ack)))
        }
        if(result!=GroupApply.ACCEPTED && result!=GroupApply.REMOVED)
            throw ApiFailure(409,"group_invalid")
        flushGovernanceEntries()
    }
    /** Explicit recovery for a missing final entry, including after delivery expiry. */
    suspend fun requestGovernanceResync(groupId:String) {
        val localId=localMember(groupId) ?: throw ApiFailure(409,"group_unavailable")
        val ledger=GroupLedger(records,GroupTrustedPeer {false},localId)
        val current=ledger.state(groupId) ?: throw ApiFailure(409,"group_unavailable")
        val head=ledger.governanceHead(groupId) ?: throw ApiFailure(409,"group_unavailable")
        if(ledger.isForked(groupId) || ledger.governanceJournalStatus(groupId)!=GovernanceJournalStatus.COMPLETE ||
            current.members.none {it.memberId==localId}) throw ApiFailure(409,"group_unavailable")
        val coordinator=governanceCoordinator(current)
        if(coordinator==network.ownDevice()) return
        val marker=GovernanceResyncMarkerV1(groupId=groupId,
            activationDigest=head.activationDigest,sequence=head.sequence,headDigest=head.headDigest,
            stateRevision=head.stateRevision,stateDigest=head.stateDigest)
        governanceJournal.saveMarker(marker)
        send(coordinator,GroupControl(kind=GroupControlKind.GOVERNANCE_RESYNC_REQUEST_V1,
            groupId=groupId,governanceResyncRequestV1=GroupGovernanceResyncRequestV1(
                groupId=groupId,activationDigest=head.activationDigest,sequence=head.sequence,
                headDigest=head.headDigest,stateRevision=head.stateRevision,
                stateDigest=head.stateDigest,requesterMemberId=localId)))
    }
    private suspend fun receiveGovernanceResyncRequest(sender:String,control:GroupControl) {
        val request=control.governanceResyncRequestV1 ?: throw ApiFailure(400,"group_invalid")
        val localId=localMember(control.groupId) ?: throw ApiFailure(409,"group_invalid")
        val ledger=GroupLedger(records,GroupTrustedPeer {false},localId)
        val current=ledger.state(control.groupId) ?: throw ApiFailure(409,"group_invalid")
        val barrier=ledger.governanceBarrier(control.groupId) ?: throw ApiFailure(409,"group_invalid")
        val head=ledger.governanceHead(control.groupId) ?: throw ApiFailure(409,"group_invalid")
        if(ledger.isForked(control.groupId) || localId!=current.coordinatorId ||
            ledger.governanceJournalStatus(control.groupId)!=GovernanceJournalStatus.COMPLETE ||
            current.members.singleOrNull {it.memberId==request.requesterMemberId}?.deviceId!=sender ||
            !MessageDigest.isEqual(request.activationDigest,barrier.activationDigest) ||
            request.sequence>=head.sequence) throw ApiFailure(409,"group_invalid")
        // A member admitted after activation has no claim to pre-join history.
        val latestJoin=(1L..head.sequence).mapNotNull {sequence ->
            governanceJournal.entry(control.groupId,sequence)?.takeIf {
                it.action==GroupAction.ADD &&
                    it.transition.change.added?.memberId==request.requesterMemberId
            }?.sequence
        }.maxOrNull()
        if(latestJoin!=null && request.sequence<latestJoin)
            throw ApiFailure(409,"group_invalid")
        val parent=if(request.sequence==0L) null else governanceJournal.entry(control.groupId,request.sequence)
        val parentPolicy=if(request.sequence==0L) null else governanceJournal.policyEntry(
            control.groupId,request.sequence)
        if(request.sequence>0 && (parent==null)==(parentPolicy==null))
            throw ApiFailure(409,"group_invalid")
        val parentHead=parent?.let(GroupGovernanceV1::entryDigest) ?:
            parentPolicy?.let(GroupGovernancePolicyRulesV1::entryDigest) ?: barrier.activationDigest
        val parentRevision=parent?.postRevision ?: parentPolicy?.stateRevision ?:
            barrier.activationStateRevision
        val parentState=parent?.postDigest ?: parentPolicy?.stateDigest ?: barrier.activationStateDigest
        if(!MessageDigest.isEqual(parentHead,request.headDigest) ||
            parentRevision!=request.stateRevision ||
            !MessageDigest.isEqual(parentState,request.stateDigest))
            throw ApiFailure(409,"group_invalid")
        val sequence=request.sequence+1
        val entry=governanceJournal.entry(control.groupId,sequence)
        val policyEntry=governanceJournal.policyEntry(control.groupId,sequence)
        if((entry==null)==(policyEntry==null)) throw ApiFailure(409,"group_invalid")
        if(entry!=null) {
            val response=GroupGovernanceResyncResponseV1(groupId=control.groupId,
                activationDigest=barrier.activationDigest,requesterSequence=request.sequence,
                requesterHeadDigest=request.headDigest,startSequence=entry.sequence,
                startHeadDigest=request.headDigest,entries=listOf(entry),endSequence=entry.sequence,
                endHeadDigest=GroupGovernanceV1.entryDigest(entry),more=entry.sequence<head.sequence)
            send(sender,GroupControl(kind=GroupControlKind.GOVERNANCE_RESYNC_RESPONSE_V1,
                groupId=control.groupId,governanceResyncResponseV1=response))
        } else {
            val next=checkNotNull(policyEntry)
            val response=GroupGovernanceResyncResponseV2(groupId=control.groupId,
                activationDigest=barrier.activationDigest,requesterSequence=request.sequence,
                requesterHeadDigest=request.headDigest,startSequence=sequence,
                startHeadDigest=request.headDigest,policyEntry=next,endSequence=sequence,
                endHeadDigest=GroupGovernancePolicyRulesV1.entryDigest(next),more=sequence<head.sequence)
            send(sender,GroupControl(kind=GroupControlKind.GOVERNANCE_RESYNC_RESPONSE_V2,
                groupId=control.groupId,governanceResyncResponseV2=response))
        }
    }
    private suspend fun receiveGovernanceResyncResponse(sender:String,control:GroupControl) {
        val response=control.governanceResyncResponseV1 ?: throw ApiFailure(400,"group_invalid")
        val localId=localMember(control.groupId) ?: throw ApiFailure(409,"group_invalid")
        val ledger=GroupLedger(records,GroupTrustedPeer {false},localId)
        val current=ledger.state(control.groupId) ?: throw ApiFailure(409,"group_invalid")
        val head=ledger.governanceHead(control.groupId) ?: throw ApiFailure(409,"group_invalid")
        if(ledger.isForked(control.groupId) || governanceCoordinator(current)!=sender ||
            !MessageDigest.isEqual(head.activationDigest,response.activationDigest))
            throw ApiFailure(409,"group_invalid")
        if(response.startSequence<=head.sequence) {
            val result=ledger.markGovernanceForkIfValid(response.entries.single(),
                historicalPeer={member,revision ->
                    historicalAuthority.matchesAt(control.groupId,member,revision)
                },admissionEvidence=admissionV2.evidence(control.groupId,
                    response.entries.single().eventId))
            if(result==GroupApply.FORKED) throw ApiFailure(409,"group_forked")
        }
        val marker=governanceJournal.marker(control.groupId)
        if(marker==null && head.sequence==response.endSequence &&
            MessageDigest.isEqual(head.headDigest,response.endHeadDigest)) return
        if(marker==null || marker.sequence!=response.requesterSequence ||
            !MessageDigest.isEqual(marker.headDigest,response.requesterHeadDigest) ||
            head.sequence!=marker.sequence || !MessageDigest.isEqual(head.headDigest,marker.headDigest) ||
            response.startSequence!=head.sequence+1 ||
            !MessageDigest.isEqual(response.startHeadDigest,head.headDigest))
            throw ApiFailure(409,"group_invalid")
        val entry=response.entries.single()
        receiveGovernanceEntry(sender,GroupControl(kind=GroupControlKind.GOVERNANCE_ENTRY,
            groupId=control.groupId,governanceEntryV1=entry))
        val advanced=checkNotNull(ledger.governanceHead(control.groupId))
        if(advanced.sequence!=response.endSequence ||
            !MessageDigest.isEqual(advanced.headDigest,response.endHeadDigest))
            throw ApiFailure(409,"group_invalid")
        if(response.more) requestGovernanceResync(control.groupId)
        else governanceJournal.clearMarker(control.groupId)
    }
    private suspend fun receiveGovernanceResyncResponseV2(sender:String,control:GroupControl) {
        val response=control.governanceResyncResponseV2 ?: throw ApiFailure(400,"group_invalid")
        val localId=localMember(control.groupId) ?: throw ApiFailure(409,"group_invalid")
        val ledger=GroupLedger(records,GroupTrustedPeer {false},localId)
        val current=ledger.state(control.groupId) ?: throw ApiFailure(409,"group_invalid")
        val head=ledger.governanceHead(control.groupId) ?: throw ApiFailure(409,"group_invalid")
        if(ledger.isForked(control.groupId) || governanceCoordinator(current)!=sender ||
            !MessageDigest.isEqual(head.activationDigest,response.activationDigest))
            throw ApiFailure(409,"group_invalid")
        val marker=governanceJournal.marker(control.groupId)
        if(marker==null && head.sequence==response.endSequence &&
            MessageDigest.isEqual(head.headDigest,response.endHeadDigest)) return
        if(marker==null || marker.sequence!=response.requesterSequence ||
            !MessageDigest.isEqual(marker.headDigest,response.requesterHeadDigest) ||
            head.sequence!=marker.sequence || !MessageDigest.isEqual(head.headDigest,marker.headDigest) ||
            response.startSequence!=head.sequence+1 ||
            !MessageDigest.isEqual(response.startHeadDigest,head.headDigest))
            throw ApiFailure(409,"group_invalid")
        val wrapped=response.membershipEntry
        if(wrapped!=null) receiveGovernanceEntry(sender,GroupControl(kind=GroupControlKind.GOVERNANCE_ENTRY,
            groupId=control.groupId,governanceEntryV1=wrapped))
        else receivePolicyEntry(sender,GroupControl(kind=GroupControlKind.GOVERNANCE_POLICY_ENTRY_V1,
            groupId=control.groupId,governancePolicyEntryV1=checkNotNull(response.policyEntry)))
        val advanced=checkNotNull(ledger.governanceHead(control.groupId))
        if(advanced.sequence!=response.endSequence ||
            !MessageDigest.isEqual(advanced.headDigest,response.endHeadDigest))
            throw ApiFailure(409,"group_invalid")
        if(response.more) requestGovernanceResync(control.groupId)
        else governanceJournal.clearMarker(control.groupId)
    }
    private suspend fun retryMarkedGovernanceResync() {
        for(id in governanceJournal.markedGroups()) {
            try {requestGovernanceResync(id)}
            catch(e:CancellationException) {throw e}
            catch(_:ApiFailure) { /* A durable marker keeps the gap visible. */ }
        }
    }
    private suspend fun receiveGovernanceEntryAck(sender:String,control:GroupControl) {
        val ack=control.governanceEntryAckV1 ?: throw ApiFailure(400,"group_invalid")
        val pending=governanceV1.outstanding(control.groupId) ?: throw ApiFailure(409,"group_invalid")
        val entry=pending.entry
        val localId=localMember(control.groupId) ?: throw ApiFailure(409,"group_invalid")
        val current=state(control.groupId) ?: throw ApiFailure(409,"group_invalid")
        if(localId!=current.coordinatorId ||
            current.members.singleOrNull {it.memberId==ack.memberId}?.deviceId!=sender ||
            !GroupGovernanceV1.verifyApplied(ack,entry))
            throw ApiFailure(409,"group_invalid")
        val old=pending.applied.singleOrNull {it.memberId==ack.memberId}
        if(old!=null && !NetworkCodec.encode(old).contentEquals(NetworkCodec.encode(ack)))
            throw ApiFailure(409,"group_invalid")
        if(old==null) governanceV1.saveOutstanding(pending.copy(applied=pending.applied+ack))
    }
    private suspend fun receiveCheckpointSignRequest(sender:String,control:GroupControl) {
        val checkpoint=control.governanceCheckpointV1 ?: throw ApiFailure(400,"group_invalid")
        val policyProof=control.governancePolicyCheckpointV1 ?: throw ApiFailure(400,"group_invalid")
        val binding=control.governedAdmissionBindingV1 ?: throw ApiFailure(400,"group_invalid")
        val proof=control.admission ?: throw ApiFailure(400,"group_invalid")
        val certificate=control.certificateV2 ?: throw ApiFailure(400,"group_invalid")
        val invite=control.invite ?: throw ApiFailure(400,"group_invalid")
        val (current,localId)=baselineState(control.groupId)
        val head=activeGovernanceHead(control.groupId) ?: throw ApiFailure(409,"group_invalid")
        val barrier=GroupLedger(records,GroupTrustedPeer {false},localId)
            .governanceBarrier(control.groupId) ?: throw ApiFailure(409,"group_invalid")
        val policy=GroupLedger(records,GroupTrustedPeer {false},localId)
            .governancePolicy(control.groupId) ?: throw ApiFailure(409,"group_invalid")
        if(localId!=current.ownerId || localId==current.coordinatorId ||
            governanceCoordinator(current)!=sender ||
            !GroupGovernedAdmissionV1.verifyBinding(binding,current,certificate,head) ||
            !admissionV2.matchesOwnApproval(current,certificate,localId) ||
            !GroupGovernedAdmissionV1.verifyCheckpoint(checkpoint,current,proof,certificate,
                binding,requireOwner=false) ||
            !GroupGovernancePolicyCheckpointRulesV1.verify(policyProof,checkpoint,current,
                requireOwner=false) ||
            !MessageDigest.isEqual(policyProof.policyDigest,
                GroupGovernancePolicyRulesV1.digest(head.activationDigest,policy)) ||
            policyProof.moderationFilter?.contentEquals(
                chats.moderationFilter(control.groupId))!=true ||
            !MessageDigest.isEqual(checkpoint.activationStateDigest,barrier.activationStateDigest) ||
            checkpoint.activationStateRevision!=barrier.activationStateRevision ||
            !MessageDigest.isEqual(checkpoint.baselineCertificateDigest,
                barrier.baselineCertificateDigest) ||
            invite.inviteId!=binding.inviteId ||
            !GroupStatements.verifyInvite(invite,current,emptySet()))
            throw ApiFailure(409,"group_invalid")
        val signed=checkpoint.copy(ownerSignature=network.signGroupStatement(
            GroupGovernedAdmissionV1.ownerStatement(checkpoint)))
        val signedPolicy=policyProof.copy(ownerSignature=network.signGroupStatement(
            GroupGovernancePolicyCheckpointRulesV1.ownerStatement(policyProof)))
        if(!GroupGovernedAdmissionV1.verifyCheckpoint(signed,current,proof,certificate,binding))
            throw ApiFailure(409,"group_invalid")
        send(sender,GroupControl(kind=GroupControlKind.GOVERNANCE_CHECKPOINT_SIGN_RESPONSE_V1,
            groupId=control.groupId,governanceCheckpointV1=signed,
            governancePolicyCheckpointV1=signedPolicy))
    }
    private suspend fun receiveCheckpointSignResponse(sender:String,control:GroupControl) {
        val checkpoint=control.governanceCheckpointV1 ?: throw ApiFailure(400,"group_invalid")
        val policyProof=control.governancePolicyCheckpointV1 ?: throw ApiFailure(400,"group_invalid")
        val pending=governedAdmission.pending(control.groupId) ?: throw ApiFailure(409,"group_invalid")
        val (current,localId)=baselineState(control.groupId)
        val head=activeGovernanceHead(control.groupId) ?: throw ApiFailure(409,"group_invalid")
        val expected=pending.checkpoint ?: throw ApiFailure(409,"group_invalid")
        val expectedPolicy=policyCheckpoints.pending(control.groupId)
            ?: throw ApiFailure(409,"group_invalid")
        if(localId!=current.coordinatorId || governanceOwner(current)!=sender ||
            expected.ownerSignature.isNotEmpty() ||
            expectedPolicy.ownerSignature.isNotEmpty() ||
            !NetworkCodec.encode(expectedPolicy).contentEquals(NetworkCodec.encode(
                policyProof.copy(ownerSignature=byteArrayOf()))) ||
            !NetworkCodec.encode(expected).contentEquals(NetworkCodec.encode(
                checkpoint.copy(ownerSignature=byteArrayOf()))) ||
            !GroupGovernedAdmissionV1.verifyBinding(pending.binding,current,
                pending.certificate,head) ||
            !GroupGovernedAdmissionV1.verifyCheckpoint(checkpoint,current,pending.proof,
                pending.certificate,pending.binding) ||
            !GroupGovernancePolicyCheckpointRulesV1.verify(policyProof,checkpoint,current) ||
            !MessageDigest.isEqual(policyProof.policyDigest,
                GroupGovernancePolicyRulesV1.digest(head.activationDigest,
                    GroupLedger(records,GroupTrustedPeer {false},localId)
                        .governancePolicy(control.groupId) ?: throw ApiFailure(409,"group_invalid"))))
            throw ApiFailure(409,"group_invalid")
        governedAdmission.savePending(pending.copy(checkpoint=checkpoint))
        policyCheckpoints.savePending(policyProof)
        flushGovernedAdmission()
    }
    private suspend fun receiveGovernanceBootstrap(sender:String,control:GroupControl) {
        val checkpoint=control.governanceCheckpointV1 ?: throw ApiFailure(400,"group_invalid")
        val policyProof=control.governancePolicyCheckpointV1 ?: throw ApiFailure(400,"group_invalid")
        val entry=control.governanceEntryV1 ?: throw ApiFailure(400,"group_invalid")
        val incoming=records.transaction {records.read(incomingKey(checkpoint.inviteId))?.let {
            NetworkCodec.decode<Incoming>(it,16_384)
        }} ?: throw ApiFailure(409,"group_invalid")
        if(incoming.status!=InviteState.ACCEPTING || incoming.sender!=sender ||
            incoming.offer.groupId!=control.groupId) throw ApiFailure(409,"group_invalid")
        val proof=incoming.offer.admission ?: throw ApiFailure(409,"group_invalid")
        val certificate=incoming.offer.certificateV2 ?: throw ApiFailure(409,"group_invalid")
        val binding=incoming.offer.governedAdmissionBindingV1 ?: throw ApiFailure(409,"group_invalid")
        val parent=incoming.offer.state ?: throw ApiFailure(409,"group_invalid")
        val acceptedInvite=entry.transition.change.invite ?: throw ApiFailure(409,"group_invalid")
        val offered=incoming.offer.invite ?: throw ApiFailure(409,"group_invalid")
        val localId=localMember(control.groupId) ?: throw ApiFailure(409,"group_invalid")
        if(localId!=proof.target.memberId ||
            !MessageDigest.isEqual(checkpoint.candidateDigest,
                GroupStatements.digestMember(proof.target)) ||
            parent.coordinatorId!=acceptedInvite.inviterId ||
            parent.members.single {it.memberId==parent.coordinatorId}.deviceId!=sender ||
            !NetworkCodec.encode(offered).contentEquals(NetworkCodec.encode(
                acceptedInvite.copy(targetAcceptance=byteArrayOf()))) ||
            !GroupGovernedAdmissionV1.verifyCheckpoint(checkpoint,parent,proof,
                certificate,binding) ||
            !GroupGovernancePolicyCheckpointRulesV1.verify(policyProof,checkpoint,parent))
            throw ApiFailure(409,"group_invalid")
        val trusted=trusted(parent.members+proof.target,control.groupId)
        val unsignedAck=GroupGovernanceV1.unsignedApplied(entry,localId)
        val ack=unsignedAck.copy(signature=network.signGroupStatement(
            GroupGovernanceV1.appliedStatement(unsignedAck)))
        if(!GroupGovernanceV1.verifyApplied(ack,entry)) throw ApiFailure(409,"group_invalid")
        val evidence=GovernedJoinEvidenceV1(checkpoint,binding,proof,certificate)
        val result=GroupLedger(records,trusted,localId).acceptGovernedBootstrap(evidence,
            acceptedInvite,entry,policyProof) {
            val digest=DeviceAuth.digest(NetworkCodec.encode(proof))
            entry.transition.next.members.forEach {member ->
                currentAuthority.anchor(entry.transition.next,member,digest)
            }
            parent.members.forEach {historicalAuthority.anchorBaseline(proof,it)}
            historicalAuthority.record(parent,entry.transition,certificate)
            admissionV2.saveEvidence(control.groupId,entry.eventId,certificate)
            governanceV1.saveOutstanding(PendingGovernanceEntryV1(entry,listOf(ack)))
            records.remove(incomingKey(checkpoint.inviteId))
            chats.cancelStale(control.groupId,entry.transition.next.epoch)
        }
        if(result!=GroupApply.ACCEPTED && result!=GroupApply.DUPLICATE)
            throw ApiFailure(409,"group_invalid")
        flushGovernanceEntries()
    }
    private suspend fun flushGovernedAdmission() {
        for(groupId in governedAdmission.pendingGroups()) {
            val pending=governedAdmission.pending(groupId) ?: continue
            val current=state(groupId) ?: continue
            val localId=localMember(groupId) ?: continue
            val ledger=GroupLedger(records,GroupTrustedPeer {false},localId)
            val head=runCatching {activeGovernanceHead(groupId)}.getOrNull() ?: continue
            val barrier=ledger.governanceBarrier(groupId) ?: continue
            val binding=pending.binding
            val checkpoint=pending.checkpoint ?: continue
            val policyProof=policyCheckpoints.pending(groupId) ?: continue
            if(localId!=current.coordinatorId || ledger.isForked(groupId) ||
                !GroupGovernedAdmissionV1.verifyBinding(binding,current,pending.certificate,head) ||
                !MessageDigest.isEqual(checkpoint.activationDigest,barrier.activationDigest)) {
                discardStaleGovernedAdmission(groupId,binding.inviteId)
                continue
            }
            if(checkpoint.ownerSignature.isEmpty()) {
                sendGovernanceOnce(groupId,"checkpoint-${binding.inviteId}",
                    governanceOwner(current),GroupControl(
                        kind=GroupControlKind.GOVERNANCE_CHECKPOINT_SIGN_REQUEST_V1,
                        groupId=groupId,inviteId=binding.inviteId,invite=pending.invite,
                        admission=pending.proof,certificateV2=pending.certificate,
                        governedAdmissionBindingV1=binding,governanceCheckpointV1=checkpoint,
                        governancePolicyCheckpointV1=policyProof))
                continue
            }
            if(!GroupGovernedAdmissionV1.verifyCheckpoint(checkpoint,current,pending.proof,
                    pending.certificate,binding) ||
                !GroupGovernancePolicyCheckpointRulesV1.verify(policyProof,checkpoint,current) ||
                !MessageDigest.isEqual(policyProof.policyDigest,
                    GroupGovernancePolicyRulesV1.digest(head.activationDigest,
                        GroupLedger(records,GroupTrustedPeer {false},localId)
                            .governancePolicy(groupId) ?: throw ApiFailure(409,"group_invalid"))))
                throw ApiFailure(409,"group_invalid")
            try {retireCompletedGovernanceEntry(groupId,current,localId)}
            catch(_:ApiFailure) {continue}
            val candidate=pending.invite.target
            val peers=trusted(current.members+candidate,groupId)
            val change=GroupChange(binding.eventId,GroupAction.ADD,localId,
                added=candidate,invite=pending.invite)
            val next=try {GroupRules.derive(current,change,emptySet(),peers)}
                catch(_:IllegalArgumentException) {throw ApiFailure(409,"group_invalid")}
            val event=GroupTransition(change,next,
                network.signGroupStatement(GroupStatements.actor(current,change,next)),
                network.signGroupStatement(GroupStatements.coordinator(current,change,next)))
            val unsigned=GroupGovernanceEntryV1(groupId=groupId,
                activationDigest=head.activationDigest,sequence=head.sequence+1,
                previousHeadDigest=head.headDigest,eventId=change.eventId,
                preRevision=current.revision,preDigest=GroupStatements.digest(current),
                actorId=localId,action=GroupAction.ADD,
                transitionDigest=DeviceAuth.digest(NetworkCodec.encode(event)),
                postRevision=next.revision,postDigest=GroupStatements.digest(next),
                transition=event,actorSignature=byteArrayOf(),coordinatorSignature=byteArrayOf())
            val entry=unsigned.copy(actorSignature=network.signGroupStatement(
                GroupGovernanceV1.actorStatement(unsigned)),
                coordinatorSignature=network.signGroupStatement(
                    GroupGovernanceV1.coordinatorStatement(unsigned)))
            if(!GroupGovernanceV1.verifyEntry(entry,current,head))
                throw ApiFailure(409,"group_invalid")
            val unsignedAck=GroupGovernanceV1.unsignedApplied(entry,localId)
            val ack=unsignedAck.copy(signature=network.signGroupStatement(
                GroupGovernanceV1.appliedStatement(unsignedAck)))
            if(!GroupGovernanceV1.verifyApplied(ack,entry)) throw ApiFailure(409,"group_invalid")
            val result=GroupLedger(records,peers,localId).applyGovernedTransition(groupId,head,
                event,GroupGovernanceV1.entryDigest(entry)) {
                governanceJournal.append(entry,barrier,
                    checkNotNull(GroupLedger(records,peers,localId).governanceHead(groupId)))
                val nextLedger=GroupLedger(records,peers,localId)
                chats.cancelStaleHead(groupId,nextLedger.governanceHead(groupId),
                    nextLedger.governancePolicy(groupId))
                currentAuthority.anchor(next,candidate,DeviceAuth.digest(NetworkCodec.encode(event)))
                historicalAuthority.record(current,event,pending.certificate)
                admissionV2.saveEvidence(groupId,event.change.eventId,pending.certificate)
                admissionV2.clearPending(groupId)
                admissionV2.removeOwn(groupId,binding.inviteId)
                governedAdmission.clearReservation(groupId)
                governedAdmission.clearPending(groupId)
                governedAdmission.saveCheckpoint(checkpoint)
                policyCheckpoints.clearPending(groupId)
                policyCheckpoints.saveCommitted(policyProof)
                governanceV1.saveOutstanding(PendingGovernanceEntryV1(entry,listOf(ack)))
                records.read(outgoingKey(binding.inviteId))?.let {bytes ->
                    val outgoing=NetworkCodec.decode<Outgoing>(bytes,16_384)
                    records.write(outgoingKey(binding.inviteId),
                        NetworkCodec.encode(outgoing.copy(used=true)))
                }
                chats.cancelStale(groupId,next.epoch)
            }
            if(result!=GroupApply.ACCEPTED) throw ApiFailure(409,"group_invalid")
            flushGovernanceEntries()
        }
    }
    private suspend fun flushGovernanceEntries() {
        for(groupId in governanceV1.outstandingGroups()) {
            val pending=governanceV1.outstanding(groupId) ?: continue
            val entry=pending.entry
            val current=state(groupId) ?: continue
            if(current.revision!=entry.postRevision ||
                !MessageDigest.isEqual(GroupStatements.digest(current),entry.postDigest)) continue
            val localId=localMember(groupId) ?: continue
            if(localId==current.coordinatorId) {
                for(member in current.members.filter {it.memberId!=localId &&
                    pending.applied.none {ack ->ack.memberId==it.memberId}})
                    if(entry.action==GroupAction.ADD &&
                        entry.transition.change.added?.memberId==member.memberId) {
                        val checkpoint=governedAdmission.checkpoint(groupId) ?: continue
                        val policyProof=policyCheckpoints.committed(groupId) ?: continue
                        sendGovernanceOnce(groupId,"entry-${entry.sequence}",member.deviceId,
                            GroupControl(kind=GroupControlKind.GOVERNANCE_BOOTSTRAP_V1,
                                groupId=groupId,governanceEntryV1=entry,
                                governanceCheckpointV1=checkpoint,
                                governancePolicyCheckpointV1=policyProof))
                    } else sendGovernanceOnce(groupId,"entry-${entry.sequence}",member.deviceId,
                        GroupControl(kind=GroupControlKind.GOVERNANCE_ENTRY,groupId=groupId,
                            governanceEntryV1=entry))
            } else {
                val own=pending.applied.singleOrNull {it.memberId==localId} ?: continue
                sendGovernanceOnce(groupId,"entry-ack-${entry.sequence}",
                    governanceCoordinator(current),
                    GroupControl(kind=GroupControlKind.GOVERNANCE_ENTRY_APPLIED_ACK,
                        groupId=groupId,governanceEntryAckV1=own))
            }
        }
        for(groupId in governanceV1.policyOutstandingGroups()) {
            val pending=governanceV1.policyOutstanding(groupId) ?: continue
            val entry=pending.entry
            val current=state(groupId) ?: continue
            if(current.revision!=entry.stateRevision ||
                !MessageDigest.isEqual(GroupStatements.digest(current),entry.stateDigest)) continue
            val localId=localMember(groupId) ?: continue
            if(localId==current.coordinatorId) {
                for(member in current.members.filter {it.memberId!=localId &&
                    pending.applied.none {ack ->ack.memberId==it.memberId}})
                    sendGovernanceOnce(groupId,"entry-${entry.sequence}",member.deviceId,
                        GroupControl(kind=GroupControlKind.GOVERNANCE_POLICY_ENTRY_V1,
                            groupId=groupId,governancePolicyEntryV1=entry))
            } else {
                val own=pending.applied.singleOrNull {it.memberId==localId} ?: continue
                sendGovernanceOnce(groupId,"entry-ack-${entry.sequence}",
                    governanceCoordinator(current),
                    GroupControl(kind=GroupControlKind.GOVERNANCE_POLICY_ENTRY_ACK_V1,
                        groupId=groupId,governanceEntryAckV1=own))
            }
        }
    }
    /** All peers, including blocked canonical members, get a fresh exact registered-binding check. */
    private suspend fun verifyBaselinePeers(state:GroupState,localId:String) {
        for(member in state.members) {
            val matches=if(member.memberId==localId) {
                val own=ownMember(member.role,member.joinedEpoch,member.memberId)
                MessageDigest.isEqual(GroupStatements.digestMember(own),
                    GroupStatements.digestMember(member))
            } else authority.matchesCurrentGroupMember(state.groupId,member)
            if(!matches) throw ApiFailure(409,"group_binding_unavailable")
        }
        val (latest,id)=baselineState(state.groupId)
        if(id!=localId || !MessageDigest.isEqual(GroupStatements.digest(latest),
                GroupStatements.digest(state))) throw ApiFailure(409,"group_baseline_stale")
    }
    private fun signedOwnBaselineApproval(proposal:GroupAuthorityBaselineProposalV1,
        localId:String):GroupAuthorityBaselineApprovalV1 {
        val unsigned=GroupAuthorityBaselineV1.unsignedApproval(proposal,localId)
        val signed=unsigned.copy(signature=network.signGroupStatement(
            GroupAuthorityBaselineV1.approvalStatement(unsigned)))
        if(!GroupAuthorityBaselineV1.verifyApproval(signed,proposal))
            throw ApiFailure(409,"group_invalid")
        return signed
    }
    private fun saveOwnBaseline(proposal:GroupAuthorityBaselineProposalV1,
        approval:GroupAuthorityBaselineApprovalV1)=baselineV1.saveOwn(OwnBaselineApprovalV1(
        proposal.groupId,proposal.proposalId,proposal.state.revision,
        GroupAuthorityBaselineV1.proposalDigest(proposal),proposal.memberSetDigest,approval))
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
    private suspend fun trusted(members:Collection<GroupMember>,groupId:String?=null,
        historicalOnly:Boolean=false):GroupTrustedPeer {
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
                val current=if(repository.isActiveContact(member.deviceId))
                    try {authority.resolveTrustedGroupAuthority(member.deviceId).let {binding ->
                    binding.accountId==member.accountId && binding.deviceId==member.deviceId &&
                        MessageDigest.isEqual(binding.authPublicKey,member.authPublicKey) &&
                        MessageDigest.isEqual(binding.identityDigest,member.signalIdentityDigest)
                    }} catch(e:CancellationException) {throw e}
                    catch(_:ApiFailure) {false}
                else false
                current || (groupId!=null && authority.matchesCurrentGroupMember(groupId,member))
            }
            if(!matches && !historicalOnly) throw ApiFailure(409,"group_binding_unavailable")
            if(matches) valid[member.memberId]=member
        }
        return GroupTrustedPeer {member -> valid[member.memberId]?.let {
            it.accountId==member.accountId && it.deviceId==member.deviceId &&
                MessageDigest.isEqual(it.authPublicKey,member.authPublicKey) &&
                MessageDigest.isEqual(it.signalIdentityDigest,member.signalIdentityDigest)
        }==true}
    }
    private suspend fun send(target:String,control:GroupControl,committed:(String)->Unit = {}):String {
        val groupScoped=!repository.isActiveContact(target)
        if(groupScoped) {
            val state=state(control.groupId) ?: throw ApiFailure(409,"group_peer_unavailable")
            val member=state.members.singleOrNull {it.deviceId==target}
                ?: throw ApiFailure(409,"group_peer_unavailable")
            if(!control.kind.blockSafeMaintenance() ||
                !authority.matchesCurrentGroupMember(control.groupId,member))
                throw ApiFailure(409,"group_peer_unavailable")
        } else if(!repository.isActiveContact(target) || !repository.groupPeer(target))
            throw ApiFailure(409,"group_peer_unavailable")
        val plaintext=ConversationPayload.encodeGroup(control)
        return try {outbox.enqueue(target,plaintext) {id ->
            committed(id)
            if(groupScoped) records.write("app/group/system-outbox/$id",
                (control.groupId+"/"+target).toByteArray())
        }} finally {plaintext.fill(0)}
    }

    private fun allAdmissionV2Peers(state:GroupState,target:String,localId:String):Boolean =
        repository.admissionV2Peer(target) && state.members.all {member ->
            member.memberId==localId || repository.admissionV2Peer(member.deviceId)
        }
    private fun activeGovernanceHead(groupId:String):GovernanceHeadFoundationV1? {
        val localId=localMember(groupId) ?: return null
        val ledger=GroupLedger(records,GroupTrustedPeer {false},localId)
        if(ledger.governanceBarrier(groupId)==null) return null
        if(ledger.governanceJournalStatus(groupId)!=GovernanceJournalStatus.COMPLETE ||
            ledger.isForked(groupId) || (governanceV1.ready(groupId)==null &&
                governedAdmission.checkpoint(groupId)==null &&
                governedAdmission.join(groupId)==null))
            throw ApiFailure(409,"group_governance_legacy_incomplete")
        return ledger.governanceHead(groupId) ?: throw ApiFailure(409,"group_invalid")
    }
    private fun discardStaleGovernedAdmission(groupId:String,inviteId:String)=records.transaction {
        admissionV2.clearPending(groupId)
        admissionV2.removeOwn(groupId,inviteId)
        governedAdmission.clearPending(groupId)
        policyCheckpoints.clearPending(groupId)
        governedAdmission.clearReservation(groupId)
        records.remove(outgoingKey(inviteId))
    }

    /** A separate durable protocol; never mutate the frozen v1 GroupAdmission/Invite bytes. */
    private suspend fun beginAdmissionV2(offer:GroupControl,initialize:()->Unit = {}) {
        val state=offer.state ?: throw ApiFailure(409,"group_invalid")
        val invite=offer.invite ?: throw ApiFailure(409,"group_invalid")
        val localId=localMember(state.groupId) ?: state.coordinatorId
        val governedHead=activeGovernanceHead(state.groupId)
        if(governedHead!=null && localId==state.coordinatorId)
            retireCompletedGovernanceEntry(state.groupId,state,localId)
        val otherInvitation=records.transaction {
            records.keys("app/group/outgoing/").any {key ->
                records.read(key)?.let {NetworkCodec.decode<Outgoing>(it,16_384)}?.let {
                    it.offer.groupId==state.groupId && !it.used
                }==true
            }
        }
        if(state.coordinatorId!=localId || admissionV2.pending(state.groupId)!=null ||
            (governedHead==null && governanceV1.pending(state.groupId)!=null) ||
            otherInvitation ||
            !allAdmissionV2Peers(state,invite.target.deviceId,localId) ||
            !authority.verifyAdmissionCandidate(invite.target) ||
            (governedHead!=null && (governedHead.sequence>=GroupGovernanceJournalV1.MAX_ENTRIES ||
                !repository.governanceV1Peer(invite.target.deviceId) ||
                !allGovernancePeers(state,localId))))
            throw ApiFailure(409,"group_invalid")
        val unsigned=AdmissionProposalV2(groupId=state.groupId,parent=state,
            parentDigest=GroupStatements.digest(state),inviteId=invite.inviteId,
            inviterId=localId,candidate=invite.target,
            candidateDigest=AdmissionV2.candidateDigest(invite.target),eventId=GroupIds.create(),
            inviterSignature=byteArrayOf())
        val proposal=unsigned.copy(inviterSignature=network.signGroupStatement(
            AdmissionV2.proposalStatement(unsigned)))
        if(!AdmissionV2.verifyProposal(proposal,state)) throw ApiFailure(409,"group_invalid")
        val approvalUnsigned=AdmissionV2.approval(proposal,localId,byteArrayOf())
        val approval=approvalUnsigned.copy(signature=network.signGroupStatement(
            AdmissionV2.approvalStatement(approvalUnsigned)))
        if(!AdmissionV2.verifyApproval(approval,proposal)) throw ApiFailure(409,"group_invalid")
        records.transaction {
            val latestHead=activeGovernanceHead(state.groupId)
            require(if(governedHead==null) latestHead==null &&
                governanceV1.pending(state.groupId)==null else latestHead!=null &&
                GovernanceFoundationStore(records).matches(governedHead,latestHead))
            initialize()
            if(governedHead!=null) governedAdmission.saveReservation(state.groupId,
                GovernedAdmissionReservationV1(invite.inviteId,governedHead))
            admissionV2.saveOwn(state.groupId,OwnAdmissionApprovalV2(
                AdmissionV2.proposalDigest(proposal),proposal.parentDigest,state.revision,
                proposal.inviteId,proposal.candidateDigest,localId,approval=approval))
            admissionV2.savePending(state.groupId,PendingAdmissionV2(offer,proposal,listOf(approval)))
        }
        flushAdmissionsV2()
    }

    private suspend fun flushAdmissionsV2() {
        for(groupId in admissionV2.pendingGroups()) {
            val pending=admissionV2.pending(groupId) ?: continue
            val proposal=pending.proposal
            val localId=localMember(groupId) ?: continue
            val current=state(groupId) ?: continue
            if(localId!=current.coordinatorId ||
                !MessageDigest.isEqual(GroupStatements.digest(current),proposal.parentDigest)) {
                if(governedAdmission.reservation(groupId)!=null)
                    discardStaleGovernedAdmission(groupId,proposal.inviteId)
                else {
                    admissionV2.clearPending(groupId)
                    admissionV2.removeOwn(groupId,proposal.inviteId)
                }
                continue
            }
            val reservation=governedAdmission.reservation(groupId)
            if(reservation==null && GroupLedger(records,GroupTrustedPeer {false},localId)
                    .governanceBarrier(groupId)!=null) {
                admissionV2.clearPending(groupId)
                admissionV2.removeOwn(groupId,proposal.inviteId)
                continue
            }
            if(reservation!=null) {
                val head=runCatching {activeGovernanceHead(groupId)}.getOrNull()
                if(reservation.inviteId!=proposal.inviteId || head==null ||
                    !GovernanceFoundationStore(records).matches(reservation.head,head)) {
                    discardStaleGovernedAdmission(groupId,proposal.inviteId)
                    continue
                }
            }
            if(pending.approvals.size==current.members.size) {
                if(pending.invitationQueued) continue
                val certificate=AdmissionV2.certificate(proposal,pending.approvals)
                if(!AdmissionV2.verifyCertificate(certificate,current)) throw ApiFailure(409,"group_invalid")
                val binding=reservation?.let {
                    val unsigned=GroupGovernedAdmissionV1.unsignedBinding(certificate,it.head)
                    unsigned.copy(coordinatorSignature=network.signGroupStatement(
                        GroupGovernedAdmissionV1.bindingStatement(unsigned)))
                }
                val offered=pending.offer.copy(certificateV2=certificate,
                    governedAdmissionBindingV1=binding)
                if(binding!=null && !GroupGovernedAdmissionV1.verifyBinding(binding,current,
                        certificate,reservation.head)) throw ApiFailure(409,"group_invalid")
                try {send(proposal.candidate.deviceId,offered) {
                        if(binding!=null) governedAdmission.saveBinding(binding)
                        records.write(outgoingKey(proposal.inviteId),
                            NetworkCodec.encode(Outgoing(proposal.candidate.deviceId,offered)))
                        admissionV2.savePending(groupId,pending.copy(invitationQueued=true))
                    }} catch(e:CancellationException) {throw e}
                    catch(_:ApiFailure) { /* Retain exact signed evidence and retry. */ }
            } else {
                for(member in current.members.filter {it.memberId!=localId}) {
                    if(admissionV2.queued(groupId,proposal.inviteId,member.deviceId)) continue
                    try {send(member.deviceId,GroupControl(kind=GroupControlKind.ADMISSION_V2_PROPOSAL,
                        groupId=groupId,inviteId=proposal.inviteId,proposalV2=proposal)) {id ->
                        admissionV2.markQueued(groupId,proposal.inviteId,member.deviceId,id)
                    }} catch(e:CancellationException) {throw e}
                    catch(_:ApiFailure) { /* Durable pending proposal retries after reconnect. */ }
                }
            }
        }
    }
    private suspend fun retryOwnApprovalsV2() {
        for((groupId,inviteId) in admissionV2.ownKeys()) {
            val approval=admissionV2.own(groupId,inviteId) ?: continue
            val current=state(groupId) ?: continue
            if(!MessageDigest.isEqual(GroupStatements.digest(current),approval.parentDigest)) {
                admissionV2.removeOwn(groupId,inviteId)
                continue
            }
            val coordinator=current.members.single {it.memberId==current.coordinatorId}.deviceId
            if(coordinator==network.ownDevice() || admissionV2.queued(groupId,inviteId,coordinator)) continue
            try {send(coordinator,GroupControl(kind=GroupControlKind.ADMISSION_V2_APPROVAL,
                groupId=groupId,inviteId=inviteId,approvalV2=approval.approval)) {id ->
                admissionV2.markQueued(groupId,inviteId,coordinator,id)
            }} catch(e:CancellationException) {throw e}
            catch(_:ApiFailure) { /* Retry the same signature; never sign a new approval. */ }
        }
    }
    /** Protocol entry point for a future management flow; no governance policy is activated. */
    suspend fun beginAuthorityBaseline(groupId:String)=textMutex.withLock {
        val (current,localId)=baselineState(groupId)
        if(current.coordinatorId!=localId || baselineV1.active(groupId)!=null ||
            baselineV1.pending(groupId)!=null) throw ApiFailure(409,"group_baseline_unavailable")
        val unknown=current.members.filter {it.memberId!=localId &&
            !repository.baselineV1Peer(it.deviceId)}
        if(unknown.isNotEmpty()) {
            records.transaction {
                require(records.read("app/group/baseline-v1/start-intent/$groupId")!=null ||
                    records.keys("app/group/baseline-v1/start-intent/").size<64)
                records.write("app/group/baseline-v1/start-intent/$groupId",
                    GroupStatements.digest(current))
            }
            unknown.forEach {sendBaselineReadiness(current,it)}
            return@withLock
        }
        startAuthorityBaseline(groupId)
    }
    private suspend fun startAuthorityBaseline(groupId:String) {
        val (current,localId)=baselineState(groupId)
        if(current.coordinatorId!=localId || baselineV1.active(groupId)!=null ||
            baselineV1.pending(groupId)!=null || baselineV1.own(groupId)!=null ||
            current.members.any {it.memberId!=localId && !repository.baselineV1Peer(it.deviceId)})
            throw ApiFailure(409,"group_baseline_unavailable")
        verifyBaselinePeers(current,localId)
        val unsigned=GroupAuthorityBaselineV1.unsignedProposal(current,GroupIds.create())
        val proposal=unsigned.copy(coordinatorSignature=network.signGroupStatement(
            GroupAuthorityBaselineV1.proposalStatement(unsigned)))
        if(!GroupAuthorityBaselineV1.verifyProposal(proposal,current))
            throw ApiFailure(409,"group_invalid")
        val approval=signedOwnBaselineApproval(proposal,localId)
        records.transaction {
            val (latest,id)=baselineState(groupId)
            require(id==localId && MessageDigest.isEqual(GroupStatements.digest(latest),proposal.stateDigest) &&
                baselineV1.active(groupId)==null && baselineV1.pending(groupId)==null)
            saveOwnBaseline(proposal,approval)
            baselineV1.savePending(PendingBaselineV1(proposal,listOf(approval)))
        }
        flushBaselinesV1()
    }

    private suspend fun receiveBaselineProposalV1(sender:String,control:GroupControl) {
        val proposal=control.baselineProposalV1 ?: throw ApiFailure(400,"group_invalid")
        val (current,localId)=baselineState(control.groupId)
        if(current.members.single {it.memberId==current.coordinatorId}.deviceId!=sender ||
            !GroupAuthorityBaselineV1.verifyProposal(proposal,current) ||
            baselineV1.active(control.groupId)!=null) throw ApiFailure(409,"group_invalid")
        val priorOwn=baselineV1.own(control.groupId)
        if(priorOwn!=null && (priorOwn.stateRevision!=current.revision ||
                !MessageDigest.isEqual(priorOwn.approval.stateDigest,GroupStatements.digest(current))))
            baselineV1.clearOwn(control.groupId)
        val existing=baselineV1.own(control.groupId)
        if(existing!=null && (!MessageDigest.isEqual(existing.proposalDigest,
                GroupAuthorityBaselineV1.proposalDigest(proposal)) ||
                existing.proposalId!=proposal.proposalId)) throw ApiFailure(409,"group_baseline_pending")
        val approval=if(existing!=null) existing.approval else {
            verifyBaselinePeers(current,localId)
            val signed=signedOwnBaselineApproval(proposal,localId)
            records.transaction {
                val (latest,id)=baselineState(control.groupId)
                require(id==localId && MessageDigest.isEqual(GroupStatements.digest(latest),
                    proposal.stateDigest) && baselineV1.active(control.groupId)==null)
                saveOwnBaseline(proposal,signed)
            }
            signed
        }
        val coordinator=current.members.single {it.memberId==current.coordinatorId}.deviceId
        if(coordinator!=network.ownDevice() && canRetryControl(
                baselineV1.queuedOutbox(control.groupId,proposal.proposalId,"approval",coordinator)))
            send(coordinator,GroupControl(kind=GroupControlKind.BASELINE_V1_APPROVAL,
                groupId=control.groupId,baselineApprovalV1=approval)) {id ->
                baselineV1.markQueued(control.groupId,proposal.proposalId,"approval",coordinator,id)
            }
    }
    private suspend fun receiveBaselineApprovalV1(sender:String,control:GroupControl) {
        val approval=control.baselineApprovalV1 ?: throw ApiFailure(400,"group_invalid")
        val pending=baselineV1.pending(control.groupId) ?: throw ApiFailure(409,"group_invalid")
        val (current,localId)=baselineState(control.groupId)
        if(localId!=current.coordinatorId ||
            !GroupAuthorityBaselineV1.verifyProposal(pending.proposal,current) ||
            current.members.singleOrNull {it.memberId==approval.approverId}?.deviceId!=sender ||
            !GroupAuthorityBaselineV1.verifyApproval(approval,pending.proposal))
            throw ApiFailure(409,"group_invalid")
        val prior=pending.approvals.singleOrNull {it.approverId==approval.approverId}
        if(prior!=null && !NetworkCodec.encode(prior).contentEquals(NetworkCodec.encode(approval)))
            throw ApiFailure(409,"group_invalid")
        if(prior==null) baselineV1.savePending(pending.copy(approvals=pending.approvals+approval))
        flushBaselinesV1()
    }
    private suspend fun receiveBaselineCertificateV1(sender:String,control:GroupControl) {
        val certificate=control.baselineCertificateV1 ?: throw ApiFailure(400,"group_invalid")
        val active=baselineV1.active(control.groupId)
        if(active!=null) {
            val stored=baselineV1.certificate(control.groupId)
            if(stored==null || !MessageDigest.isEqual(active.certificateDigest,certificate.digest) ||
                !NetworkCodec.encode(stored).contentEquals(NetworkCodec.encode(certificate)) ||
                !GroupAuthorityBaselineV1.verifyCertificate(certificate,
                    certificate.proposal.state))
                throw ApiFailure(409,"group_invalid")
            return
        }
        val (current,localId)=baselineState(control.groupId)
        if(current.members.single {it.memberId==current.coordinatorId}.deviceId!=sender ||
            !GroupAuthorityBaselineV1.verifyCertificate(certificate,current))
            throw ApiFailure(409,"group_invalid")
        val own=baselineV1.own(control.groupId)
        if(own==null || own.proposalId!=certificate.proposal.proposalId ||
            !MessageDigest.isEqual(own.proposalDigest,
                GroupAuthorityBaselineV1.proposalDigest(certificate.proposal)))
            throw ApiFailure(503,"group_baseline_evidence_pending")
        verifyBaselinePeers(current,localId)
        records.transaction {
            val (latest,id)=baselineState(control.groupId)
            require(id==localId && MessageDigest.isEqual(GroupStatements.digest(latest),
                certificate.proposal.stateDigest))
            baselineV1.activate(latest,certificate,localId)
        }
    }
    private suspend fun flushBaselinesV1() {
        for(groupId in baselineV1.pendingGroups()) {
            val pending=baselineV1.pending(groupId) ?: continue
            val proposal=pending.proposal
            val current=runCatching {baselineState(groupId).first}.getOrNull() ?: continue
            if(!GroupAuthorityBaselineV1.verifyProposal(proposal,current)) {
                baselineV1.clearPending(groupId);baselineV1.clearOwn(groupId);continue
            }
            val localId=localMember(groupId) ?: continue
            if(localId!=current.coordinatorId) continue
            if(pending.approvals.size==current.members.size) {
                val certificate=GroupAuthorityBaselineV1.certificate(proposal,pending.approvals)
                if(!GroupAuthorityBaselineV1.verifyCertificate(certificate,current))
                    throw ApiFailure(409,"group_invalid")
                verifyBaselinePeers(current,localId)
                records.transaction {
                    val (latest,id)=baselineState(groupId)
                    require(id==localId && MessageDigest.isEqual(GroupStatements.digest(latest),
                        proposal.stateDigest))
                    baselineV1.activate(latest,certificate,localId)
                }
            } else for(member in current.members.filter {it.memberId!=localId &&
                pending.approvals.none {approval -> approval.approverId==it.memberId}}) {
                if(baselineV1.queued(groupId,proposal.proposalId,"proposal",member.deviceId)) continue
                try {send(member.deviceId,GroupControl(kind=GroupControlKind.BASELINE_V1_PROPOSAL,
                    groupId=groupId,baselineProposalV1=proposal)) {id ->
                    baselineV1.markQueued(groupId,proposal.proposalId,"proposal",member.deviceId,id)
                }} catch(e:CancellationException) {throw e}
                catch(_:ApiFailure) { /* Persisted attempt waits for this exact peer. */ }
            }
        }
        // Durable certificate fan-out survives coordinator restart after local activation.
        for(key in records.transaction {records.keys("app/group/baseline-v1/active/").take(64)}) {
            val groupId=key.removePrefix("app/group/baseline-v1/active/")
            val certificate=baselineV1.certificate(groupId) ?: continue
            val current=runCatching {baselineState(groupId).first}.getOrNull() ?: continue
            val localId=localMember(groupId) ?: continue
            if(localId!=current.coordinatorId ||
                !MessageDigest.isEqual(GroupStatements.digest(current),certificate.proposal.stateDigest))
                continue
            for(member in current.members.filter {it.memberId!=localId}) {
                if(baselineV1.queued(groupId,certificate.proposal.proposalId,"certificate",member.deviceId))
                    continue
                try {send(member.deviceId,GroupControl(kind=GroupControlKind.BASELINE_V1_CERTIFICATE,
                    groupId=groupId,baselineCertificateV1=certificate)) {id ->
                    baselineV1.markQueued(groupId,certificate.proposal.proposalId,"certificate",
                        member.deviceId,id)
                }} catch(e:CancellationException) {throw e}
                catch(_:ApiFailure) { /* Retry exact certificate after reconnect. */ }
            }
        }
        // Signed approvals are durable before transport enqueue.
        for(key in records.transaction {records.keys("app/group/baseline-v1/own/").take(64)}) {
            val groupId=key.removePrefix("app/group/baseline-v1/own/")
            if(baselineV1.active(groupId)!=null) continue
            val own=baselineV1.own(groupId) ?: continue
            val current=runCatching {baselineState(groupId).first}.getOrNull() ?: continue
            if(current.revision!=own.stateRevision ||
                !MessageDigest.isEqual(GroupStatements.digest(current),own.approval.stateDigest)) {
                baselineV1.clearOwn(groupId);continue
            }
            val localId=localMember(groupId) ?: continue
            if(localId==current.coordinatorId) continue
            val coordinator=current.members.single {it.memberId==current.coordinatorId}.deviceId
            if(baselineV1.queued(groupId,own.proposalId,"approval",coordinator)) continue
            try {send(coordinator,GroupControl(kind=GroupControlKind.BASELINE_V1_APPROVAL,
                groupId=groupId,baselineApprovalV1=own.approval)) {id ->
                baselineV1.markQueued(groupId,own.proposalId,"approval",coordinator,id)
            }} catch(e:CancellationException) {throw e}
            catch(_:ApiFailure) { /* Keep signed local proof for retry. */ }
        }
    }
    /** A finished outbox entry proves only server submission, not peer application. */
    private fun canRetryControl(outboxId:String?):Boolean {
        if(outboxId==null) return true
        val state=try {outbox.get(outboxId).state}
            catch(e:ApiFailure) {if(e.status==404) return true else throw e}
        return state in setOf(OutboxState.SERVER_ACCEPTED,OutboxState.FAILED,
            OutboxState.SUBMISSION_EXPIRED)
    }
    /** A valid legacy resync request advertises A5 only in ignored authenticated frame padding. */
    private suspend fun sendBaselineReadiness(current:GroupState,member:GroupMember,
        retryFinished:Boolean=false) {
        val groupId=current.groupId
        val sentKey="app/group/baseline-v1/ready-sent/$groupId/${member.deviceId}"
        val retryKey="app/group/baseline-v1/reply-intent/$groupId/${member.deviceId}"
        val prior=records.transaction {records.read(sentKey)?.decodeToString()}
        if(prior!=null && (!retryFinished || !canRetryControl(prior))) {
            records.transaction {records.remove(retryKey)}
            return
        }
        try {send(member.deviceId,GroupControl(kind=GroupControlKind.RESYNC_REQUEST,
            groupId=groupId,fromRevision=current.revision,
            fromDigest=GroupStatements.digest(current))) {id ->
            records.write(sentKey,id.toByteArray())
            records.remove(retryKey)
        }} catch(e:CancellationException) {throw e}
        catch(_:ApiFailure) {records.transaction {
            require(records.read(retryKey)!=null ||
                records.keys("app/group/baseline-v1/reply-intent/").size<256)
            records.write(retryKey,GroupStatements.digest(current))
        }}
    }
    /** Repeats only the exact outstanding member check or signed proposal. No approval is waived. */
    suspend fun retryGroupManagementSetup(groupId:String)=textMutex.withLock {
        val (current,localId)=baselineState(groupId)
        if(current.coordinatorId!=localId || baselineV1.active(groupId)!=null)
            throw ApiFailure(409,"group_baseline_unavailable")
        val pending=baselineV1.pending(groupId)
        if(pending!=null) {
            if(!GroupAuthorityBaselineV1.verifyProposal(pending.proposal,current))
                throw ApiFailure(409,"group_baseline_unavailable")
            for(member in current.members.filter {it.memberId!=localId &&
                pending.approvals.none {approval -> approval.approverId==it.memberId}}) {
                val prior=baselineV1.queuedOutbox(groupId,pending.proposal.proposalId,
                    "proposal",member.deviceId)
                if(canRetryControl(prior)) send(member.deviceId,GroupControl(
                    kind=GroupControlKind.BASELINE_V1_PROPOSAL,groupId=groupId,
                    baselineProposalV1=pending.proposal)) {id ->
                    baselineV1.markQueued(groupId,pending.proposal.proposalId,"proposal",member.deviceId,id)
                }
            }
            return@withLock
        }
        val intent=records.transaction {records.read("app/group/baseline-v1/start-intent/$groupId")}
            ?: throw ApiFailure(409,"group_baseline_unavailable")
        if(!MessageDigest.isEqual(intent,GroupStatements.digest(current)))
            throw ApiFailure(409,"group_baseline_unavailable")
        val unknown=current.members.filter {it.memberId!=localId &&
            !repository.baselineV1Peer(it.deviceId)}
        if(unknown.isEmpty()) {
            records.transaction {records.remove("app/group/baseline-v1/start-intent/$groupId")}
            startAuthorityBaseline(groupId)
        } else unknown.forEach {sendBaselineReadiness(current,it,retryFinished=true)}
    }
    private suspend fun flushBaselineReadiness() {
        for(key in records.transaction {records.keys("app/group/baseline-v1/reply-intent/").take(16)}) {
            val parts=key.removePrefix("app/group/baseline-v1/reply-intent/").split('/')
            if(parts.size!=2) continue
            val (groupId,deviceId)=parts
            val expected=records.transaction {records.read(key)} ?: continue
            val current=runCatching {baselineState(groupId).first}.getOrNull() ?: continue
            val member=current.members.singleOrNull {it.deviceId==deviceId} ?: continue
            if(!MessageDigest.isEqual(expected,GroupStatements.digest(current))) {
                records.transaction {records.remove(key)};continue
            }
            sendBaselineReadiness(current,member)
        }
        for(key in records.transaction {records.keys("app/group/baseline-v1/start-intent/").take(64)}) {
            val groupId=key.removePrefix("app/group/baseline-v1/start-intent/")
            val expected=records.transaction {records.read(key)} ?: continue
            val (current,localId)=runCatching {baselineState(groupId)}.getOrNull() ?: continue
            if(current.coordinatorId!=localId ||
                !MessageDigest.isEqual(GroupStatements.digest(current),expected) ||
                baselineV1.active(groupId)!=null || baselineV1.pending(groupId)!=null) {
                records.transaction {records.remove(key)};continue
            }
            val unknown=current.members.filter {it.memberId!=localId &&
                !repository.baselineV1Peer(it.deviceId)}
            if(unknown.isEmpty()) {
                records.transaction {records.remove(key)}
                startAuthorityBaseline(groupId)
            } else unknown.forEach {sendBaselineReadiness(current,it)}
        }
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
        if(allAdmissionV2Peers(group,targetDeviceId,owner.memberId)) {
            beginAdmissionV2(control) {
                check(records.keys("app/group/member/").size<64)
                records.write(memberKey(group.groupId),owner.memberId.toByteArray())
                check(GroupLedger(records,trusted,owner.memberId).acceptGenesis(genesis)==GroupApply.ACCEPTED)
            }
            return group.groupId
        }
        send(targetDeviceId,control) {
            check(records.keys("app/group/member/").size<64 && records.keys("app/group/outgoing/").size<128)
            records.write(memberKey(group.groupId),owner.memberId.toByteArray())
            check(GroupLedger(records,trusted,owner.memberId).acceptGenesis(genesis)==GroupApply.ACCEPTED)
            records.write(outgoingKey(inviteId),NetworkCodec.encode(Outgoing(targetDeviceId,control)))
        }
        return group.groupId
    }

    /** Invites a directly accepted, group-capable peer to an already active local group. */
    suspend fun invite(groupId:String,targetDeviceId:String):String = textMutex.withLock {
        inviteInternal(groupId,targetDeviceId)
    }
    private suspend fun inviteInternal(groupId:String,targetDeviceId:String,
        requestedId:String?=null,onCommitted:()->Unit={}):String {
        if(targetDeviceId==network.ownDevice() || !repository.isActiveContact(targetDeviceId) ||
            !repository.groupPeer(targetDeviceId))
            throw ApiFailure(409,"group_peer_unavailable")
        val localId=localMember(groupId) ?: throw ApiFailure(404,"group_unavailable")
        val state=GroupLedger(records,GroupTrustedPeer {false},localId).state(groupId)
            ?: throw ApiFailure(404,"group_unavailable")
        val governedHead=activeGovernanceHead(groupId)
        if(governedHead!=null && state.coordinatorId==localId)
            retireCompletedGovernanceEntry(groupId,state,localId)
        if(governedHead==null && governanceV1.pending(groupId)!=null)
            throw ApiFailure(409,"group_governance_unavailable")
        if(state.members.size>=GroupStatements.MAX_MEMBERS || state.members.any {it.deviceId==targetDeviceId} ||
            state.lifecycle!=GroupLifecycle.ACTIVE ||
            (governedHead!=null && !noOutgoingInvitation(groupId)) ||
            GroupLedger(records,GroupTrustedPeer {false},localId).isForked(groupId))
            throw ApiFailure(409,"group_unavailable")
        val actor=state.members.singleOrNull {it.memberId==localId}
            ?: throw ApiFailure(409,"group_unavailable")
        if(actor.role !in setOf(GroupRole.OWNER,GroupRole.ADMIN))
            throw ApiFailure(409,"group_policy_denied")
        if(governedHead!=null && (governedHead.sequence>=GroupGovernanceJournalV1.MAX_ENTRIES ||
            governanceJournal.marker(groupId)!=null ||
            (state.coordinatorId==localId && (governanceV1.outstanding(groupId)!=null ||
                governanceV1.policyOutstanding(groupId)!=null)) ||
            governedChanges.own(groupId)!=null || governedChanges.prepared(groupId)!=null ||
            governedChanges.outgoingTransfer(groupId)!=null))
            throw ApiFailure(409,"group_governance_entry_pending")
        if(baselineV1.active(groupId)!=null &&
            !allAdmissionV2Peers(state,targetDeviceId,localId))
            throw ApiFailure(409,"group_admission_evidence_required")
        if(governedHead!=null && (!allAdmissionV2Peers(state,targetDeviceId,localId) ||
            !repository.governanceV1Peer(targetDeviceId) ||
            !allGovernancePeers(state,localId)))
            throw ApiFailure(409,"group_governance_capability_pending")
        if(state.coordinatorId!=localId) {
            if(requestedId!=null) throw ApiFailure(409,"group_policy_denied")
            val unsigned=GroupInviteDelegationV1(groupId=groupId,requestId=GroupIds.create(),
                actorId=localId,targetDeviceId=targetDeviceId,
                stateDigest=GroupStatements.digest(state),
                governanceHeadDigest=governedHead?.headDigest)
            val request=unsigned.copy(actorSignature=network.signGroupStatement(
                GroupInviteDelegationRulesV1.statement(unsigned)))
            send(governanceCoordinator(state),GroupControl(
                kind=GroupControlKind.GOVERNANCE_INVITE_DELEGATION_V1,
                groupId=groupId,inviteDelegationV1=request))
            return request.requestId
        }
        val binding=authority.resolveTrustedGroupAuthority(targetDeviceId)
        if(state.members.any {it.accountId==binding.accountId}) throw ApiFailure(409,"group_unavailable")
        val target=GroupMember(GroupIds.create(),binding.accountId,binding.deviceId,binding.authPublicKey,
            binding.identityDigest,GroupRole.MEMBER,state.epoch+1)
        val trusted=trusted(state.members+target,groupId)
        val inviteId=requestedId ?: GroupIds.create()
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
        if(!coSign && allAdmissionV2Peers(state,targetDeviceId,localId)) {
            beginAdmissionV2(control.copy(kind=GroupControlKind.INVITE)) {onCommitted()}
            return inviteId
        }
        send(recipient,control) {
            check(if(governedHead==null) governanceV1.pending(groupId)==null &&
                activeGovernanceHead(groupId)==null else
                activeGovernanceHead(groupId)?.let {
                    GovernanceFoundationStore(records).matches(governedHead,it)
                }==true)
            check(records.keys("app/group/outgoing/").size<128)
            onCommitted()
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
    fun groupInfo(groupId:String,invitationPending:Boolean=false):GroupInfo? {
        val localId=localMember(groupId) ?: return null
        val ledger=GroupLedger(records,GroupTrustedPeer {false},localId)
        val current=ledger.state(groupId) ?: return null
        val localStatus=ledger.status(groupId) ?: return null
        val barrier=ledger.governanceBarrier(groupId)
        val head=ledger.governanceHead(groupId)
        val management=when {
            localStatus==GroupLocalStatus.FORKED -> GroupManagementStatus.FORKED
            barrier!=null && ledger.governanceJournalStatus(groupId)!=GovernanceJournalStatus.COMPLETE ->
                GroupManagementStatus.LEGACY_INCOMPLETE
            barrier!=null && governanceJournal.marker(groupId)!=null -> GroupManagementStatus.NEEDS_RESYNC
            barrier!=null && (governanceV1.ready(groupId)!=null ||
                governedAdmission.join(groupId)!=null) -> GroupManagementStatus.READY
            barrier!=null || governanceV1.pending(groupId)!=null -> GroupManagementStatus.ACTIVATING
            baselineV1.pending(groupId)!=null || records.transaction {
                records.read("app/group/baseline-v1/start-intent/$groupId")!=null
            } -> GroupManagementStatus.SETTING_UP_BASELINE
            baselineV1.active(groupId)!=null -> GroupManagementStatus.BASELINE_READY
            else -> GroupManagementStatus.NOT_CONFIGURED
        }
        val policy=ledger.governancePolicy(groupId) ?: GroupGovernancePolicyV1()
        val members=current.members.map {member -> GroupInfoMember(member.memberId,
            member.deviceId,member.role,member.memberId in policy.restrictedMemberIds,
            member.memberId==localId,member.memberId==current.coordinatorId) }
        val membershipPending=governanceV1.outstanding(groupId)?.let {record ->
            localId==current.coordinatorId &&
                record.applied.map {it.memberId}.sorted()!=current.members.map {it.memberId}.sorted()
        }==true
        val policyPending=governanceV1.policyOutstanding(groupId)?.let {record ->
            localId==current.coordinatorId &&
                record.applied.map {it.memberId}.sorted()!=current.members.map {it.memberId}.sorted()
        }==true
        val pending=localStatus==GroupLocalStatus.ACTIVE &&
            (membershipPending || policyPending || governedChanges.own(groupId)!=null ||
                governedChanges.prepared(groupId)!=null || governedChanges.outgoingTransfer(groupId)!=null)
        return GroupInfo(groupId,current.members.firstOrNull {it.memberId==localId}?.role,
            localStatus,management,members,policy.postingMode,pending,
            head?.sequence?.let {it>=GroupGovernanceJournalV1.MAX_ENTRIES}==true,
            invitationPending || (barrier!=null && !noOutgoingInvitation(groupId)),
            barrier==null || allTextV2Peers(current,localId),
            records.transaction {records.read("app/group/baseline-v1/start-intent/$groupId")!=null},
            allModerationPeers(current,localId))
    }
    suspend fun beginGroupManagementSetup(groupId:String) {
        val info=groupInfo(groupId) ?: throw ApiFailure(409,"group_unavailable")
        if(!info.active || info.pending || !noOutgoingInvitation(groupId) ||
            info.localRole !in setOf(GroupRole.OWNER,GroupRole.ADMIN) ||
            info.members.firstOrNull {it.isLocal}?.isCoordinator!=true)
            throw ApiFailure(409,"group_policy_denied")
        if(info.managementStatus !in setOf(GroupManagementStatus.NOT_CONFIGURED,
                GroupManagementStatus.BASELINE_READY))
            throw ApiFailure(409,"group_governance_entry_pending")
        if(baselineV1.active(groupId)==null) beginAuthorityBaseline(groupId)
        else beginGovernanceActivation(groupId)
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
        val localId=localMember(id)
        val restriction=if(status!=GroupLocalStatus.ACTIVE || localId==null) null else {
            val ledger=GroupLedger(records,GroupTrustedPeer {false},localId)
            val barrier=ledger.governanceBarrier(id)
            if(barrier==null) null else {
                val policy=ledger.governancePolicy(id)
                when {
                    policy==null || ledger.governanceJournalStatus(id)!=GovernanceJournalStatus.COMPLETE ->
                        "Group management is unavailable. Create a new group to continue."
                    governanceV1.ready(id)==null && governedAdmission.join(id)==null ->
                        "Group is updating. Messages are paused."
                    governanceJournal.marker(id)!=null -> "Group is syncing. Messages are paused."
                    !allTextV2Peers(current,localId) -> "Waiting for updated group members."
                    !GroupGovernancePolicyRulesV1.canSend(policy,current,localId) ->
                        if(policy.postingMode==GroupPostingModeV1.ADMINS_ONLY &&
                            current.members.single {it.memberId==localId}.role==GroupRole.MEMBER)
                            "Only admins can send messages" else "You can't send messages in this group"
                    else -> null
                }
            }
        }
        Conversation(id,status,current.members.size,current.members.associate {it.memberId to it.deviceId},
            chats.messages(id),pending,restriction,groupInfo(id,pending))
        }
    }
    fun conversation(id:String):Conversation?=conversations().firstOrNull {it.groupId==id}
    /** Persist one logical message and its immutable recipient set before any pairwise enqueue. */
    suspend fun sendText(groupId:String,text:String):String = textMutex.withLock {
        val local=localMember(groupId) ?: throw ApiFailure(409,"group_unavailable")
        val before=GroupLedger(records,GroupTrustedPeer {false},local).state(groupId)
            ?: throw ApiFailure(409,"group_unavailable")
        val ownMember=before.members.singleOrNull {it.memberId==local}
            ?: throw ApiFailure(409,"group_unavailable")
        trusted(listOf(ownMember))
        records.transaction {
            val localId=localMember(groupId) ?: throw ApiFailure(409,"group_unavailable")
            val ledger=GroupLedger(records,GroupTrustedPeer {false},localId)
            val current=ledger.state(groupId) ?: throw ApiFailure(409,"group_unavailable")
            if(ledger.status(groupId)!=GroupLocalStatus.ACTIVE || current.lifecycle!=GroupLifecycle.ACTIVE ||
                current.members.size<2 || current.members.none {it.memberId==localId})
                throw ApiFailure(409,"group_unavailable")
            val logicalId=GroupIds.create()
            val message=GroupChatMessage(groupId,logicalId,current.epoch,localId,true,text,
                chats.nextOrder(),current.members.filter {it.memberId!=localId}.map {GroupRecipient(it.deviceId)})
            val barrier=ledger.governanceBarrier(groupId)
            if(barrier==null) {
                GroupTextCodec.validate(GroupText(groupId=groupId,epoch=current.epoch,
                    senderMemberId=localId,logicalId=logicalId,text=text))
                chats.create(message)
            } else {
                val head=ledger.governanceHead(groupId)
                    ?: throw ApiFailure(409,"group_unavailable")
                val policy=ledger.governancePolicy(groupId)
                    ?: throw ApiFailure(409,"group_unavailable")
                if(ledger.governanceJournalStatus(groupId)!=GovernanceJournalStatus.COMPLETE ||
                    (governanceV1.ready(groupId)==null && governedAdmission.join(groupId)==null) ||
                    governanceJournal.marker(groupId)!=null ||
                    !allTextV2Peers(current,localId) ||
                    !GroupGovernancePolicyRulesV1.canSend(policy,current,localId))
                    throw ApiFailure(409,"group_send_unavailable")
                val binding=GroupTextV2Binding(head.activationDigest,head.sequence,
                    head.headDigest,GroupGovernancePolicyRulesV1.digest(head.activationDigest,policy))
                GroupTextV2Codec.validate(GroupTextV2(groupId=groupId,epoch=current.epoch,
                    senderMemberId=localId,logicalId=logicalId,
                    governanceActivationDigest=head.activationDigest,
                    governanceSequence=head.sequence,governanceHeadDigest=head.headDigest,
                    policyDigest=binding.policyDigest,text=text))
                chats.createV2(message,binding)
            }
            logicalId
        }
    }
    /** Public management actions use the signed, single-head governance path only. */
    suspend fun removeGroupMemberGoverned(groupId:String,targetId:String)=textMutex.withLock {
        proposeGovernedGroupChange(groupId,GroupAction.REMOVE,targetId)
    }
    suspend fun promoteGroupMemberGoverned(groupId:String,targetId:String)=textMutex.withLock {
        proposeGovernedGroupChange(groupId,GroupAction.PROMOTE,targetId)
    }
    suspend fun demoteGroupAdminGoverned(groupId:String,targetId:String)=textMutex.withLock {
        proposeGovernedGroupChange(groupId,GroupAction.DEMOTE,targetId)
    }
    suspend fun leaveGroupGoverned(groupId:String)=textMutex.withLock {
        val own=localMember(groupId) ?: throw ApiFailure(409,"group_unavailable")
        proposeGovernedGroupChange(groupId,GroupAction.LEAVE,own)
    }
    suspend fun dissolveGroupGoverned(groupId:String)=textMutex.withLock {
        proposeGovernedGroupChange(groupId,GroupAction.DISSOLVE,null)
    }
    fun pendingOwnershipRequests():List<GroupOwnershipRequestV1> =
        governedChanges.incomingTransfers().filter {request ->
            val localId=localMember(request.groupId) ?: return@filter false
            val ledger=GroupLedger(records,GroupTrustedPeer {false},localId)
            val current=ledger.state(request.groupId) ?: return@filter false
            val head=ledger.governanceHead(request.groupId) ?: return@filter false
            current.members.any {it.memberId==localId} &&
                request.transition.change.targetId==localId &&
                request.parentSequence==head.sequence &&
                MessageDigest.isEqual(request.parentHeadDigest,head.headDigest)
        }
    suspend fun requestOwnershipTransferGoverned(groupId:String,targetId:String)=textMutex.withLock {
        val (current,localId)=baselineState(groupId)
        val head=activeGovernanceHead(groupId) ?: throw ApiFailure(409,"group_governance_unavailable")
        reconcileGovernedChange(groupId,current,head)
        if(localId==current.coordinatorId) retireCompletedGovernanceEntry(groupId,current,localId)
        if(current.ownerId!=localId || targetId==localId ||
            !allTextV2Peers(current,localId) ||
            (localId==current.coordinatorId &&
                (governanceV1.outstanding(groupId)!=null ||
                    governanceV1.policyOutstanding(groupId)!=null)) ||
            governedChanges.outgoingTransfer(groupId)!=null ||
            governedChanges.own(groupId)!=null || governedChanges.prepared(groupId)!=null ||
            governanceJournal.marker(groupId)!=null ||
            head.sequence>=GroupGovernanceJournalV1.MAX_ENTRIES)
            throw ApiFailure(409,"group_governance_entry_pending")
        val target=current.members.singleOrNull {it.memberId==targetId}
            ?: throw ApiFailure(409,"group_invalid")
        val change=GroupChange(GroupIds.create(),GroupAction.TRANSFER_OWNER,localId,targetId=targetId)
        val next=GroupRules.derive(current,change,emptySet(),GroupTrustedPeer {true})
        val transition=GroupTransition(change,next,
            network.signGroupStatement(GroupStatements.actor(current,change,next)),byteArrayOf())
        val request=GroupOwnershipRequestV1(groupId=groupId,
            activationDigest=head.activationDigest,parentSequence=head.sequence,
            parentHeadDigest=head.headDigest,parentStateDigest=GroupStatements.digest(current),
            transition=transition)
        governedChanges.saveOutgoingTransfer(request)
        sendGovernedChangeOnce(groupId,"transfer-request",target.deviceId,
            GroupControl(kind=GroupControlKind.GOVERNANCE_TRANSFER_REQUEST_V1,
                groupId=groupId,ownershipRequestV1=request))
    }
    suspend fun decideOwnershipTransfer(groupId:String,accept:Boolean)=textMutex.withLock {
        val request=governedChanges.incomingTransfer(groupId)
            ?: throw ApiFailure(409,"group_transfer_unavailable")
        val (current,localId)=baselineState(groupId)
        val head=activeGovernanceHead(groupId) ?: throw ApiFailure(409,"group_transfer_unavailable")
        if(request.transition.change.targetId!=localId ||
            request.parentSequence!=head.sequence ||
            !MessageDigest.isEqual(request.parentHeadDigest,head.headDigest) ||
            !MessageDigest.isEqual(request.parentStateDigest,GroupStatements.digest(current)))
            throw ApiFailure(409,"group_transfer_stale")
        val signature=if(accept) network.signGroupStatement(GroupStatements.transferAcceptance(
            current,request.transition.change,request.transition.next)) else byteArrayOf()
        val owner=current.members.single {it.memberId==current.ownerId}
        send(owner.deviceId,GroupControl(kind=GroupControlKind.GOVERNANCE_TRANSFER_DECISION_V1,
            groupId=groupId,ownershipDecisionV1=GroupOwnershipDecisionV1(
                request=request,accepted=accept,targetSignature=signature)))
        governedChanges.clear(groupId,"transfer-in")
    }
    private fun verifyOwnershipRequest(request:GroupOwnershipRequestV1,current:GroupState,
        head:GovernanceHeadFoundationV1):Boolean=runCatching {
        val change=request.transition.change
        require(request.version==1 && request.groupId==current.groupId &&
            MessageDigest.isEqual(request.activationDigest,head.activationDigest) &&
            request.parentSequence==head.sequence &&
            MessageDigest.isEqual(request.parentHeadDigest,head.headDigest) &&
            MessageDigest.isEqual(request.parentStateDigest,GroupStatements.digest(current)) &&
            change.action==GroupAction.TRANSFER_OWNER && change.actorId==current.ownerId &&
            request.transition.coordinatorSignature.isEmpty() &&
            request.transition.targetSignature.isEmpty())
        val next=GroupRules.derive(current,change,emptySet(),GroupTrustedPeer {true})
        require(MessageDigest.isEqual(GroupStatements.digest(next),
            GroupStatements.digest(request.transition.next)))
        val owner=current.members.single {it.memberId==current.ownerId}
        require(GroupStatements.verify(owner.authPublicKey,
            GroupStatements.actor(current,change,next),request.transition.actorSignature))
        true
    }.getOrDefault(false)
    private fun receiveOwnershipRequest(sender:String,control:GroupControl) {
        val request=control.ownershipRequestV1 ?: throw ApiFailure(400,"group_invalid")
        val (current,localId)=baselineState(control.groupId)
        val head=activeGovernanceHead(control.groupId) ?: throw ApiFailure(409,"group_invalid")
        reconcileGovernedChange(control.groupId,current,head)
        if(current.members.single {it.memberId==current.ownerId}.deviceId!=sender ||
            request.transition.change.targetId!=localId ||
            !verifyOwnershipRequest(request,current,head)) throw ApiFailure(409,"group_invalid")
        val prior=governedChanges.incomingTransfer(control.groupId)
        if(prior!=null && !NetworkCodec.encode(prior).contentEquals(NetworkCodec.encode(request)))
            throw ApiFailure(409,"group_governance_entry_pending")
        governedChanges.saveIncomingTransfer(request)
    }
    private suspend fun receiveOwnershipDecision(sender:String,control:GroupControl) {
        val decision=control.ownershipDecisionV1 ?: throw ApiFailure(400,"group_invalid")
        val request=governedChanges.outgoingTransfer(control.groupId)
            ?: throw ApiFailure(409,"group_transfer_unavailable")
        val (current,localId)=baselineState(control.groupId)
        val head=activeGovernanceHead(control.groupId) ?: throw ApiFailure(409,"group_invalid")
        val target=current.members.singleOrNull {it.memberId==request.transition.change.targetId}
            ?: throw ApiFailure(409,"group_invalid")
        if(current.ownerId!=localId || target.deviceId!=sender ||
            !NetworkCodec.encode(request).contentEquals(NetworkCodec.encode(decision.request)) ||
            !verifyOwnershipRequest(request,current,head)) {
            governedChanges.clear(control.groupId,"transfer-out")
            throw ApiFailure(409,"group_transfer_stale")
        }
        if(!decision.accepted) {
            governedChanges.clear(control.groupId,"transfer-out")
            governedChanges.clearSent(control.groupId)
            return
        }
        if(!GroupStatements.verify(target.authPublicKey,GroupStatements.transferAcceptance(
                current,request.transition.change,request.transition.next),decision.targetSignature))
            throw ApiFailure(409,"group_invalid")
        governedChanges.clear(control.groupId,"transfer-out")
        governedChanges.clearSent(control.groupId)
        proposeGovernedGroupChange(control.groupId,GroupAction.TRANSFER_OWNER,target.memberId,
            decision.targetSignature,request.transition.change)
    }
    private suspend fun proposeGovernedGroupChange(groupId:String,action:GroupAction,
        targetId:String?,targetSignature:ByteArray=byteArrayOf(),
        consentedChange:GroupChange?=null) {
        require(action in setOf(GroupAction.REMOVE,GroupAction.PROMOTE,GroupAction.DEMOTE,
            GroupAction.LEAVE,GroupAction.TRANSFER_OWNER,GroupAction.DISSOLVE))
        val (current,localId)=baselineState(groupId)
        val ledger=GroupLedger(records,GroupTrustedPeer {false},localId)
        val head=activeGovernanceHead(groupId) ?: throw ApiFailure(409,"group_governance_unavailable")
        reconcileGovernedChange(groupId,current,head)
        if(!allTextV2Peers(current,localId) || governanceJournal.marker(groupId)!=null)
            throw ApiFailure(409,"group_governance_resync")
        if(localId==current.coordinatorId) retireCompletedGovernanceEntry(groupId,current,localId)
        if(head.sequence>=GroupGovernanceJournalV1.MAX_ENTRIES ||
            governedChanges.own(groupId)!=null || governedChanges.prepared(groupId)!=null ||
            governedChanges.outgoingTransfer(groupId)!=null ||
            (localId==current.coordinatorId &&
                (governanceV1.outstanding(groupId)!=null ||
                    governanceV1.policyOutstanding(groupId)!=null)))
            throw ApiFailure(409,"group_governance_entry_pending")
        val peers=trusted(current.members,groupId)
        val change=consentedChange ?: GroupChange(GroupIds.create(),action,localId,targetId=targetId)
        if(change.action!=action || change.actorId!=localId || change.targetId!=targetId)
            throw ApiFailure(409,"group_transfer_stale")
        val next=try {GroupRules.derive(current,change,emptySet(),peers)}
            catch(_:IllegalArgumentException) {throw ApiFailure(409,"group_policy_denied")}
        if(action==GroupAction.TRANSFER_OWNER && targetSignature.isEmpty())
            throw ApiFailure(409,"group_transfer_consent_required")
        val transition=GroupTransition(change,next,
            network.signGroupStatement(GroupStatements.actor(current,change,next)),
            byteArrayOf(),targetSignature)
        val proposal=GroupGovernedChangeProposalV1(groupId=groupId,
            activationDigest=head.activationDigest,parentSequence=head.sequence,
            parentHeadDigest=head.headDigest,parentStateDigest=GroupStatements.digest(current),
            transition=transition)
        governedChanges.saveOwn(proposal)
        if(localId==current.coordinatorId) {
            prepareGovernedChange(proposal,current,head,localId)
            completePreparedGovernedChange(groupId,localId)
        } else sendGovernedChangeOnce(groupId,"proposal",governanceCoordinator(current),GroupControl(
            kind=GroupControlKind.GOVERNANCE_CHANGE_PROPOSAL_V1,groupId=groupId,
            governedChangeProposalV1=proposal))
    }
    private fun prepareGovernedChange(proposal:GroupGovernedChangeProposalV1,
        current:GroupState,head:GovernanceHeadFoundationV1,localId:String) {
        val change=proposal.transition.change
        if(localId!=current.coordinatorId || !governedChanges.matchesHead(proposal,head,current) ||
            proposal.transition.coordinatorSignature.isNotEmpty() ||
            change.action !in setOf(GroupAction.REMOVE,GroupAction.PROMOTE,GroupAction.DEMOTE,
                GroupAction.LEAVE,GroupAction.TRANSFER_OWNER,GroupAction.DISSOLVE))
            throw ApiFailure(409,"group_invalid")
        val actor=current.members.singleOrNull {it.memberId==change.actorId}
            ?: throw ApiFailure(409,"group_invalid")
        val peers=GroupTrustedPeer {member ->current.members.any {it.memberId==member.memberId}}
        val next=runCatching {GroupRules.derive(current,change,emptySet(),peers)}.getOrNull()
            ?: throw ApiFailure(409,"group_policy_denied")
        if(!MessageDigest.isEqual(GroupStatements.digest(next),
                GroupStatements.digest(proposal.transition.next)) ||
            !GroupStatements.verify(actor.authPublicKey,
                GroupStatements.actor(current,change,next),proposal.transition.actorSignature))
            throw ApiFailure(409,"group_invalid")
        if(change.action==GroupAction.TRANSFER_OWNER) {
            val target=current.members.singleOrNull {it.memberId==change.targetId}
                ?: throw ApiFailure(409,"group_invalid")
            if(!GroupStatements.verify(target.authPublicKey,
                    GroupStatements.transferAcceptance(current,change,next),
                    proposal.transition.targetSignature)) throw ApiFailure(409,"group_invalid")
        } else if(proposal.transition.targetSignature.isNotEmpty()) throw ApiFailure(409,"group_invalid")
        val signed=proposal.transition.copy(coordinatorSignature=network.signGroupStatement(
            GroupStatements.coordinator(current,change,next)))
        val unsigned=GroupGovernanceEntryV1(groupId=current.groupId,
            activationDigest=head.activationDigest,sequence=head.sequence+1,
            previousHeadDigest=head.headDigest,eventId=change.eventId,
            preRevision=current.revision,preDigest=GroupStatements.digest(current),
            actorId=change.actorId,action=change.action,
            transitionDigest=DeviceAuth.digest(NetworkCodec.encode(signed)),
            postRevision=next.revision,postDigest=GroupStatements.digest(next),
            transition=signed,actorSignature=byteArrayOf(),coordinatorSignature=byteArrayOf())
        governedChanges.savePrepared(unsigned)
    }
    private suspend fun completePreparedGovernedChange(groupId:String,localId:String) {
        val prepared=governedChanges.prepared(groupId) ?: throw ApiFailure(409,"group_invalid")
        if(prepared.actorId!=localId) {
            val actor=state(groupId)?.members?.singleOrNull {it.memberId==prepared.actorId}
                ?: throw ApiFailure(409,"group_invalid")
            sendGovernedChangeOnce(groupId,"sign-request",actor.deviceId,
                GroupControl(kind=GroupControlKind.GOVERNANCE_CHANGE_SIGN_REQUEST_V1,
                    groupId=groupId,governanceEntryV1=prepared))
            return
        }
        val actorSigned=prepared.copy(actorSignature=network.signGroupStatement(
            GroupGovernanceV1.actorStatement(prepared)))
        acceptSignedGovernedChange(actorSigned)
    }
    private suspend fun acceptSignedGovernedChange(actorSigned:GroupGovernanceEntryV1) {
        val groupId=actorSigned.groupId
        val prepared=governedChanges.prepared(groupId) ?: throw ApiFailure(409,"group_invalid")
        if(!NetworkCodec.encode(prepared).contentEquals(NetworkCodec.encode(actorSigned.copy(
                actorSignature=byteArrayOf(),coordinatorSignature=byteArrayOf()))) ||
            actorSigned.coordinatorSignature.isNotEmpty()) throw ApiFailure(409,"group_invalid")
        val current=state(groupId) ?: throw ApiFailure(409,"group_invalid")
        val head=activeGovernanceHead(groupId) ?: throw ApiFailure(409,"group_invalid")
        val actor=current.members.singleOrNull {it.memberId==actorSigned.actorId}
            ?: throw ApiFailure(409,"group_invalid")
        if(actorSigned.sequence!=head.sequence+1 ||
            !MessageDigest.isEqual(actorSigned.previousHeadDigest,head.headDigest) ||
            !GroupStatements.verify(actor.authPublicKey,
                GroupGovernanceV1.actorStatement(actorSigned),actorSigned.actorSignature))
            throw ApiFailure(409,"group_invalid")
        val entry=actorSigned.copy(coordinatorSignature=network.signGroupStatement(
            GroupGovernanceV1.coordinatorStatement(actorSigned)))
        receiveGovernanceEntry(network.ownDevice(),GroupControl(
            kind=GroupControlKind.GOVERNANCE_ENTRY,groupId=groupId,governanceEntryV1=entry))
        records.transaction {
            governedChanges.clear(groupId,"prepared")
            governedChanges.clear(groupId,"own")
            governedChanges.clearSent(groupId)
        }
    }
    private suspend fun sendGovernedChangeOnce(groupId:String,phase:String,target:String,control:GroupControl) {
        if(governedChanges.sent(groupId,phase)) return
        send(target,control) {id ->governedChanges.markSent(groupId,phase,id)}
    }
    private fun reconcileGovernedChange(groupId:String,current:GroupState,
        head:GovernanceHeadFoundationV1) {
        val own=governedChanges.own(groupId)
        val prepared=governedChanges.prepared(groupId)
        val outgoing=governedChanges.outgoingTransfer(groupId)
        val incoming=governedChanges.incomingTransfer(groupId)
        val staleOwn=own!=null && !governedChanges.matchesHead(own,head,current)
        val stalePrepared=prepared!=null && (prepared.sequence!=head.sequence+1 ||
            !MessageDigest.isEqual(prepared.previousHeadDigest,head.headDigest) ||
            !MessageDigest.isEqual(prepared.preDigest,GroupStatements.digest(current)))
        val staleOutgoing=outgoing!=null && (outgoing.parentSequence!=head.sequence ||
            !MessageDigest.isEqual(outgoing.parentHeadDigest,head.headDigest) ||
            !MessageDigest.isEqual(outgoing.parentStateDigest,GroupStatements.digest(current)))
        val staleIncoming=incoming!=null && (incoming.parentSequence!=head.sequence ||
            !MessageDigest.isEqual(incoming.parentHeadDigest,head.headDigest) ||
            !MessageDigest.isEqual(incoming.parentStateDigest,GroupStatements.digest(current)))
        if(staleOwn || stalePrepared || staleOutgoing || staleIncoming) records.transaction {
            if(staleOwn) governedChanges.clear(groupId,"own")
            if(stalePrepared) governedChanges.clear(groupId,"prepared")
            if(staleOutgoing) governedChanges.clear(groupId,"transfer-out")
            if(staleIncoming) governedChanges.clear(groupId,"transfer-in")
            governedChanges.clearSent(groupId)
        }
    }
    private suspend fun flushGovernedChanges() {
        val groups=(governedChanges.ownGroups()+governedChanges.preparedGroups()+
            governedChanges.outgoingTransferGroups()).distinct().take(64)
        for(groupId in groups) {
            try {
                val current=state(groupId) ?: continue
                val localId=localMember(groupId) ?: continue
                val head=activeGovernanceHead(groupId) ?: continue
                reconcileGovernedChange(groupId,current,head)
                val prepared=governedChanges.prepared(groupId)
                if(prepared!=null && localId==current.coordinatorId) {
                    completePreparedGovernedChange(groupId,localId)
                    continue
                }
                val own=governedChanges.own(groupId)
                if(own!=null && prepared==null) {
                    if(localId==current.coordinatorId) {
                        prepareGovernedChange(own,current,head,localId)
                        completePreparedGovernedChange(groupId,localId)
                    } else sendGovernedChangeOnce(groupId,"proposal",governanceCoordinator(current),
                        GroupControl(kind=GroupControlKind.GOVERNANCE_CHANGE_PROPOSAL_V1,
                            groupId=groupId,governedChangeProposalV1=own))
                }
                val transfer=governedChanges.outgoingTransfer(groupId)
                if(transfer!=null && localId==current.ownerId) {
                    val target=current.members.singleOrNull {
                        it.memberId==transfer.transition.change.targetId
                    } ?: continue
                    sendGovernedChangeOnce(groupId,"transfer-request",target.deviceId,
                        GroupControl(kind=GroupControlKind.GOVERNANCE_TRANSFER_REQUEST_V1,
                            groupId=groupId,ownershipRequestV1=transfer))
                }
            } catch(e:CancellationException) {throw e}
            catch(_:ApiFailure) { /* Protected intent stays for a later sync. */ }
        }
    }
    private suspend fun receiveGovernedChangeProposal(sender:String,control:GroupControl) {
        val proposal=control.governedChangeProposalV1 ?: throw ApiFailure(400,"group_invalid")
        val localId=localMember(control.groupId) ?: throw ApiFailure(409,"group_invalid")
        val current=state(control.groupId) ?: throw ApiFailure(409,"group_invalid")
        val head=activeGovernanceHead(control.groupId) ?: throw ApiFailure(409,"group_invalid")
        if(current.coordinatorId==localId)
            retireCompletedGovernanceEntry(control.groupId,current,localId)
        if(!allTextV2Peers(current,localId))
            throw ApiFailure(409,"group_governance_capability_pending")
        if(current.coordinatorId!=localId || current.members.singleOrNull {
                it.memberId==proposal.transition.change.actorId}?.deviceId!=sender ||
            governanceV1.outstanding(control.groupId)!=null ||
            governanceV1.policyOutstanding(control.groupId)!=null ||
            governedChanges.prepared(control.groupId)!=null ||
            governedChanges.own(control.groupId)!=null)
            throw ApiFailure(409,"group_governance_entry_pending")
        prepareGovernedChange(proposal,current,head,localId)
        completePreparedGovernedChange(control.groupId,localId)
    }
    private suspend fun receiveGovernedChangeSignRequest(sender:String,control:GroupControl) {
        val prepared=control.governanceEntryV1 ?: throw ApiFailure(400,"group_invalid")
        val own=governedChanges.own(control.groupId) ?: throw ApiFailure(409,"group_invalid")
        val localId=localMember(control.groupId) ?: throw ApiFailure(409,"group_invalid")
        val current=state(control.groupId) ?: throw ApiFailure(409,"group_invalid")
        val head=activeGovernanceHead(control.groupId) ?: throw ApiFailure(409,"group_invalid")
        if(localId!=prepared.actorId || sender!=governanceCoordinator(current) ||
            !governedChanges.matchesHead(own,head,current) ||
            prepared.coordinatorSignature.isNotEmpty() || prepared.actorSignature.isNotEmpty() ||
            prepared.sequence!=head.sequence+1 ||
            !MessageDigest.isEqual(prepared.previousHeadDigest,head.headDigest) ||
            !MessageDigest.isEqual(prepared.preDigest,GroupStatements.digest(current)) ||
            prepared.eventId!=own.transition.change.eventId ||
            !NetworkCodec.encode(prepared.transition.copy(coordinatorSignature=byteArrayOf()))
                .contentEquals(NetworkCodec.encode(own.transition)) ||
            !MessageDigest.isEqual(prepared.transitionDigest,
                DeviceAuth.digest(NetworkCodec.encode(prepared.transition))) ||
            !MessageDigest.isEqual(GroupStatements.digest(prepared.transition.next),
                GroupStatements.digest(own.transition.next)) ||
            !prepared.transition.actorSignature.contentEquals(own.transition.actorSignature) ||
            !prepared.transition.targetSignature.contentEquals(own.transition.targetSignature) ||
            !GroupStatements.verify(current.members.single {it.memberId==current.coordinatorId}.authPublicKey,
                GroupStatements.coordinator(current,own.transition.change,own.transition.next),
                prepared.transition.coordinatorSignature)) throw ApiFailure(409,"group_invalid")
        val actorSigned=prepared.copy(actorSignature=network.signGroupStatement(
            GroupGovernanceV1.actorStatement(prepared)))
        send(sender,GroupControl(kind=GroupControlKind.GOVERNANCE_CHANGE_SIGN_RESPONSE_V1,
            groupId=control.groupId,governanceEntryV1=actorSigned))
    }
    private suspend fun receiveGovernedChangeSignResponse(sender:String,control:GroupControl) {
        val entry=control.governanceEntryV1 ?: throw ApiFailure(400,"group_invalid")
        val current=state(control.groupId) ?: throw ApiFailure(409,"group_invalid")
        if(current.coordinatorId!=localMember(control.groupId) ||
            current.members.singleOrNull {it.memberId==entry.actorId}?.deviceId!=sender)
            throw ApiFailure(409,"group_invalid")
        acceptSignedGovernedChange(entry)
    }
    /** Internal membership foundation; never wire this raw path into product UI. */
    suspend fun removeMember(groupId:String,targetMemberId:String) = textMutex.withLock {
        val localId=localMember(groupId) ?: throw ApiFailure(404,"group_unavailable")
        val ledger=GroupLedger(records,GroupTrustedPeer {false},localId)
        val current=ledger.state(groupId) ?: throw ApiFailure(404,"group_unavailable")
        if(ledger.isForked(groupId) || current.coordinatorId!=localId ||
            current.members.none {it.memberId==targetMemberId}) throw ApiFailure(409,"group_unavailable")
        val trusted=trusted(current.members,groupId)
        val change=GroupChange(GroupIds.create(),GroupAction.REMOVE,localId,targetId=targetMemberId)
        val next=try {GroupRules.derive(current,change,emptySet(),trusted)}
            catch(_:IllegalArgumentException) {throw ApiFailure(409,"group_unavailable")}
        val event=GroupTransition(change,next,network.signGroupStatement(GroupStatements.actor(current,change,next)),
            network.signGroupStatement(GroupStatements.coordinator(current,change,next)))
        val result=GroupLedger(records,trusted,localId).apply(groupId,event) {
            val removedDevice=current.members.single {it.memberId==targetMemberId}.deviceId
            chats.markRemoved(groupId,removedDevice)
            chats.cancelStale(groupId,next.epoch)
            currentAuthority.removeDeparted(next)
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
        val trusted=trusted(state.members+invite.target,state.groupId)
        if(!GroupStatements.verifyOffer(invite,state,trusted)) throw ApiFailure(409,"group_invite_unavailable")
        offer.certificateV2?.let {certificate ->
            if(!AdmissionV2.verifyCertificate(certificate,state) ||
                certificate.proposal.inviteId!=invite.inviteId ||
                !MessageDigest.isEqual(certificate.proposal.candidateDigest,
                    GroupStatements.digestMember(invite.target))) throw ApiFailure(409,"group_invite_unavailable")
        }
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
            val direct=repository.isActiveContact(pending.senderDeviceId)
            if(!direct && (!pending.groupScoped || !pending.control.kind.blockSafeMaintenance())) {
                repository.finishGroupControl(id);continue
            }
            try {
                if(!direct) {
                    val member=state(pending.control.groupId)?.members?.singleOrNull {
                        it.deviceId==pending.senderDeviceId
                    } ?: throw ApiFailure(409,"group_unavailable")
                    if(!authority.matchesCurrentGroupMember(pending.control.groupId,member))
                        throw ApiFailure(409,"group_unavailable")
                }
                if(pending.baselineV1Advertised &&
                    (!repository.baselineV1Peer(pending.senderDeviceId) ||
                        pending.control.kind==GroupControlKind.RESYNC_REQUEST)) {
                    val current=state(pending.control.groupId)
                    val peer=current?.members?.singleOrNull {
                        it.deviceId==pending.senderDeviceId
                    }
                    if(current!=null && peer!=null &&
                        authority.matchesCurrentGroupMember(current.groupId,peer)) {
                        repository.baselineV1Peer(pending.senderDeviceId,true)
                        if(pending.control.kind==GroupControlKind.RESYNC_REQUEST &&
                            peer.memberId==current.coordinatorId &&
                            localMember(current.groupId)!=current.coordinatorId)
                            sendBaselineReadiness(current,peer,retryFinished=true)
                    }
                }
                if(pending.governanceV1Advertised) {
                    val current=state(pending.control.groupId)
                    val peer=current?.members?.singleOrNull {
                        it.deviceId==pending.senderDeviceId
                    }
                    if(current!=null && peer!=null &&
                        authority.matchesCurrentGroupMember(current.groupId,peer)) {
                        repository.governanceV1Peer(pending.senderDeviceId,true)
                        if(pending.control.kind==GroupControlKind.RESYNC_REQUEST &&
                            pending.control.fromRevision==current.revision &&
                            pending.control.fromDigest?.let {MessageDigest.isEqual(it,
                                GroupStatements.digest(current))}==true)
                            send(pending.senderDeviceId,GroupControl(
                                kind=GroupControlKind.GOVERNANCE_CAPABILITY_ECHO,
                                groupId=current.groupId))
                    }
                }
                if(pending.governanceTextV2Advertised) {
                    val current=state(pending.control.groupId)
                    val peer=current?.members?.singleOrNull {
                        it.deviceId==pending.senderDeviceId
                    }
                    if(current!=null && peer!=null &&
                        authority.matchesCurrentGroupMember(current.groupId,peer))
                        repository.governanceTextV2Peer(pending.senderDeviceId,true)
                }
                if(pending.moderationAdvertised) {
                    val current=state(pending.control.groupId)
                    val peer=current?.members?.singleOrNull {
                        it.deviceId==pending.senderDeviceId
                    }
                    if(current!=null && peer!=null &&
                        authority.matchesCurrentGroupMember(current.groupId,peer))
                        repository.groupModerationPeer(pending.senderDeviceId,true)
                }
                when(pending.control.kind) {
                    GroupControlKind.INVITE -> receiveInvite(pending.senderDeviceId,pending.control)
                    GroupControlKind.ACCEPT -> receiveAcceptance(pending.senderDeviceId,pending.control)
                    GroupControlKind.INVITE_EXPIRED -> receiveInviteExpired(pending.senderDeviceId,pending.control)
                    GroupControlKind.STATE_UPDATE -> receiveState(pending.senderDeviceId,pending.control)
                    GroupControlKind.RESYNC_REQUEST -> receiveResyncRequest(pending.senderDeviceId,pending.control)
                    GroupControlKind.RESYNC_RESPONSE -> receiveResyncResponse(pending.senderDeviceId,pending.control)
                    GroupControlKind.ADMISSION_REQUEST -> receiveAdmissionRequest(pending.senderDeviceId,pending.control)
                    GroupControlKind.ADMISSION_RESPONSE -> receiveAdmissionResponse(pending.senderDeviceId,pending.control)
                    GroupControlKind.ADMISSION_V2_PROPOSAL -> receiveProposalV2(pending.senderDeviceId,pending.control)
                    GroupControlKind.ADMISSION_V2_APPROVAL -> receiveApprovalV2(pending.senderDeviceId,pending.control)
                    GroupControlKind.ADMISSION_V2_EVIDENCE_REQUEST -> receiveEvidenceRequestV2(pending.senderDeviceId,pending.control)
                    GroupControlKind.ADMISSION_V2_EVIDENCE_RESPONSE -> receiveEvidenceResponseV2(pending.senderDeviceId,pending.control)
                    GroupControlKind.BASELINE_V1_PROPOSAL -> receiveBaselineProposalV1(pending.senderDeviceId,pending.control)
                    GroupControlKind.BASELINE_V1_APPROVAL -> receiveBaselineApprovalV1(pending.senderDeviceId,pending.control)
                    GroupControlKind.BASELINE_V1_CERTIFICATE -> receiveBaselineCertificateV1(pending.senderDeviceId,pending.control)
                    GroupControlKind.GOVERNANCE_OWNER_SIGN_REQUEST -> receiveGovernanceOwnerRequest(pending.senderDeviceId,pending.control)
                    GroupControlKind.GOVERNANCE_OWNER_SIGN_RESPONSE -> receiveGovernanceOwnerResponse(pending.senderDeviceId,pending.control)
                    GroupControlKind.GOVERNANCE_ACTIVATION_PROPOSAL -> receiveGovernanceProposal(pending.senderDeviceId,pending.control)
                    GroupControlKind.GOVERNANCE_ACTIVATION_ACK -> receiveGovernanceAck(pending.senderDeviceId,pending.control)
                    GroupControlKind.GOVERNANCE_ACTIVATION_COMMIT -> receiveGovernanceCommit(pending.senderDeviceId,pending.control)
                    GroupControlKind.GOVERNANCE_ACTIVATION_INSTALLED_ACK -> receiveGovernanceInstalled(pending.senderDeviceId,pending.control)
                    GroupControlKind.GOVERNANCE_ACTIVATION_READY -> receiveGovernanceReady(pending.senderDeviceId,pending.control)
                    GroupControlKind.GOVERNANCE_ENTRY -> receiveGovernanceEntry(pending.senderDeviceId,pending.control)
                    GroupControlKind.GOVERNANCE_ENTRY_APPLIED_ACK -> receiveGovernanceEntryAck(pending.senderDeviceId,pending.control)
                    GroupControlKind.GOVERNANCE_RESYNC_REQUEST_V1 -> receiveGovernanceResyncRequest(pending.senderDeviceId,pending.control)
                    GroupControlKind.GOVERNANCE_RESYNC_RESPONSE_V1 -> receiveGovernanceResyncResponse(pending.senderDeviceId,pending.control)
                    GroupControlKind.GOVERNANCE_RESYNC_RESPONSE_V2 -> receiveGovernanceResyncResponseV2(pending.senderDeviceId,pending.control)
                    GroupControlKind.GOVERNANCE_POLICY_ENTRY_V1 -> receivePolicyEntry(pending.senderDeviceId,pending.control)
                    GroupControlKind.GOVERNANCE_POLICY_ENTRY_ACK_V1 -> receivePolicyAck(pending.senderDeviceId,pending.control)
                    GroupControlKind.GOVERNANCE_POLICY_PROPOSAL_V1 -> receivePolicyProposal(pending.senderDeviceId,pending.control)
                    GroupControlKind.GOVERNANCE_CHECKPOINT_SIGN_REQUEST_V1 -> receiveCheckpointSignRequest(pending.senderDeviceId,pending.control)
                    GroupControlKind.GOVERNANCE_CHECKPOINT_SIGN_RESPONSE_V1 -> receiveCheckpointSignResponse(pending.senderDeviceId,pending.control)
                    GroupControlKind.GOVERNANCE_BOOTSTRAP_V1 -> receiveGovernanceBootstrap(pending.senderDeviceId,pending.control)
                    GroupControlKind.GOVERNANCE_CHANGE_PROPOSAL_V1 -> receiveGovernedChangeProposal(pending.senderDeviceId,pending.control)
                    GroupControlKind.GOVERNANCE_CHANGE_SIGN_REQUEST_V1 -> receiveGovernedChangeSignRequest(pending.senderDeviceId,pending.control)
                    GroupControlKind.GOVERNANCE_CHANGE_SIGN_RESPONSE_V1 -> receiveGovernedChangeSignResponse(pending.senderDeviceId,pending.control)
                    GroupControlKind.GOVERNANCE_TRANSFER_REQUEST_V1 -> receiveOwnershipRequest(pending.senderDeviceId,pending.control)
                    GroupControlKind.GOVERNANCE_TRANSFER_DECISION_V1 -> receiveOwnershipDecision(pending.senderDeviceId,pending.control)
                    GroupControlKind.GOVERNANCE_INVITE_DELEGATION_V1 -> receiveInviteDelegation(pending.senderDeviceId,pending.control)
                    GroupControlKind.GOVERNANCE_CAPABILITY_ECHO -> Unit
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
        flushAdmissionsV2()
        flushGovernedAdmission()
        retryOwnApprovalsV2()
        flushBaselineReadiness()
        flushBaselinesV1()
        flushGovernanceV1()
        flushGovernedChanges()
        // Existing A6.3 groups may have no recent direct messages from updated peers.
        // Exchange an authenticated capability echo before any governed text is enabled.
        for(groupId in chats.groups()) {
            val localId=localMember(groupId) ?: continue
            val ledger=GroupLedger(records,GroupTrustedPeer {false},localId)
            if(ledger.governanceBarrier(groupId)==null) continue
            val current=ledger.state(groupId) ?: continue
            if(!allTextV2Peers(current,localId) || !allModerationPeers(current,localId))
                probeMissingGovernancePeers(current,localId)
        }
        processSystemOutbox()
        retryMarkedResync()
        retryMarkedGovernanceResync()
        processPendingTexts()
        flushTexts()
    }
    private suspend fun receiveProposalV2(sender:String,control:GroupControl) {
        val proposal=control.proposalV2 ?: throw ApiFailure(400,"group_invalid")
        val localId=localMember(proposal.groupId) ?: throw ApiFailure(409,"group_invalid")
        val ledger=GroupLedger(records,GroupTrustedPeer {false},localId)
        val current=ledger.state(proposal.groupId) ?: throw ApiFailure(409,"group_invalid")
        if(ledger.governanceBarrier(proposal.groupId)!=null)
            activeGovernanceHead(proposal.groupId)
        if(ledger.isForked(proposal.groupId) || ledger.status(proposal.groupId)!=GroupLocalStatus.ACTIVE ||
            current.members.singleOrNull {it.memberId==current.coordinatorId}?.deviceId!=sender ||
            !AdmissionV2.verifyProposal(proposal,current) ||
            !repository.admissionV2Peer(sender)) throw ApiFailure(409,"group_invalid")
        for((group,invite) in admissionV2.ownKeys().filter {it.first==proposal.groupId && it.second!=proposal.inviteId}) {
            val previous=admissionV2.own(group,invite) ?: continue
            if(MessageDigest.isEqual(previous.parentDigest,proposal.parentDigest))
                throw ApiFailure(409,"group_admission_pending")
            admissionV2.removeOwn(group,invite)
        }
        val existing=admissionV2.own(proposal.groupId,proposal.inviteId)
        if(existing!=null && !MessageDigest.isEqual(existing.proposalDigest,AdmissionV2.proposalDigest(proposal)))
            throw ApiFailure(409,"group_invalid")
        val ownApproval=if(existing!=null) existing.approval else {
            if(!authority.verifyAdmissionCandidate(proposal.candidate)) throw ApiFailure(409,"group_binding_unavailable")
            val unsigned=AdmissionV2.approval(proposal,localId,byteArrayOf())
            val approval=unsigned.copy(signature=network.signGroupStatement(
                AdmissionV2.approvalStatement(unsigned)))
            if(!AdmissionV2.verifyApproval(approval,proposal)) throw ApiFailure(409,"group_invalid")
            admissionV2.saveOwn(proposal.groupId,OwnAdmissionApprovalV2(
                AdmissionV2.proposalDigest(proposal),proposal.parentDigest,current.revision,
                proposal.inviteId,proposal.candidateDigest,localId,approval=approval))
            approval
        }
        if(!admissionV2.queued(proposal.groupId,proposal.inviteId,sender))
            send(sender,GroupControl(kind=GroupControlKind.ADMISSION_V2_APPROVAL,
                groupId=proposal.groupId,inviteId=proposal.inviteId,approvalV2=ownApproval)) {id ->
                admissionV2.markQueued(proposal.groupId,proposal.inviteId,sender,id)
            }
    }

    private suspend fun receiveApprovalV2(sender:String,control:GroupControl) {
        val approval=control.approvalV2 ?: throw ApiFailure(400,"group_invalid")
        val pending=admissionV2.pending(control.groupId) ?: throw ApiFailure(409,"group_invalid")
        val proposal=pending.proposal
        val localId=localMember(control.groupId) ?: throw ApiFailure(409,"group_invalid")
        val current=state(control.groupId) ?: throw ApiFailure(409,"group_invalid")
        val reservation=governedAdmission.reservation(control.groupId)
        val head=activeGovernanceHead(control.groupId)
        if(head!=null &&
            (reservation==null || !GovernanceFoundationStore(records).matches(
                reservation.head,head)))
            throw ApiFailure(409,"group_invalid")
        if(current.coordinatorId!=localId ||
            !AdmissionV2.verifyProposal(proposal,current) ||
            current.members.singleOrNull {it.memberId==approval.approverId}?.deviceId!=sender ||
            !AdmissionV2.verifyApproval(approval,proposal)) throw ApiFailure(409,"group_invalid")
        val prior=pending.approvals.singleOrNull {it.approverId==approval.approverId}
        if(prior!=null && !NetworkCodec.encode(prior).contentEquals(NetworkCodec.encode(approval)))
            throw ApiFailure(409,"group_invalid")
        if(prior==null) admissionV2.savePending(control.groupId,
            pending.copy(approvals=pending.approvals+approval))
        flushAdmissionsV2()
    }
    private suspend fun requestEvidenceV2(parent:GroupState,eventId:String) {
        val recipient=parent.members.single {it.memberId==parent.coordinatorId}.deviceId
        if(admissionV2.queued(parent.groupId,eventId,recipient)) return
        send(recipient,GroupControl(kind=GroupControlKind.ADMISSION_V2_EVIDENCE_REQUEST,
            groupId=parent.groupId,evidenceEventId=eventId)) {id ->
            admissionV2.markQueued(parent.groupId,eventId,recipient,id)
        }
    }
    private suspend fun receiveEvidenceRequestV2(sender:String,control:GroupControl) {
        val eventId=control.evidenceEventId ?: throw ApiFailure(400,"group_invalid")
        val localId=localMember(control.groupId) ?: throw ApiFailure(409,"group_invalid")
        val ledger=GroupLedger(records,GroupTrustedPeer {false},localId)
        val current=ledger.state(control.groupId) ?: throw ApiFailure(409,"group_invalid")
        if(ledger.status(control.groupId)!=GroupLocalStatus.ACTIVE ||
            current.members.none {it.deviceId==sender})
            throw ApiFailure(409,"group_invalid")
        val cert=admissionV2.evidence(control.groupId,eventId) ?: throw ApiFailure(409,"group_invalid")
        val anchor=ledger.anchorRevision(control.groupId) ?: throw ApiFailure(409,"group_invalid")
        val event=ledger.transitionsAfter(control.groupId,anchor)?.singleOrNull {
            it.change.eventId==eventId && it.change.action==GroupAction.ADD
        } ?: throw ApiFailure(409,"group_invalid")
        if(event.change.invite?.inviteId!=cert.proposal.inviteId ||
            !MessageDigest.isEqual(GroupStatements.digestMember(event.change.added!!),
                cert.proposal.candidateDigest)) throw ApiFailure(409,"group_invalid")
        send(sender,GroupControl(kind=GroupControlKind.ADMISSION_V2_EVIDENCE_RESPONSE,
            groupId=control.groupId,inviteId=cert.proposal.inviteId,
            evidenceEventId=eventId,certificateV2=cert))
    }
    private suspend fun receiveEvidenceResponseV2(sender:String,control:GroupControl) {
        val cert=control.certificateV2 ?: throw ApiFailure(400,"group_invalid")
        val eventId=control.evidenceEventId ?: throw ApiFailure(400,"group_invalid")
        val localId=localMember(control.groupId) ?: throw ApiFailure(409,"group_invalid")
        val prior=state(control.groupId) ?: throw ApiFailure(409,"group_invalid")
        if(prior.members.none {it.deviceId==sender} ||
            cert.proposal.eventId!=eventId) throw ApiFailure(409,"group_invalid")
        var matchedParent:GroupState?=null
        for((_,pending) in repository.pendingGroupControls()) {
            val value=pending.control
            if(value.groupId!=control.groupId) continue
            var parent=prior
            val events=when(value.kind) {
                GroupControlKind.STATE_UPDATE -> listOfNotNull(value.transition)
                GroupControlKind.RESYNC_RESPONSE -> value.chain
                GroupControlKind.GOVERNANCE_ENTRY ->
                    listOfNotNull(value.governanceEntryV1?.transition)
                GroupControlKind.GOVERNANCE_RESYNC_RESPONSE_V1 ->
                    value.governanceResyncResponseV1?.entries?.map {it.transition} ?: emptyList()
                else -> emptyList()
            }
            for(event in events) {
                if(event.change.eventId==eventId && event.change.action==GroupAction.ADD &&
                    event.change.invite?.inviteId==cert.proposal.inviteId &&
                    event.next.revision==parent.revision+1 &&
                    MessageDigest.isEqual(GroupStatements.digestMember(event.change.added!!),
                        cert.proposal.candidateDigest)) matchedParent=parent
                parent=event.next
            }
        }
        if(matchedParent==null || !AdmissionV2.verifyCertificate(cert,matchedParent))
            throw ApiFailure(409,"group_invalid")
        if(matchedParent.members.any {it.memberId==localId} &&
            !admissionV2.matchesOwnApproval(matchedParent,cert,localId))
            throw ApiFailure(503,"group_admission_evidence_pending")
        records.transaction {
            admissionV2.saveEvidence(control.groupId,eventId,cert)
            admissionV2.clearQueued(control.groupId,eventId)
        }
    }
    private suspend fun processPendingTexts() {
        for((envelopeId,pending) in chats.pendingV2()) {
            val value=pending.text
            val localId=localMember(value.groupId)
            if(localId==null) {chats.discardPendingV2(envelopeId);continue}
            val ledger=GroupLedger(records,GroupTrustedPeer {false},localId)
            val current=ledger.state(value.groupId)
            val head=ledger.governanceHead(value.groupId)
            val policy=ledger.governancePolicy(value.groupId)
            if(current==null || ledger.status(value.groupId)!=GroupLocalStatus.ACTIVE) {
                chats.discardPendingV2(envelopeId);continue
            }
            if(ledger.governanceBarrier(value.groupId)==null || head==null || policy==null ||
                ledger.governanceJournalStatus(value.groupId)!=GovernanceJournalStatus.COMPLETE ||
                (governanceV1.ready(value.groupId)==null && governedAdmission.join(value.groupId)==null))
                continue // The frame remains encrypted and hidden until signed authority exists.
            if(!MessageDigest.isEqual(value.governanceActivationDigest,head.activationDigest)) {
                chats.discardPendingV2(envelopeId);continue
            }
            if(value.governanceSequence>head.sequence) {
                runCatching {requestGovernanceResync(value.groupId)}
                continue
            }
            if(governanceJournal.marker(value.groupId)!=null) continue
            if(value.governanceSequence!=head.sequence || value.epoch!=current.epoch ||
                !MessageDigest.isEqual(value.governanceHeadDigest,head.headDigest) ||
                !MessageDigest.isEqual(value.policyDigest,
                    GroupGovernancePolicyRulesV1.digest(head.activationDigest,policy)) ||
                !GroupGovernancePolicyRulesV1.canSend(policy,current,value.senderMemberId)) {
                chats.discardPendingV2(envelopeId);continue
            }
            val sender=current.members.singleOrNull {it.memberId==value.senderMemberId}
            if(sender==null || sender.deviceId!=pending.senderDeviceId ||
                !repository.isActiveContact(sender.deviceId)) {
                chats.discardPendingV2(envelopeId);continue
            }
            try {trusted(listOf(sender))}
            catch(e:CancellationException) {throw e}
            catch(_:ApiFailure) {continue}
            records.transaction {
                val fresh=ledger.state(value.groupId)
                val freshHead=ledger.governanceHead(value.groupId)
                val freshPolicy=ledger.governancePolicy(value.groupId)
                if(fresh!=null && freshHead!=null && freshPolicy!=null &&
                    ledger.status(value.groupId)==GroupLocalStatus.ACTIVE &&
                    governanceJournal.marker(value.groupId)==null &&
                    value.governanceSequence==freshHead.sequence && value.epoch==fresh.epoch &&
                    MessageDigest.isEqual(value.governanceActivationDigest,freshHead.activationDigest) &&
                    MessageDigest.isEqual(value.governanceHeadDigest,freshHead.headDigest) &&
                    MessageDigest.isEqual(value.policyDigest,
                        GroupGovernancePolicyRulesV1.digest(freshHead.activationDigest,freshPolicy)) &&
                    fresh.members.singleOrNull {it.memberId==value.senderMemberId}?.deviceId==pending.senderDeviceId &&
                    repository.isActiveContact(pending.senderDeviceId) &&
                    GroupGovernancePolicyRulesV1.canSend(freshPolicy,fresh,value.senderMemberId))
                    chats.acceptV2(envelopeId,pending)
            }
        }
        for((envelopeId,pending) in chats.pending()) {
            val value=pending.text
            val localId=localMember(value.groupId)
            if(localId==null) {chats.discardPending(envelopeId);continue}
            val ledger=GroupLedger(records,GroupTrustedPeer {false},localId)
            val current=ledger.state(value.groupId)
            if(current==null || ledger.status(value.groupId)!=GroupLocalStatus.ACTIVE ||
                ledger.governanceBarrier(value.groupId)!=null) {
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
            val barrier=ledger.governanceBarrier(groupId)
            val head=if(barrier!=null) ledger.governanceHead(groupId) else null
            val policy=if(barrier!=null) ledger.governancePolicy(groupId) else null
            if(barrier!=null) {
                chats.cancelStaleHead(groupId,head,policy)
                if(head==null || policy==null ||
                    ledger.governanceJournalStatus(groupId)!=GovernanceJournalStatus.COMPLETE ||
                    (governanceV1.ready(groupId)==null && governedAdmission.join(groupId)==null) ||
                    governanceJournal.marker(groupId)!=null || !allTextV2Peers(current,localId)) continue
            }
            for(message in chats.messages(groupId).filter {it.outgoing}) {
                if(budget<=0) break
                // Epoch changes never reinterpret queued old-epoch text as current content.
                if(message.epoch!=current.epoch) {chats.cancelStale(groupId,current.epoch);continue}
                val binding=chats.binding(groupId,message.logicalId)
                if(barrier!=null && (head==null || policy==null ||
                    binding?.matches(head,policy)!=true)) continue
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
                        val payload=if(binding==null) ConversationPayload.encodeGroupText(GroupText(
                            groupId=groupId,epoch=message.epoch,senderMemberId=localId,
                            logicalId=message.logicalId,text=message.text)) else
                            ConversationPayload.encodeGroupTextV2(GroupTextV2(groupId=groupId,
                                epoch=message.epoch,senderMemberId=localId,
                                logicalId=message.logicalId,
                                governanceActivationDigest=binding.activationDigest,
                                governanceSequence=binding.sequence,
                                governanceHeadDigest=binding.headDigest,
                                policyDigest=binding.policyDigest,text=message.text))
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
    /** The coordinator executes an Owner/Admin invitation only after verifying its signed exact-state request. */
    private suspend fun receiveInviteDelegation(sender:String,control:GroupControl) {
        val request=control.inviteDelegationV1 ?: throw ApiFailure(400,"group_invalid")
        if(!GroupInviteDelegationRulesV1.valid(request)) throw ApiFailure(400,"group_invalid")
        val (current,localId)=baselineState(control.groupId)
        val actor=current.members.singleOrNull {it.memberId==request.actorId && it.deviceId==sender}
            ?: throw ApiFailure(409,"group_invalid")
        val head=activeGovernanceHead(control.groupId)
        if(localId!=current.coordinatorId || actor.role !in setOf(GroupRole.OWNER,GroupRole.ADMIN) ||
            !MessageDigest.isEqual(request.stateDigest,GroupStatements.digest(current)) ||
            (head?.headDigest==null)!=(request.governanceHeadDigest==null) ||
            (head!=null && !MessageDigest.isEqual(head.headDigest,request.governanceHeadDigest)) ||
            !GroupStatements.verify(actor.authPublicKey,
                GroupInviteDelegationRulesV1.statement(request),request.actorSignature))
            throw ApiFailure(409,"group_invalid")
        if(inviteDelegations.processed(control.groupId,request.requestId)) return
        inviteInternal(control.groupId,request.targetDeviceId,request.requestId) {
            inviteDelegations.markProcessed(control.groupId,request.requestId)
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
        val governed=activeGovernanceHead(state.groupId)
        if((governed==null && governanceV1.pending(state.groupId)!=null) ||
            (governed!=null && !repository.governanceV1Peer(invite.target.deviceId)))
            throw ApiFailure(409,"group_governance_unavailable")
        if(localId!=current.ownerId || current.coordinatorId==localId ||
            current.members.single {it.memberId==current.coordinatorId}.deviceId!=sender ||
            ledger.isForked(state.groupId) || current.members.size>=GroupStatements.MAX_MEMBERS ||
            !GroupStatements.digest(current).contentEquals(GroupStatements.digest(state)) ||
            proof.ownerSignature.isNotEmpty() ||
            !repository.isActiveContact(invite.target.deviceId) || !repository.groupPeer(invite.target.deviceId))
            throw ApiFailure(409,"group_invalid")
        val trusted=trusted(state.members+invite.target,state.groupId)
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
        val governed=activeGovernanceHead(state.groupId)
        if(governed==null && governanceV1.pending(state.groupId)!=null)
            throw ApiFailure(409,"group_governance_unavailable")
        if(current.coordinatorId!=localId || ledger.isForked(state.groupId) ||
            current.members.single {it.memberId==current.ownerId}.deviceId!=sender ||
            !GroupStatements.digest(current).contentEquals(GroupStatements.digest(state)) ||
            !NetworkCodec.encode(original.invite!!).contentEquals(NetworkCodec.encode(invite)) ||
            !NetworkCodec.encode(original.admission!!).contentEquals(
                NetworkCodec.encode(proof.copy(ownerSignature=byteArrayOf()))) ||
            pending.target!=invite.target.deviceId) throw ApiFailure(409,"group_invalid")
        val trusted=trusted(state.members+invite.target,state.groupId)
        if(!GroupStatements.verifyAdmission(proof,trusted) || !GroupStatements.verifyOffer(invite,state,trusted))
            throw ApiFailure(409,"group_invalid")
        val finished=control.copy(kind=GroupControlKind.INVITE)
        if(allAdmissionV2Peers(state,pending.target,localId)) {
            beginAdmissionV2(finished) {records.remove(admissionKey(id))}
            return
        }
        if(governed!=null) throw ApiFailure(409,"group_admission_evidence_required")
        send(pending.target,finished) {
            check(governanceV1.pending(state.groupId)==null &&
                ledger.governanceBarrier(state.groupId)==null)
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
        val trusted=trusted(state.members+invite.target,state.groupId)
        if(!GroupStatements.verifyAdmission(proof,trusted) || !GroupStatements.verifyOffer(invite,state,trusted))
            throw ApiFailure(409,"group_invalid")
        control.certificateV2?.let {certificate ->
            if(!AdmissionV2.verifyCertificate(certificate,state) ||
                certificate.proposal.inviteId!=invite.inviteId ||
                !MessageDigest.isEqual(certificate.proposal.candidateDigest,
                    GroupStatements.digestMember(invite.target))) throw ApiFailure(409,"group_invalid")
        }
        val governedBinding=control.governedAdmissionBindingV1
        if(governedBinding!=null) {
            val certificate=control.certificateV2 ?: throw ApiFailure(409,"group_invalid")
            if(!repository.governanceV1Peer(sender) ||
                !GroupGovernedAdmissionV1.verifyBinding(governedBinding,state,certificate) ||
                GroupLedger(records,trusted,own.memberId).state(state.groupId)!=null)
                throw ApiFailure(409,"group_invalid")
        }
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
            if(governedBinding==null) {
                if(GroupLedger(records,trusted,own.memberId).acceptAdmission(proof)!=GroupApply.ACCEPTED)
                    throw ApiFailure(409,"group_invalid")
                val admissionDigest=DeviceAuth.digest(NetworkCodec.encode(proof))
                state.members.forEach {member ->
                    currentAuthority.anchor(state,member,admissionDigest)
                    if(control.certificateV2!=null) historicalAuthority.anchorBaseline(proof,member)
                }
                control.certificateV2?.let {admissionV2.saveEvidence(state.groupId,it.proposal.eventId,it)}
            }
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
        val trusted=trusted(current.members+invite.target,control.groupId)
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
        val certificate=outgoing.offer.certificateV2
        if((baselineV1.active(control.groupId)!=null ||
                allAdmissionV2Peers(current,invite.target.deviceId,localId)) && certificate==null)
            throw ApiFailure(409,"group_admission_evidence_required")
        if(certificate!=null && (!AdmissionV2.verifyCertificate(certificate,current) ||
            certificate.proposal.inviteId!=invite.inviteId ||
            certificate.proposal.inviterId!=localId ||
            !admissionV2.matchesOwnApproval(current,certificate,localId) ||
            !MessageDigest.isEqual(certificate.proposal.candidateDigest,
                GroupStatements.digestMember(invite.target)))) throw ApiFailure(409,"group_invalid")
        val governedBinding=outgoing.offer.governedAdmissionBindingV1
        if(governedBinding!=null) {
            val cert=certificate ?: throw ApiFailure(409,"group_invalid")
            val head=activeGovernanceHead(control.groupId) ?: throw ApiFailure(409,"group_invalid")
            val barrier=GroupLedger(records,GroupTrustedPeer {false},localId)
                .governanceBarrier(control.groupId) ?: throw ApiFailure(409,"group_invalid")
            val proof=outgoing.offer.admission ?: throw ApiFailure(409,"group_invalid")
            if(!GroupGovernedAdmissionV1.verifyBinding(governedBinding,current,cert,head))
                throw ApiFailure(409,"group_invalid")
            val prior=governedAdmission.pending(control.groupId)
            if(prior!=null) {
                if(prior.binding.inviteId!=invite.inviteId ||
                    !NetworkCodec.encode(prior.invite).contentEquals(NetworkCodec.encode(invite)))
                    throw ApiFailure(409,"group_admission_pending")
                flushGovernedAdmission();return@withLock
            }
            val unsigned=GroupGovernedAdmissionV1.unsignedCheckpoint(governedBinding,
                proof,barrier,current,GroupIds.create())
            val coordinatorSigned=unsigned.copy(coordinatorSignature=network.signGroupStatement(
                GroupGovernedAdmissionV1.coordinatorStatement(unsigned)))
            val checkpoint=if(current.ownerId==localId) coordinatorSigned.copy(
                ownerSignature=network.signGroupStatement(
                    GroupGovernedAdmissionV1.ownerStatement(unsigned))) else coordinatorSigned
            val currentPolicy=GroupLedger(records,GroupTrustedPeer {false},localId)
                .governancePolicy(control.groupId) ?: throw ApiFailure(409,"group_invalid")
            val unsignedPolicy=GroupGovernancePolicyCheckpointRulesV1.unsigned(checkpoint,
                currentPolicy,chats.moderationFilter(control.groupId))
            val coordinatorPolicy=unsignedPolicy.copy(coordinatorSignature=network.signGroupStatement(
                GroupGovernancePolicyCheckpointRulesV1.coordinatorStatement(unsignedPolicy)))
            val policyProof=if(current.ownerId==localId) coordinatorPolicy.copy(
                ownerSignature=network.signGroupStatement(
                    GroupGovernancePolicyCheckpointRulesV1.ownerStatement(coordinatorPolicy)))
                else coordinatorPolicy
            if(!GroupGovernedAdmissionV1.verifyCheckpoint(checkpoint,current,proof,cert,
                    governedBinding,requireOwner=current.ownerId==localId) ||
                !GroupGovernancePolicyCheckpointRulesV1.verify(policyProof,checkpoint,current,
                    requireOwner=current.ownerId==localId))
                throw ApiFailure(409,"group_invalid")
            governedAdmission.savePending(GovernedAdmissionPendingV1(governedBinding,invite,
                proof,cert,checkpoint))
            policyCheckpoints.savePending(policyProof)
            flushGovernedAdmission();return@withLock
        }
        val change=GroupChange(certificate?.proposal?.eventId ?: GroupIds.create(),
            GroupAction.ADD,localId,added=invite.target,invite=invite)
        val next=GroupRules.derive(current,change,emptySet(),trusted)
        val event=GroupTransition(change,next,network.signGroupStatement(GroupStatements.actor(current,change,next)),
            network.signGroupStatement(GroupStatements.coordinator(current,change,next)))
        val result=GroupLedger(records,trusted,localId).apply(control.groupId,event) {
            currentAuthority.anchor(next,invite.target,DeviceAuth.digest(NetworkCodec.encode(event)))
            if(certificate!=null) {
                admissionV2.saveEvidence(control.groupId,event.change.eventId,certificate)
                historicalAuthority.record(current,event,certificate)
                admissionV2.clearPending(control.groupId)
                admissionV2.removeOwn(control.groupId,certificate.proposal.inviteId)
            }
            chats.cancelStale(control.groupId,next.epoch)
            records.write(outgoingKey(invite.inviteId),NetworkCodec.encode(outgoing.copy(used=true)))
            for(member in next.members.filter {it.memberId!=localId})
                records.write(fanoutKey(next.groupId,next.revision,member.deviceId),
                    GroupControlCodec.encode(GroupControl(kind=GroupControlKind.STATE_UPDATE,
                        groupId=next.groupId,transition=event,certificateV2=certificate)))
        }
        if(result!=GroupApply.ACCEPTED) throw ApiFailure(409,"group_invalid")
    }
    private suspend fun receiveState(sender:String,control:GroupControl) {
        val event=control.transition ?: throw ApiFailure(400,"group_invalid")
        val localId=localMember(control.groupId) ?: throw ApiFailure(409,"group_invalid")
        val local=GroupLedger(records,GroupTrustedPeer {false},localId)
        val prior=local.stateForLegacyReplay(control.groupId) ?: throw ApiFailure(409,"group_invalid")
        if(prior.members.none {it.memberId==prior.coordinatorId && it.deviceId==sender})
            throw ApiFailure(409,"group_invalid")
        val candidate=event.change.added
        val certificate=if(candidate!=null) control.certificateV2 ?:
            admissionV2.evidence(control.groupId,event.change.eventId) else null
        if(candidate!=null && certificate==null &&
            (baselineV1.active(control.groupId)!=null ||
                allAdmissionV2Peers(prior,candidate.deviceId,localId) ||
                event.change.invite?.inviteId?.let {admissionV2.own(control.groupId,it)!=null}==true)) {
            requestEvidenceV2(prior,event.change.eventId)
            throw ApiFailure(503,"group_admission_evidence_pending")
        }
        if(certificate!=null && (candidate==null ||
            !AdmissionV2.verifyCertificate(certificate,prior) ||
            certificate.proposal.eventId!=event.change.eventId ||
            certificate.proposal.inviteId!=event.change.invite?.inviteId ||
            !MessageDigest.isEqual(certificate.proposal.candidateDigest,
                GroupStatements.digestMember(candidate)))) throw ApiFailure(409,"group_invalid")
        if(certificate!=null && prior.members.any {it.memberId==localId} &&
            !admissionV2.matchesOwnApproval(prior,certificate,localId))
            throw ApiFailure(503,"group_admission_evidence_pending")
        val base=trusted(prior.members,control.groupId)
        val trusted=if(certificate==null) trusted(prior.members+listOfNotNull(candidate),control.groupId)
            else GroupTrustedPeer {member -> base.matches(member) ||
                (candidate!=null && member.memberId==candidate.memberId &&
                    MessageDigest.isEqual(GroupStatements.digestMember(member),
                        GroupStatements.digestMember(candidate))) }
        val result=GroupLedger(records,trusted,localId).apply(control.groupId,event) {
            event.change.added?.let {added ->
                currentAuthority.anchor(event.next,added,DeviceAuth.digest(NetworkCodec.encode(event)))
                if(certificate!=null) {
                    admissionV2.saveEvidence(control.groupId,event.change.eventId,certificate)
                    historicalAuthority.record(prior,event,certificate)
                    admissionV2.removeOwn(control.groupId,certificate.proposal.inviteId)
                }
            }
            currentAuthority.removeDeparted(event.next)
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
            val current=ledger.stateForLegacyReplay(groupId) ?: continue
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
        val prior=GroupLedger(records,GroupTrustedPeer {false},localId).stateForLegacyReplay(control.groupId)
            ?: throw ApiFailure(409,"group_invalid")
        if(prior.members.none {it.memberId==prior.coordinatorId && it.deviceId==sender})
            throw ApiFailure(409,"group_invalid")
        val certificates=HashMap<String,Pair<GroupState,AdmissionCertificateV2>>()
        var parent=prior
        for(event in control.chain) {
            if(event.change.action==GroupAction.ADD) {
                val added=event.change.added ?: throw ApiFailure(409,"group_invalid")
                val certificate=admissionV2.evidence(control.groupId,event.change.eventId)
                val baseline=baselineV1.active(control.groupId)
                if(certificate==null && ((baseline!=null &&
                    parent.revision>=baseline.stateRevision) ||
                    allAdmissionV2Peers(parent,added.deviceId,localId) ||
                    event.change.invite?.inviteId?.let {admissionV2.own(control.groupId,it)!=null}==true)) {
                    requestEvidenceV2(prior,event.change.eventId)
                    throw ApiFailure(503,"group_admission_evidence_pending")
                }
                if(certificate!=null) {
                    if(!AdmissionV2.verifyCertificate(certificate,parent) ||
                        certificate.proposal.eventId!=event.change.eventId ||
                        certificate.proposal.inviteId!=event.change.invite?.inviteId ||
                        !MessageDigest.isEqual(certificate.proposal.candidateDigest,
                            GroupStatements.digestMember(added))) throw ApiFailure(409,"group_invalid")
                    if(parent.members.any {it.memberId==localId} &&
                        !admissionV2.matchesOwnApproval(parent,certificate,localId))
                        throw ApiFailure(503,"group_admission_evidence_pending")
                    certificates[event.change.eventId]=parent to certificate
                }
            }
            parent=event.next
        }
        val candidates=control.chain.mapNotNull {it.change.added}.filter {added ->
            control.chain.any {it.change.added?.memberId==added.memberId &&
                certificates.containsKey(it.change.eventId)}
        }
        val members=(prior.members+control.chain.flatMap {it.next.members })
            .filter {member -> candidates.none {it.memberId==member.memberId}}
        val base=trusted(members,control.groupId,historicalOnly=true)
        val trusted=GroupTrustedPeer {member -> base.matches(member) || candidates.any {candidate ->
            candidate.memberId==member.memberId &&
                MessageDigest.isEqual(GroupStatements.digestMember(candidate),
                    GroupStatements.digestMember(member))
        }}
        val ledger=GroupLedger(records,trusted,localId)
        val result=ledger.applySnapshot(control.groupId,GroupSnapshot(control.state!!,control.chain),
            historicalPeer={member,revision ->
                historicalAuthority.matchesAt(control.groupId,member,revision)
            }) {final,chain ->
            chain.forEach {event -> event.change.added?.let {added ->
                certificates[event.change.eventId]?.let {(old,certificate) ->
                    historicalAuthority.record(old,event,certificate)
                    admissionV2.removeOwn(control.groupId,certificate.proposal.inviteId)
                }
                final.members.singleOrNull {it.memberId==added.memberId}?.let {
                    currentAuthority.anchor(final,it,DeviceAuth.digest(NetworkCodec.encode(event)))
                }
            }}
            currentAuthority.removeDeparted(final)
        }
        if(result==GroupApply.REMOVED) return
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
    /** Only type-13 maintenance entries staged by send() can use this group-scoped retry path. */
    private suspend fun processSystemOutbox() {
        for(key in records.transaction {records.keys("app/group/system-outbox/").sorted().take(16)}) {
            val id=key.substringAfterLast('/')
            val descriptor=records.transaction {records.read(key)?.decodeToString()} ?: continue
            val parts=descriptor.split('/')
            if(parts.size!=2 || !GroupIds.valid(parts[0]) || !RandomIdentifiers.valid(parts[1])) {
                records.transaction {records.remove(key)};continue
            }
            val (groupId,device)=parts
            val entry=runCatching {outbox.get(id)}.getOrNull()
            if(entry==null) {records.transaction {records.remove(key)};continue}
            if(entry.deviceId!=device) continue
            val current=state(groupId)
            val member=current?.members?.singleOrNull {it.deviceId==device}
            if(member==null || current.lifecycle!=GroupLifecycle.ACTIVE ||
                status(groupId)!=GroupLocalStatus.ACTIVE) {
                records.transaction {
                    if(entry.state==OutboxState.SERVER_ACCEPTED) outbox.removeFinished(id)
                    else outbox.cancelUnsentGroupSystem(id)
                    records.remove(key)
                }
                continue
            }
            try {
                if(!authority.matchesCurrentGroupMember(groupId,member)) continue
                val result=outbox.process(id)
                if(result.state in setOf(OutboxState.SERVER_ACCEPTED,OutboxState.FAILED,
                        OutboxState.SUBMISSION_EXPIRED)) records.transaction {
                    outbox.removeFinished(id)
                    records.remove(key)
                }
            } catch(e:CancellationException) {throw e}
            catch(e:CryptoFailure) {
                if(e.error !in setOf(CryptoError.IdentityChanged,CryptoError.UnknownSession,
                        CryptoError.ReauthenticationRequired)) throw e
            } catch(_:ApiFailure) { /* Retry without changing direct-contact status. */ }
        }
    }
}
