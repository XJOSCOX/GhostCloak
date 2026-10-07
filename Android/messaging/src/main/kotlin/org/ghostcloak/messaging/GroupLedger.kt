package org.ghostcloak.messaging

import kotlinx.serialization.Serializable
import org.ghostcloak.crypto.EndpointRecords
import org.ghostcloak.protocol.NetworkCodec
import java.security.MessageDigest

/** Encrypted local group state authority. Transport may only supply validated P13.2 bindings. */
@Serializable internal data class GroupRecord(
    val state:GroupState,
    val genesis:GroupGenesis? = null,
    val admission:GroupAdmission? = null,
    val events:List<GroupTransition> = emptyList(),
    val usedInvites:List<String> = emptyList(),
    val forked:Boolean = false
)

class GroupLedger(
    private val records:EndpointRecords,
    private val trustedPeer:GroupTrustedPeer,
    private val localMemberId:String,
) {
    init { require(GroupIds.valid(localMemberId)) }
    private fun key(id:String):String { require(GroupIds.valid(id)); return "group/state/v1/$id" }
    private val governance=GovernanceFoundationStore(records)
    private fun load(id:String,allowPreBarrierReplay:Boolean=false):GroupRecord? {
        val stored=records.read(key(id))
        if(stored==null) {
            check(governance.barrier(id)==null && governance.head(id)==null) {
                "governance_boundary_without_group"
            }
            return null
        }
        return stored.let {
        require(it.size<=GroupStatements.MAX_LEDGER_BYTES)
        NetworkCodec.decode<GroupRecord>(it,GroupStatements.MAX_LEDGER_BYTES).also {record ->
            GroupStatements.validate(record.state)
            record.genesis?.let { GroupStatements.validate(it.state) }
            record.admission?.let { GroupStatements.validate(it.state) }
            require((record.genesis==null) != (record.admission==null) &&
                record.state.groupId==id && (record.genesis?.state?.groupId ?: record.admission?.state?.groupId)==id &&
                (record.genesis==null || record.genesis.state.revision==1L) &&
                record.events.size<GroupStatements.MAX_EVENTS &&
                record.usedInvites.size<=GroupStatements.MAX_INVITES &&
                record.usedInvites.distinct().size==record.usedInvites.size)
            val barrier=governance.barrier(id)
            val head=governance.head(id)
            check((barrier==null)==(head==null)) {"governance_boundary_incomplete"}
            barrier?.let {
                val barrier=it
                val head=checkNotNull(head)
                check(MessageDigest.isEqual(head.activationDigest,barrier.activationDigest) &&
                    head.stateRevision>=barrier.activationStateRevision &&
                    head.sequence>=head.stateRevision-barrier.activationStateRevision &&
                    head.sequence<=GroupGovernanceJournalV1.MAX_ENTRIES)
                if(head.sequence==0L) check(head.stateRevision==barrier.activationStateRevision &&
                    MessageDigest.isEqual(head.stateDigest,barrier.activationStateDigest) &&
                    MessageDigest.isEqual(head.headDigest,barrier.activationDigest))
                val currentDigest=GroupStatements.digest(record.state)
                if(record.state.revision<barrier.activationStateRevision) {
                    check(allowPreBarrierReplay && head.sequence==0L &&
                        head.stateRevision==barrier.activationStateRevision &&
                        MessageDigest.isEqual(head.stateDigest,barrier.activationStateDigest))
                } else {
                    check(record.state.revision==head.stateRevision &&
                        MessageDigest.isEqual(currentDigest,head.stateDigest))
                    if(record.state.revision==barrier.activationStateRevision)
                        check(MessageDigest.isEqual(currentDigest,barrier.activationStateDigest))
                }
            }
        }
        }
    }
    private fun save(record:GroupRecord) {
        val bytes=NetworkCodec.encode(record)
        require(bytes.size<=GroupStatements.MAX_LEDGER_BYTES)
        records.write(key(record.state.groupId),bytes)
    }
    /** The single legacy-advance boundary shared by live updates and signed-chain replay. */
    private fun legacyAdvanceAllowed(id:String,currentRevision:Long,
        nextRevisions:List<Long>):Boolean {
        val barrier=governance.barrier(id) ?: return true
        return currentRevision<barrier.activationStateRevision &&
            nextRevisions.all {it<=barrier.activationStateRevision}
    }
    fun state(id:String):GroupState?=records.transaction { load(id)?.state }
    internal fun governanceHead(id:String):GovernanceHeadFoundationV1?=records.transaction {
        load(id);governance.head(id)
    }
    internal fun governanceBarrier(id:String):GovernanceBarrierV1?=records.transaction {
        load(id);governance.barrier(id)
    }
    /** An incomplete A6.2 history locks management while leaving readable history intact. */
    internal fun governanceJournalStatus(id:String):GovernanceJournalStatus?=runCatching { records.transaction {
        val record=load(id) ?: return@transaction null
        val barrier=governance.barrier(id) ?: return@transaction null
        val head=checkNotNull(governance.head(id))
        val join=GovernedAdmissionStore(records).join(id)
        val policyProof=GroupGovernancePolicyCheckpointStoreV1(records).join(id)
        if(join!=null && (join.proof.target.memberId!=localMemberId ||
            record.admission?.inviteId!=join.proof.inviteId ||
            record.events.none {it.change.action==GroupAction.ADD &&
                it.change.added?.memberId==localMemberId &&
                it.change.invite?.inviteId==join.proof.inviteId}))
            return@transaction GovernanceJournalStatus.LEGACY_INCOMPLETE
        val anchor=record.genesis?.state ?: record.admission?.state
        val activation=if(join!=null) join.proof.state else if(anchor?.revision==barrier.activationStateRevision) anchor else
            record.events.singleOrNull {it.next.revision==barrier.activationStateRevision}?.next
        if(join!=null && policyProof!=null &&
            !GroupGovernancePolicyCheckpointRulesV1.verify(policyProof,join.checkpoint,
                join.proof.state)) return@transaction GovernanceJournalStatus.LEGACY_INCOMPLETE
        GroupGovernanceJournalV1(records).status(id,barrier,head,activation,record.events,
            policyProof?.policy ?: GroupGovernancePolicyRulesV1.initial())
    } }.getOrDefault(GovernanceJournalStatus.LEGACY_INCOMPLETE)
    /** Policy is derived from the authenticated journal, never trusted from a local snapshot. */
    internal fun governancePolicy(id:String):GroupGovernancePolicyV1?=runCatching { records.transaction {
        val record=load(id) ?: return@transaction null
        val barrier=governance.barrier(id) ?: return@transaction null
        val head=governance.head(id) ?: return@transaction null
        val join=GovernedAdmissionStore(records).join(id)
        val policyProof=GroupGovernancePolicyCheckpointStoreV1(records).join(id)
        val anchor=record.genesis?.state ?: record.admission?.state
        val activation=if(join!=null) join.proof.state else if(anchor?.revision==barrier.activationStateRevision) anchor else
            record.events.singleOrNull {it.next.revision==barrier.activationStateRevision}?.next
        if(join!=null && policyProof!=null &&
            !GroupGovernancePolicyCheckpointRulesV1.verify(policyProof,join.checkpoint,
                join.proof.state)) return@transaction null
        GroupGovernanceJournalV1(records).currentPolicy(id,barrier,head,activation,record.events,
            policyProof?.policy ?: GroupGovernancePolicyRulesV1.initial())
    } }.getOrNull()
    /** A rival is a fork only when both governance and wrapped v1 successors verify. */
    internal fun markGovernanceForkIfValid(entry:GroupGovernanceEntryV1,
        historicalPeer:((GroupMember,Long)->Boolean)?=null,
        admissionEvidence:AdmissionCertificateV2?=null):GroupApply=records.transaction {
        val id=entry.groupId
        val record=load(id) ?: return@transaction GroupApply.REJECTED
        if(record.forked) return@transaction GroupApply.FORKED
        val barrier=governance.barrier(id) ?: return@transaction GroupApply.REJECTED
        val head=governance.head(id) ?: return@transaction GroupApply.REJECTED
        if(entry.sequence !in 1..head.sequence ||
            governanceJournalStatus(id)!=GovernanceJournalStatus.COMPLETE)
            return@transaction GroupApply.REJECTED
        val journal=GroupGovernanceJournalV1(records)
        val accepted=journal.entry(id,entry.sequence)
        val acceptedPolicy=journal.policyEntry(id,entry.sequence)
        if(accepted==null && acceptedPolicy==null) return@transaction GroupApply.REJECTED
        if(accepted!=null && MessageDigest.isEqual(GroupGovernanceV1.entryDigest(accepted),
                GroupGovernanceV1.entryDigest(entry))) return@transaction GroupApply.DUPLICATE
        val join=GovernedAdmissionStore(records).join(id)
        val start=join?.checkpoint?.parentSequence ?: 0L
        if(entry.sequence<=start) return@transaction GroupApply.REJECTED
        val preceding=if(entry.sequence==start+1) null else journal.entry(id,entry.sequence-1)
        val precedingPolicy=if(entry.sequence==start+1) null else journal.policyEntry(id,entry.sequence-1)
        if(entry.sequence>start+1 && (preceding==null)==(precedingPolicy==null))
            return@transaction GroupApply.REJECTED
        val pre=if(preceding!=null) preceding.transition.next else if(precedingPolicy!=null) {
            val revision=precedingPolicy.stateRevision
            val anchor=join?.proof?.state ?: record.genesis?.state ?: record.admission?.state
            if(anchor?.revision==revision) anchor else
                record.events.singleOrNull {it.next.revision==revision}?.next
        } else {
            val anchor=join?.proof?.state ?: record.genesis?.state ?: record.admission?.state
            if(join!=null || anchor?.revision==barrier.activationStateRevision) anchor else
                record.events.singleOrNull {it.next.revision==barrier.activationStateRevision}?.next
        } ?: return@transaction GroupApply.REJECTED
        val prior=GovernanceHeadFoundationV1(groupId=id,activationDigest=barrier.activationDigest,
            sequence=entry.sequence-1,
            headDigest=preceding?.let(GroupGovernanceV1::entryDigest) ?:
                precedingPolicy?.let(GroupGovernancePolicyRulesV1::entryDigest) ?:
                join?.checkpoint?.parentHeadDigest
                ?: barrier.activationDigest,
            stateRevision=pre.revision,stateDigest=GroupStatements.digest(pre))
        if(!GroupGovernanceV1.verifyEntry(entry,pre,prior) ||
            !authorizedSignatures(pre,entry.transition,historicalPeer))
            return@transaction GroupApply.REJECTED
        val candidate=entry.transition.change.added
        if(candidate!=null && (entry.transition.change.action!=GroupAction.ADD ||
            admissionEvidence==null || !AdmissionV2.verifyCertificate(admissionEvidence,pre) ||
            admissionEvidence.proposal.eventId!=entry.eventId ||
            !MessageDigest.isEqual(admissionEvidence.proposal.candidateDigest,
                GroupStatements.digestMember(candidate)))) return@transaction GroupApply.REJECTED
        val usedBefore=record.events.filter {it.next.revision<=pre.revision}
            .mapNotNull {it.change.invite?.inviteId}.toSet()
        val derived=runCatching {GroupRules.derive(pre,entry.transition.change,usedBefore,
            GroupTrustedPeer {true})}.getOrNull() ?: return@transaction GroupApply.REJECTED
        if(!MessageDigest.isEqual(GroupStatements.digest(derived),
                GroupStatements.digest(entry.transition.next))) return@transaction GroupApply.REJECTED
        save(record.copy(forked=true))
        GroupApply.FORKED
    }
    /** A valid competing policy successor freezes the group; no local fork winner exists. */
    internal fun markGovernancePolicyForkIfValid(entry:GroupGovernancePolicyEntryV1):GroupApply=
        records.transaction {
            val id=entry.groupId
            val record=load(id) ?: return@transaction GroupApply.REJECTED
            if(record.forked) return@transaction GroupApply.FORKED
            val barrier=governance.barrier(id) ?: return@transaction GroupApply.REJECTED
            val head=governance.head(id) ?: return@transaction GroupApply.REJECTED
            if(entry.sequence !in 1..head.sequence ||
                governanceJournalStatus(id)!=GovernanceJournalStatus.COMPLETE)
                return@transaction GroupApply.REJECTED
            val journal=GroupGovernanceJournalV1(records)
            val accepted=journal.policyEntry(id,entry.sequence)
            if(accepted!=null && MessageDigest.isEqual(
                    GroupGovernancePolicyRulesV1.entryDigest(accepted),
                    GroupGovernancePolicyRulesV1.entryDigest(entry)))
                return@transaction GroupApply.DUPLICATE
            if(accepted==null && journal.entry(id,entry.sequence)==null)
                return@transaction GroupApply.REJECTED
            val join=GovernedAdmissionStore(records).join(id)
            val start=join?.checkpoint?.parentSequence ?: 0L
            if(entry.sequence<=start) return@transaction GroupApply.REJECTED
            val priorEntry=if(entry.sequence>start+1) journal.entry(id,entry.sequence-1) else null
            val priorPolicy=if(entry.sequence>start+1) journal.policyEntry(id,entry.sequence-1) else null
            if(entry.sequence>start+1 && (priorEntry==null)==(priorPolicy==null))
                return@transaction GroupApply.REJECTED
            val anchor=join?.proof?.state ?: record.genesis?.state ?: record.admission?.state
            val activation=if(join!=null) join.proof.state else if(anchor?.revision==barrier.activationStateRevision)
                anchor else record.events.singleOrNull {
                    it.next.revision==barrier.activationStateRevision}?.next
            val pre=if(activation?.revision==entry.stateRevision) activation else
                record.events.singleOrNull {it.next.revision==entry.stateRevision}?.next
                ?: return@transaction GroupApply.REJECTED
            val parentDigest=priorEntry?.let(GroupGovernanceV1::entryDigest) ?:
                priorPolicy?.let(GroupGovernancePolicyRulesV1::entryDigest) ?:
                join?.checkpoint?.parentHeadDigest ?: barrier.activationDigest
            val prior=GovernanceHeadFoundationV1(groupId=id,activationDigest=barrier.activationDigest,
                sequence=entry.sequence-1,headDigest=parentDigest,
                stateRevision=pre.revision,stateDigest=GroupStatements.digest(pre))
            val initial=GroupGovernancePolicyCheckpointStoreV1(records).join(id)?.policy
                ?: GroupGovernancePolicyRulesV1.initial()
            val policy=journal.currentPolicy(id,barrier,head,activation,record.events,initial,
                entry.sequence-1) ?: return@transaction GroupApply.REJECTED
            if(!GroupGovernancePolicyRulesV1.verifyEntry(entry,pre,prior,policy))
                return@transaction GroupApply.REJECTED
            save(record.copy(forked=true))
            GroupApply.FORKED
        }
    /** Recovery-only read for a pre-anchor legacy chain; never used to display group content. */
    internal fun stateForLegacyReplay(id:String):GroupState?=records.transaction {
        load(id,allowPreBarrierReplay=true)?.state
    }
    fun isForked(id:String):Boolean=records.transaction {
        load(id,allowPreBarrierReplay=true)?.forked==true
    }
    fun lifecycle(id:String):GroupLifecycle?=state(id)?.lifecycle
    fun anchorRevision(id:String):Long?=records.transaction {
        load(id)?.let {it.genesis?.state?.revision ?: it.admission?.state?.revision}
    }
    fun transitionsAfter(id:String,revision:Long):List<GroupTransition>?=records.transaction {
        val record=load(id) ?: return@transaction null
        val anchor=record.genesis?.state?.revision ?: record.admission?.state?.revision ?: return@transaction null
        if(revision<anchor || revision>record.state.revision) return@transaction null
        record.events.filter {it.next.revision>revision}
    }
    fun digestAtRevision(id:String,revision:Long):ByteArray?=records.transaction {
        val record=load(id) ?: return@transaction null
        val anchor=record.genesis?.state ?: record.admission?.state ?: return@transaction null
        val state=if(revision==anchor.revision) anchor else record.events.firstOrNull {
            it.next.revision==revision
        }?.next ?: return@transaction null
        GroupStatements.digest(state)
    }
    fun inviteUsed(id:String,inviteId:String):Boolean=records.transaction {
        load(id)?.usedInvites?.contains(inviteId)==true
    }
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
        if (old!=null) return@transaction if (old.genesis!=null && GroupStatements.digest(old.genesis.state).contentEquals(
                GroupStatements.digest(state))) GroupApply.DUPLICATE else GroupApply.FORKED.also {
                    save(old.copy(forked=true))
                }
        save(GroupRecord(state,genesis=genesis))
        GroupApply.ACCEPTED
    }

    /** New members get a current-state admission proof, never a blind snapshot replacement. */
    fun acceptAdmission(proof:GroupAdmission):GroupApply=records.transaction {
        val state=proof.state
        // A6.3 will provide a distinct, checkpoint-bound new-invitee bootstrap.
        if(governance.barrier(state.groupId)!=null) return@transaction GroupApply.REJECTED
        if(proof.target.memberId!=localMemberId || state.members.any {it.memberId==localMemberId} ||
            state.members.size>=GroupStatements.MAX_MEMBERS ||
            !GroupStatements.verifyAdmission(proof,trustedPeer)) return@transaction GroupApply.REJECTED
        val old=load(state.groupId)
        if(old!=null) {
            if(old.admission?.let {it.inviteId==proof.inviteId &&
                    GroupStatements.digest(it.state).contentEquals(GroupStatements.digest(state))}==true)
                return@transaction GroupApply.DUPLICATE
            // A never-joined invitee may accept a fresh dual-signed admission after the old
            // invitation expired. No signed membership event or active state may be replaced.
            if(old.admission==null || old.events.isNotEmpty() || old.forked ||
                old.state.members.any {it.memberId==localMemberId} ||
                state.revision<old.state.revision ||
                (state.revision==old.state.revision &&
                    !GroupStatements.digest(state).contentEquals(GroupStatements.digest(old.state))))
                return@transaction GroupApply.REJECTED
        }
        save(GroupRecord(state,admission=proof))
        GroupApply.ACCEPTED
    }

    fun apply(id:String,event:GroupTransition,onAccepted:()->Unit = {}):GroupApply=records.transaction {
        val record=load(id,allowPreBarrierReplay=true) ?: return@transaction GroupApply.NEEDS_RESYNC
        if(!legacyAdvanceAllowed(id,record.state.revision,listOf(event.next.revision)))
            return@transaction GroupApply.REJECTED
        governance.barrier(id)?.let {barrier ->
            if(event.next.revision==barrier.activationStateRevision) {
                val (result,next)=applyInTransaction(record,event,false)
                if(result==GroupApply.FORKED) {
                    save(record.copy(forked=true))
                    return@transaction result
                }
                if(result!=GroupApply.ACCEPTED && result!=GroupApply.REMOVED)
                    return@transaction result
                if(next==null) return@transaction GroupApply.REJECTED
                if(!MessageDigest.isEqual(GroupStatements.digest(next.state),
                    barrier.activationStateDigest)) {
                    save(record.copy(forked=true))
                    return@transaction GroupApply.FORKED
                }
                save(next)
                onAccepted()
                return@transaction result
            }
        }
        applyInTransaction(record,event,true,onAccepted,null).first
    }

    /** Dormant A6.1 primitive. Only a future verified governance entry may supply its head. */
    internal fun applyGovernedTransition(id:String,expectedHead:GovernanceHeadFoundationV1,
        event:GroupTransition,nextHeadDigest:ByteArray,
        historicalPeer:((GroupMember,Long)->Boolean)?=null,
        onAccepted:()->Unit={}):GroupApply=records.transaction {
        val barrier=governance.barrier(id) ?: return@transaction GroupApply.REJECTED
        val currentHead=governance.head(id) ?: error("governance_head_missing")
        if(!governance.matches(expectedHead,currentHead) ||
            !MessageDigest.isEqual(expectedHead.activationDigest,barrier.activationDigest))
            return@transaction GroupApply.REJECTED
        val record=load(id) ?: return@transaction GroupApply.NEEDS_RESYNC
        if(currentHead.sequence>0 && event.next.revision>record.state.revision &&
            governanceJournalStatus(id)!=GovernanceJournalStatus.COMPLETE)
            return@transaction GroupApply.REJECTED
        val (result,next)=applyInTransaction(record,event,false,{},historicalPeer)
        if(result==GroupApply.FORKED) {
            save(record.copy(forked=true))
            return@transaction result
        }
        if(result!=GroupApply.ACCEPTED && result!=GroupApply.REMOVED) return@transaction result
        if(next==null || !governance.advance(expectedHead,next.state,nextHeadDigest))
            return@transaction GroupApply.REJECTED
        save(next)
        onAccepted()
        result
    }
    /** A policy-only action advances the same governance head and journal atomically. */
    internal fun applyGovernedPolicy(entry:GroupGovernancePolicyEntryV1,
        onAccepted:()->Unit={}):GroupApply=records.transaction {
        val id=entry.groupId
        val record=load(id) ?: return@transaction GroupApply.NEEDS_RESYNC
        val barrier=governance.barrier(id) ?: return@transaction GroupApply.REJECTED
        val head=governance.head(id) ?: return@transaction GroupApply.REJECTED
        if(record.forked || governanceJournalStatus(id)!=GovernanceJournalStatus.COMPLETE ||
            head.sequence>=GroupGovernanceJournalV1.MAX_ENTRIES)
            return@transaction GroupApply.REJECTED
        val policy=governancePolicy(id) ?: return@transaction GroupApply.REJECTED
        if(!GroupGovernancePolicyRulesV1.verifyEntry(entry,record.state,head,policy))
            return@transaction GroupApply.REJECTED
        if(entry.action==GroupPolicyActionV1.REMOVE_GROUP_MESSAGE &&
            GroupChatStore(records).isModerated(id,checkNotNull(entry.targetLogicalId)))
            return@transaction GroupApply.REJECTED
        val digest=GroupGovernancePolicyRulesV1.entryDigest(entry)
        if(!governance.advancePolicy(head,digest)) return@transaction GroupApply.REJECTED
        val nextHead=checkNotNull(governance.head(id))
        GroupGovernanceJournalV1(records).appendPolicy(entry,barrier,nextHead)
        if(entry.action==GroupPolicyActionV1.REMOVE_GROUP_MESSAGE)
            check(GroupChatStore(records).moderate(id,checkNotNull(entry.targetLogicalId)))
        onAccepted()
        GroupApply.ACCEPTED
    }

    /** Internal future-activation hook. Nothing in production invokes it in A6.1. */
    internal fun installGovernanceBarrierForFutureActivation(id:String,
        activationDigest:ByteArray,onInstalled:()->Unit={}):GroupApply=records.transaction {
        require(activationDigest.size==32)
        val record=load(id) ?: return@transaction GroupApply.REJECTED
        val current=record.state
        val existing=governance.barrier(id)
        if(existing!=null) return@transaction if(current.groupId==existing.groupId &&
            MessageDigest.isEqual(existing.activationDigest,activationDigest)) GroupApply.DUPLICATE
            else GroupApply.REJECTED
        if(record.forked || current.lifecycle!=GroupLifecycle.ACTIVE ||
            current.members.none {it.memberId==localMemberId}) return@transaction GroupApply.REJECTED
        val baseline=GroupAuthorityBaselineStore(records)
        val active=baseline.active(id) ?: return@transaction GroupApply.REJECTED
        val certificate=baseline.certificate(id) ?: return@transaction GroupApply.REJECTED
        val digest=GroupStatements.digest(current)
        if(baseline.pending(id)!=null || active.stateRevision!=current.revision ||
            !MessageDigest.isEqual(active.stateDigest,digest) ||
            !MessageDigest.isEqual(active.certificateDigest,certificate.digest) ||
            !MessageDigest.isEqual(active.memberSetDigest,
                GroupAuthorityBaselineV1.memberSetDigest(GroupAuthorityBaselineV1.memberSet(current))) ||
            !GroupAuthorityBaselineV1.verifyCertificate(certificate,current))
            return@transaction GroupApply.REJECTED
        governance.install(GovernanceBarrierV1(groupId=id,
            activationStateRevision=current.revision,activationStateDigest=digest,
            activationDigest=activationDigest,baselineCertificateDigest=certificate.digest))
        onInstalled()
        GroupApply.ACCEPTED
    }
    /** Only the exact newly invited target may install a signed join-point checkpoint. */
    internal fun acceptGovernedBootstrap(evidence:GovernedJoinEvidenceV1,
        acceptedInvite:GroupInvite,entry:GroupGovernanceEntryV1,
        policyProof:GroupGovernancePolicyCheckpointV1?=null,
        onAccepted:()->Unit={}):GroupApply=records.transaction {
        val checkpoint=evidence.checkpoint
        val proof=evidence.proof
        val parent=proof.state
        val id=parent.groupId
        if(proof.target.memberId!=localMemberId || acceptedInvite.target.memberId!=localMemberId ||
            acceptedInvite.inviteId!=proof.inviteId ||
            !MessageDigest.isEqual(GroupStatements.digestMember(acceptedInvite.target),
                GroupStatements.digestMember(proof.target)) ||
            !GroupStatements.verifyOffer(acceptedInvite.copy(targetAcceptance=byteArrayOf()),
                parent,trustedPeer) ||
            !GroupStatements.verifyInvite(acceptedInvite,parent,emptySet()) ||
            !GroupStatements.verifyAdmission(proof,trustedPeer) ||
            !AdmissionV2.verifyCertificate(evidence.certificate,parent) ||
            !GroupGovernedAdmissionV1.verifyCheckpoint(checkpoint,parent,proof,
                evidence.certificate,evidence.binding) ||
            (policyProof!=null && !GroupGovernancePolicyCheckpointRulesV1.verify(
                policyProof,checkpoint,parent)) ||
            entry.groupId!=id || entry.sequence!=checkpoint.parentSequence+1 ||
            entry.action!=GroupAction.ADD ||
            entry.eventId!=evidence.binding.eventId ||
            entry.transition.change.invite?.inviteId!=proof.inviteId ||
            entry.transition.change.added?.memberId!=localMemberId ||
            !MessageDigest.isEqual(entry.transition.change.added?.let {
                GroupStatements.digestMember(it)} ?: byteArrayOf(),checkpoint.candidateDigest))
            return@transaction GroupApply.REJECTED
        val old=load(id)
        if(old!=null) return@transaction if(old.state.revision==entry.postRevision &&
            MessageDigest.isEqual(GroupStatements.digest(old.state),entry.postDigest) &&
            GovernedAdmissionStore(records).join(id)?.checkpoint?.checkpointId==checkpoint.checkpointId &&
            governanceJournalStatus(id)==GovernanceJournalStatus.COMPLETE) GroupApply.DUPLICATE
            else GroupApply.REJECTED
        val parentHead=GovernanceHeadFoundationV1(groupId=id,
            activationDigest=checkpoint.activationDigest,sequence=checkpoint.parentSequence,
            headDigest=checkpoint.parentHeadDigest,stateRevision=checkpoint.parentRevision,
            stateDigest=checkpoint.parentStateDigest)
        if(!GroupGovernanceV1.verifyEntry(entry,parent,parentHead) ||
            !authorizedSignatures(parent,entry.transition,null)) return@transaction GroupApply.REJECTED
        val next=runCatching {GroupRules.derive(parent,entry.transition.change,
            emptySet(),trustedPeer)}.getOrNull() ?: return@transaction GroupApply.REJECTED
        if(!MessageDigest.isEqual(GroupStatements.digest(next),entry.postDigest) ||
            !MessageDigest.isEqual(GroupStatements.digest(next),
                GroupStatements.digest(entry.transition.next))) return@transaction GroupApply.REJECTED
        val barrier=GovernanceBarrierV1(groupId=id,
            activationStateRevision=checkpoint.activationStateRevision,
            activationStateDigest=checkpoint.activationStateDigest,
            activationDigest=checkpoint.activationDigest,
            baselineCertificateDigest=checkpoint.baselineCertificateDigest)
        val head=GovernanceHeadFoundationV1(groupId=id,
            activationDigest=checkpoint.activationDigest,sequence=entry.sequence,
            headDigest=GroupGovernanceV1.entryDigest(entry),
            stateRevision=next.revision,stateDigest=entry.postDigest)
        save(GroupRecord(next,admission=proof,events=listOf(entry.transition),
            usedInvites=listOf(proof.inviteId)))
        governance.installAtJoinPoint(barrier,head)
        if(policyProof==null) GovernedAdmissionStore(records).saveJoin(id,evidence)
        else GovernedAdmissionStore(records).saveJoinV2(id,evidence,policyProof)
        GroupGovernanceJournalV1(records).append(entry,barrier,head)
        onAccepted()
        GroupApply.ACCEPTED
    }

    /** A gap requires every signed intermediate event; no unverified snapshot fast-forward. */
    fun applySnapshot(id:String,snapshot:GroupSnapshot,
        historicalPeer:((GroupMember,Long)->Boolean)?=null,
        onAccepted:(GroupState,List<GroupTransition>)->Unit = {_,_->}):GroupApply=records.transaction {
        var record=load(id,allowPreBarrierReplay=true) ?: return@transaction GroupApply.NEEDS_RESYNC
        if (record.forked) return@transaction GroupApply.FORKED
        if (snapshot.chain.size !in 1..GroupStatements.MAX_EVENTS ||
            snapshot.finalState.groupId!=id) return@transaction GroupApply.REJECTED
        if(!legacyAdvanceAllowed(id,record.state.revision,
                snapshot.chain.map {it.next.revision}+snapshot.finalState.revision))
            return@transaction GroupApply.REJECTED
        val barrier=governance.barrier(id)
        val accepted=ArrayList<GroupTransition>(snapshot.chain.size)
        for (event in snapshot.chain) {
            val (result,next)=applyInTransaction(record,event,false,{},historicalPeer)
            if (result==GroupApply.FORKED) {
                save(record.copy(forked=true))
                return@transaction result
            }
            if ((result!=GroupApply.ACCEPTED && result!=GroupApply.REMOVED) || next==null) return@transaction result
            if(barrier!=null && next.state.revision==barrier.activationStateRevision &&
                !MessageDigest.isEqual(GroupStatements.digest(next.state),
                    barrier.activationStateDigest)) {
                save(record.copy(forked=true))
                return@transaction GroupApply.FORKED
            }
            record=next
            accepted.add(event)
            if (record.state.members.none {it.memberId==localMemberId}) {
                save(record)
                // An admission before local removal must commit its historical authority
                // in the same transaction as the replayed membership state.
                onAccepted(record.state,accepted)
                return@transaction GroupApply.REMOVED
            }
        }
        if (!runCatching {GroupStatements.digest(record.state).contentEquals(
                GroupStatements.digest(snapshot.finalState))}.getOrDefault(false))
            return@transaction GroupApply.REJECTED
        save(record)
        onAccepted(record.state,accepted)
        GroupApply.ACCEPTED
    }

    private fun applyInTransaction(record:GroupRecord,event:GroupTransition,persist:Boolean,
        onAccepted:()->Unit = {},
        historicalPeer:((GroupMember,Long)->Boolean)?=null):Pair<GroupApply,GroupRecord?> {
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
            val parent=if (record.events.size==1) record.genesis?.state ?: record.admission?.state
                else record.events.getOrNull(record.events.size-2)?.next
            val usedBeforeLast=record.usedInvites.toMutableSet().also { used ->
                record.events.lastOrNull()?.change?.invite?.inviteId?.let(used::remove)
            }
            if (parent!=null && next.previousDigest.contentEquals(GroupStatements.digest(parent)) &&
                authorizedSignatures(parent,event,historicalPeer) && runCatching {
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
            if (authorizedSignatures(previous,event,historicalPeer)) {
                if (persist) save(record.copy(forked=true))
                return GroupApply.FORKED to null
            }
            return GroupApply.REJECTED to null
        }
        if (!authorizedSignatures(previous,event,historicalPeer)) return GroupApply.REJECTED to null
        if (record.events.any {it.change.eventId==event.change.eventId}) return GroupApply.REJECTED to null
        val derived=runCatching {GroupRules.derive(previous,event.change,record.usedInvites.toSet(),trustedPeer)}
            .getOrNull() ?: return GroupApply.REJECTED to null
        if (!GroupStatements.digest(derived).contentEquals(nextDigest)) return GroupApply.REJECTED to null
        if (record.events.size>=GroupStatements.MAX_EVENTS-1) return GroupApply.REJECTED to null
        val inviteId=event.change.invite?.inviteId
        val used=if(inviteId==null) record.usedInvites else record.usedInvites+inviteId
        if (used.size>GroupStatements.MAX_INVITES) return GroupApply.REJECTED to null
        val updated=record.copy(state=next,events=record.events+event,usedInvites=used)
        if (persist) { save(updated); onAccepted() }
        return (if(next.members.none {it.memberId==localMemberId}) GroupApply.REMOVED else GroupApply.ACCEPTED) to updated
    }

    private fun authorizedSignatures(previous:GroupState,event:GroupTransition,
        historicalPeer:((GroupMember,Long)->Boolean)?):Boolean {
        val actor=previous.members.singleOrNull {it.memberId==event.change.actorId} ?: return false
        val coordinator=previous.members.singleOrNull {it.memberId==previous.coordinatorId} ?: return false
        fun trusted(member:GroupMember)=trustedPeer.matches(member) ||
            historicalPeer?.invoke(member,previous.revision)==true
        if (!trusted(actor) || !trusted(coordinator)) return false
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
