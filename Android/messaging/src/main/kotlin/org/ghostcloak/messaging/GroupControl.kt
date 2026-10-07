package org.ghostcloak.messaging

import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import org.ghostcloak.protocol.NetworkCodec

/** Versioned application controls carried only inside an authenticated one-to-one Signal envelope. */
enum class GroupControlKind { INVITE, ACCEPT, INVITE_EXPIRED, STATE_UPDATE, RESYNC_REQUEST,
    RESYNC_RESPONSE, ADMISSION_REQUEST, ADMISSION_RESPONSE,
    ADMISSION_V2_PROPOSAL, ADMISSION_V2_APPROVAL, ADMISSION_V2_EVIDENCE_REQUEST,
    ADMISSION_V2_EVIDENCE_RESPONSE, BASELINE_V1_PROPOSAL, BASELINE_V1_APPROVAL,
    BASELINE_V1_CERTIFICATE,
    GOVERNANCE_OWNER_SIGN_REQUEST, GOVERNANCE_OWNER_SIGN_RESPONSE,
    GOVERNANCE_ACTIVATION_PROPOSAL, GOVERNANCE_ACTIVATION_ACK,
    GOVERNANCE_ACTIVATION_COMMIT, GOVERNANCE_ACTIVATION_INSTALLED_ACK,
    GOVERNANCE_ACTIVATION_READY, GOVERNANCE_ENTRY, GOVERNANCE_ENTRY_APPLIED_ACK,
    GOVERNANCE_CAPABILITY_ECHO, GOVERNANCE_RESYNC_REQUEST_V1,
    GOVERNANCE_RESYNC_RESPONSE_V1, GOVERNANCE_CHECKPOINT_SIGN_REQUEST_V1,
    GOVERNANCE_CHECKPOINT_SIGN_RESPONSE_V1, GOVERNANCE_BOOTSTRAP_V1,
    GOVERNANCE_POLICY_ENTRY_V1, GOVERNANCE_POLICY_ENTRY_ACK_V1,
    GOVERNANCE_POLICY_PROPOSAL_V1, GOVERNANCE_RESYNC_RESPONSE_V2,
    GOVERNANCE_CHANGE_PROPOSAL_V1, GOVERNANCE_CHANGE_SIGN_REQUEST_V1,
    GOVERNANCE_CHANGE_SIGN_RESPONSE_V1, GOVERNANCE_TRANSFER_REQUEST_V1,
    GOVERNANCE_TRANSFER_DECISION_V1, GOVERNANCE_INVITE_DELEGATION_V1 }

/** Only internal maintenance is permitted through a blocked canonical group relationship. */
internal fun GroupControlKind.blockSafeMaintenance(): Boolean = this in setOf(
    GroupControlKind.STATE_UPDATE, GroupControlKind.RESYNC_REQUEST, GroupControlKind.RESYNC_RESPONSE,
    GroupControlKind.ADMISSION_REQUEST, GroupControlKind.ADMISSION_RESPONSE,
    GroupControlKind.ADMISSION_V2_PROPOSAL, GroupControlKind.ADMISSION_V2_APPROVAL,
    GroupControlKind.ADMISSION_V2_EVIDENCE_REQUEST, GroupControlKind.ADMISSION_V2_EVIDENCE_RESPONSE,
    GroupControlKind.BASELINE_V1_PROPOSAL, GroupControlKind.BASELINE_V1_APPROVAL,
    GroupControlKind.BASELINE_V1_CERTIFICATE,
    GroupControlKind.GOVERNANCE_OWNER_SIGN_REQUEST,GroupControlKind.GOVERNANCE_OWNER_SIGN_RESPONSE,
    GroupControlKind.GOVERNANCE_ACTIVATION_PROPOSAL,GroupControlKind.GOVERNANCE_ACTIVATION_ACK,
    GroupControlKind.GOVERNANCE_ACTIVATION_COMMIT,GroupControlKind.GOVERNANCE_ACTIVATION_INSTALLED_ACK,
    GroupControlKind.GOVERNANCE_ACTIVATION_READY,GroupControlKind.GOVERNANCE_ENTRY,
    GroupControlKind.GOVERNANCE_ENTRY_APPLIED_ACK,GroupControlKind.GOVERNANCE_CAPABILITY_ECHO,
    GroupControlKind.GOVERNANCE_RESYNC_REQUEST_V1,GroupControlKind.GOVERNANCE_RESYNC_RESPONSE_V1,
    GroupControlKind.GOVERNANCE_CHECKPOINT_SIGN_REQUEST_V1,
    GroupControlKind.GOVERNANCE_CHECKPOINT_SIGN_RESPONSE_V1,
    GroupControlKind.GOVERNANCE_POLICY_ENTRY_V1,GroupControlKind.GOVERNANCE_POLICY_ENTRY_ACK_V1,
    GroupControlKind.GOVERNANCE_POLICY_PROPOSAL_V1,GroupControlKind.GOVERNANCE_RESYNC_RESPONSE_V2,
    GroupControlKind.GOVERNANCE_CHANGE_PROPOSAL_V1,GroupControlKind.GOVERNANCE_CHANGE_SIGN_REQUEST_V1,
    GroupControlKind.GOVERNANCE_CHANGE_SIGN_RESPONSE_V1,GroupControlKind.GOVERNANCE_TRANSFER_REQUEST_V1,
    GroupControlKind.GOVERNANCE_TRANSFER_DECISION_V1)

@OptIn(ExperimentalSerializationApi::class)
@Serializable data class GroupControl(
    val version: Int = 1,
    val kind: GroupControlKind,
    val groupId: String,
    val inviteId: String? = null,
    val state: GroupState? = null,
    val genesis: GroupGenesis? = null,
    val admission: GroupAdmission? = null,
    val invite: GroupInvite? = null,
    val transition: GroupTransition? = null,
    val chain: List<GroupTransition> = emptyList(),
    val fromRevision: Long? = null,
    val fromDigest: ByteArray? = null,
    val headRevision: Long? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val proposalV2: AdmissionProposalV2? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val approvalV2: AdmissionApprovalV2? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val certificateV2: AdmissionCertificateV2? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val evidenceEventId: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val baselineProposalV1:GroupAuthorityBaselineProposalV1?=null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val baselineApprovalV1:GroupAuthorityBaselineApprovalV1?=null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val baselineCertificateV1:GroupAuthorityBaselineCertificateV1?=null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val governanceProposalV1:GroupGovernanceActivationProposalV1?=null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val governanceAckV1:GroupGovernanceActivationAckV1?=null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val governanceCommitV1:GroupGovernanceActivationCommitV1?=null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val governanceInstalledV1:GroupGovernanceInstalledAckV1?=null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val governanceReadyV1:GroupGovernanceReadyV1?=null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val governanceEntryV1:GroupGovernanceEntryV1?=null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val governanceEntryAckV1:GroupGovernanceEntryAppliedAckV1?=null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val governanceResyncRequestV1:GroupGovernanceResyncRequestV1?=null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val governanceResyncResponseV1:GroupGovernanceResyncResponseV1?=null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val governedAdmissionBindingV1:GovernedAdmissionBindingV1?=null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val governanceCheckpointV1:GroupGovernanceInviteeCheckpointV1?=null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val governancePolicyEntryV1:GroupGovernancePolicyEntryV1?=null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val governanceResyncResponseV2:GroupGovernanceResyncResponseV2?=null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val governancePolicyCheckpointV1:GroupGovernancePolicyCheckpointV1?=null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val governedChangeProposalV1:GroupGovernedChangeProposalV1?=null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val ownershipRequestV1:GroupOwnershipRequestV1?=null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val ownershipDecisionV1:GroupOwnershipDecisionV1?=null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val inviteDelegationV1:GroupInviteDelegationV1?=null,
) {
    override fun toString() = "GroupControl(redacted)"
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable data class PendingGroupControl(val senderDeviceId: String, val control: GroupControl,
    val groupScoped: Boolean = false,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val baselineV1Advertised:Boolean=false,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val governanceV1Advertised:Boolean=false,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val governanceTextV2Advertised:Boolean=false) {
    override fun toString() = "PendingGroupControl(redacted)"
}

object GroupControlCodec {
    // Leaves at least 4 KiB below the current 16 KiB application body cap.
    const val MAX_BYTES = 12_000
    fun encode(control: GroupControl): ByteArray {
        validate(control)
        return NetworkCodec.encode(control).also { require(it.size in 1..MAX_BYTES) }
    }
    fun decode(bytes: ByteArray): GroupControl {
        require(bytes.size in 1..MAX_BYTES)
        return NetworkCodec.decode<GroupControl>(bytes, MAX_BYTES).also(::validate)
    }
    private fun validate(control: GroupControl) {
        require(control.version == 1 && GroupIds.valid(control.groupId))
        control.inviteId?.let { require(GroupIds.valid(it)) }
        control.fromDigest?.let { require(it.size == 32) }
        control.state?.let { GroupStatements.validate(it); require(it.groupId == control.groupId) }
        control.genesis?.let { GroupStatements.validate(it.state); require(it.state.groupId == control.groupId) }
        control.admission?.let { GroupStatements.validate(it.state); require(it.state.groupId == control.groupId) }
        control.invite?.let { require(it.groupId == control.groupId) }
        control.transition?.let { GroupStatements.validateEventShape(it); require(it.next.groupId == control.groupId) }
        require(control.chain.size <= GroupStatements.MAX_EVENTS)
        control.chain.forEach { GroupStatements.validateEventShape(it); require(it.next.groupId == control.groupId) }
        control.proposalV2?.let { require(it.groupId==control.groupId &&
            NetworkCodec.encode(it).size<=AdmissionV2.MAX_PROPOSAL_BYTES) }
        control.approvalV2?.let { require(NetworkCodec.encode(it).size<=AdmissionV2.MAX_APPROVAL_BYTES) }
        control.certificateV2?.let { require(it.proposal.groupId==control.groupId &&
            NetworkCodec.encode(it).size<=AdmissionV2.MAX_CERTIFICATE_BYTES) }
        control.evidenceEventId?.let { require(GroupIds.valid(it)) }
        control.baselineProposalV1?.let {require(it.groupId==control.groupId &&
            NetworkCodec.encode(it).size<=GroupAuthorityBaselineV1.MAX_PROPOSAL_BYTES)}
        control.baselineApprovalV1?.let {require(it.groupId==control.groupId &&
            NetworkCodec.encode(it).size<=GroupAuthorityBaselineV1.MAX_APPROVAL_BYTES)}
        control.baselineCertificateV1?.let {require(it.proposal.groupId==control.groupId &&
            NetworkCodec.encode(it).size<=GroupAuthorityBaselineV1.MAX_CERTIFICATE_BYTES)}
        control.governanceProposalV1?.let {require(it.groupId==control.groupId &&
            NetworkCodec.encode(it).size<=GroupGovernanceV1.MAX_PROPOSAL_BYTES)}
        control.governanceAckV1?.let {require(it.groupId==control.groupId &&
            NetworkCodec.encode(it).size<=GroupGovernanceV1.MAX_ACK_BYTES)}
        control.governanceCommitV1?.let {require(it.proposal.groupId==control.groupId &&
            NetworkCodec.encode(it).size<=GroupGovernanceV1.MAX_COMMIT_BYTES)}
        control.governanceInstalledV1?.let {require(it.groupId==control.groupId &&
            NetworkCodec.encode(it).size<=GroupGovernanceV1.MAX_ACK_BYTES)}
        control.governanceReadyV1?.let {require(it.commit.proposal.groupId==control.groupId &&
            NetworkCodec.encode(it).size<=GroupGovernanceV1.MAX_READY_BYTES)}
        control.governanceEntryV1?.let {require(it.groupId==control.groupId &&
            NetworkCodec.encode(it).size<=GroupGovernanceV1.MAX_ENTRY_BYTES)}
        control.governanceEntryAckV1?.let {require(it.groupId==control.groupId &&
            NetworkCodec.encode(it).size<=GroupGovernanceV1.MAX_ACK_BYTES)}
        control.governanceResyncRequestV1?.let {require(it.version==1 && it.groupId==control.groupId &&
            it.activationDigest.size==32 && it.sequence in 0..GroupGovernanceJournalV1.MAX_ENTRIES.toLong() &&
            it.headDigest.size==32 && it.stateDigest.size==32 && GroupIds.valid(it.requesterMemberId) &&
            it.stateRevision in 1..GroupStatements.MAX_EVENTS.toLong())}
        control.governanceResyncResponseV1?.let {require(it.version==1 && it.groupId==control.groupId &&
            it.activationDigest.size==32 && it.requesterHeadDigest.size==32 &&
            it.startHeadDigest.size==32 && it.endHeadDigest.size==32 &&
            it.entries.size==1 && it.startSequence==it.requesterSequence+1 &&
            it.endSequence==it.startSequence && it.entries.single().sequence==it.startSequence &&
            it.endSequence in 1..GroupGovernanceJournalV1.MAX_ENTRIES.toLong() &&
            it.entries.single().groupId==control.groupId &&
            it.entries.single().previousHeadDigest.contentEquals(it.startHeadDigest) &&
            it.entries.single().activationDigest.contentEquals(it.activationDigest) &&
            GroupGovernanceV1.entryDigest(it.entries.single()).contentEquals(it.endHeadDigest))}
        control.governancePolicyEntryV1?.let {require(it.version==1 && it.groupId==control.groupId &&
            NetworkCodec.encode(it).size<=GroupGovernancePolicyRulesV1.MAX_ENTRY_BYTES)}
        control.governanceResyncResponseV2?.let {require(it.version==2 && it.groupId==control.groupId &&
            it.activationDigest.size==32 && it.requesterHeadDigest.size==32 &&
            it.startHeadDigest.size==32 && it.endHeadDigest.size==32 &&
            it.startSequence==it.requesterSequence+1 && it.endSequence==it.startSequence &&
            it.endSequence in 1..GroupGovernanceJournalV1.MAX_ENTRIES.toLong() &&
            (it.membershipEntry==null)!=(it.policyEntry==null) &&
            (it.membershipEntry?.groupId ?: it.policyEntry?.groupId)==control.groupId &&
            (it.membershipEntry?.sequence ?: it.policyEntry?.sequence)==it.startSequence &&
            (it.membershipEntry?.previousHeadDigest ?: it.policyEntry?.previousHeadDigest)
                ?.contentEquals(it.startHeadDigest)==true &&
            (it.membershipEntry?.activationDigest ?: it.policyEntry?.activationDigest)
                ?.contentEquals(it.activationDigest)==true &&
            (it.membershipEntry?.let(GroupGovernanceV1::entryDigest) ?:
                it.policyEntry?.let(GroupGovernancePolicyRulesV1::entryDigest))
                ?.contentEquals(it.endHeadDigest)==true)}
        control.governedAdmissionBindingV1?.let {require(it.version==1 && it.groupId==control.groupId &&
            NetworkCodec.encode(it).size<=GroupGovernedAdmissionV1.MAX_BINDING_BYTES)}
        control.governanceCheckpointV1?.let {require(it.version==1 && it.groupId==control.groupId &&
            NetworkCodec.encode(it).size<=GroupGovernedAdmissionV1.MAX_CHECKPOINT_BYTES)}
        control.governancePolicyCheckpointV1?.let {require(it.version==1 && it.groupId==control.groupId &&
            NetworkCodec.encode(it).size<=GroupGovernancePolicyCheckpointRulesV1.MAX_BYTES &&
            control.kind in setOf(GroupControlKind.GOVERNANCE_CHECKPOINT_SIGN_REQUEST_V1,
                GroupControlKind.GOVERNANCE_CHECKPOINT_SIGN_RESPONSE_V1,
                GroupControlKind.GOVERNANCE_BOOTSTRAP_V1))}
        control.governedChangeProposalV1?.let {require(it.version==1 && it.groupId==control.groupId &&
            it.activationDigest.size==32 && it.parentHeadDigest.size==32 &&
            it.parentStateDigest.size==32 && it.parentSequence in 0..GroupGovernanceJournalV1.MAX_ENTRIES.toLong() &&
            it.transition.next.groupId==control.groupId &&
            NetworkCodec.encode(it).size<=GroupControlCodec.MAX_BYTES)}
        control.ownershipRequestV1?.let {require(it.version==1 && it.groupId==control.groupId &&
            it.activationDigest.size==32 && it.parentHeadDigest.size==32 &&
            it.parentStateDigest.size==32 && it.parentSequence in 0..GroupGovernanceJournalV1.MAX_ENTRIES.toLong() &&
            it.transition.change.action==GroupAction.TRANSFER_OWNER &&
            it.transition.next.groupId==control.groupId && NetworkCodec.encode(it).size<=MAX_BYTES)}
        control.ownershipDecisionV1?.let {require(it.version==1 &&
            it.request.groupId==control.groupId &&
            (if(it.accepted) it.targetSignature.size in 8..80 else it.targetSignature.isEmpty()) &&
            NetworkCodec.encode(it).size<=MAX_BYTES)}
        control.inviteDelegationV1?.let {require(it.groupId==control.groupId &&
            GroupInviteDelegationRulesV1.valid(it))}
        val governanceCount=listOf(control.governanceProposalV1,control.governanceAckV1,
            control.governanceCommitV1,control.governanceInstalledV1,control.governanceReadyV1,
            control.governanceEntryV1,control.governanceEntryAckV1,
            control.governanceResyncRequestV1,control.governanceResyncResponseV1,
            control.governanceCheckpointV1,control.governancePolicyEntryV1,
            control.governanceResyncResponseV2,control.governedChangeProposalV1,
            control.ownershipRequestV1,control.ownershipDecisionV1,
            control.inviteDelegationV1).count {it!=null}
        val governanceKind=control.kind.name.startsWith("GOVERNANCE_")
        if(control.kind==GroupControlKind.GOVERNANCE_CAPABILITY_ECHO) require(governanceCount==0)
        else if(control.kind==GroupControlKind.GOVERNANCE_BOOTSTRAP_V1) require(governanceCount==2)
        else if(governanceKind) require(governanceCount==1)
        else require(governanceCount==0)
        val baselineAbsent=control.baselineProposalV1==null && control.baselineApprovalV1==null &&
            control.baselineCertificateV1==null
        if(control.kind !in setOf(GroupControlKind.BASELINE_V1_PROPOSAL,
                GroupControlKind.BASELINE_V1_APPROVAL,GroupControlKind.BASELINE_V1_CERTIFICATE))
            require(baselineAbsent)
        val v2Absent=control.proposalV2==null && control.approvalV2==null &&
            control.certificateV2==null && control.evidenceEventId==null
        when (control.kind) {
            GroupControlKind.INVITE_EXPIRED -> require(v2Absent && control.inviteId!=null && control.state==null &&
                control.genesis==null && control.admission==null && control.invite==null &&
                control.transition==null && control.chain.isEmpty() && control.fromRevision==null &&
                control.fromDigest==null && control.headRevision==null)
            GroupControlKind.ADMISSION_REQUEST, GroupControlKind.ADMISSION_RESPONSE -> require(v2Absent &&
                control.state != null && control.admission != null && control.invite != null &&
                control.inviteId == control.invite.inviteId && control.genesis == null &&
                control.transition == null && control.chain.isEmpty() && control.fromRevision == null &&
                control.fromDigest == null && control.headRevision == null &&
                control.admission.inviteId == control.inviteId &&
                GroupStatements.digest(control.admission.state).contentEquals(GroupStatements.digest(control.state)) &&
                GroupStatements.digestMember(control.admission.target).contentEquals(
                    GroupStatements.digestMember(control.invite.target)) &&
                runCatching {GroupStatements.validateOfferShape(control.invite)}.isSuccess)
            GroupControlKind.INVITE -> require(control.approvalV2==null && control.proposalV2==null &&
                control.evidenceEventId==null && control.invite != null && control.admission != null &&
                control.state != null && control.genesis == null && control.inviteId == control.invite.inviteId &&
                control.admission.inviteId == control.inviteId &&
                GroupStatements.digestMember(control.admission.target).contentEquals(
                    GroupStatements.digestMember(control.invite.target)) &&
                GroupStatements.digest(control.admission.state).contentEquals(GroupStatements.digest(control.state)) &&
                control.invite.parentDigest.contentEquals(GroupStatements.digest(control.state)) &&
                control.transition == null && control.fromRevision == null && control.fromDigest == null &&
                control.headRevision == null && control.chain.isEmpty() &&
                (control.certificateV2==null ||
                    (AdmissionV2.verifyCertificate(control.certificateV2,control.state) &&
                        control.certificateV2.proposal.inviteId==control.inviteId &&
                        control.certificateV2.proposal.candidateDigest.contentEquals(
                            GroupStatements.digestMember(control.invite.target)))) &&
                runCatching {GroupStatements.validateOfferShape(control.invite)}.isSuccess &&
                (control.governedAdmissionBindingV1==null ||
                    (control.certificateV2!=null &&
                        GroupGovernedAdmissionV1.verifyBinding(control.governedAdmissionBindingV1,
                            control.state,control.certificateV2))))
            GroupControlKind.ACCEPT -> require(v2Absent && control.invite != null && control.inviteId == control.invite.inviteId &&
                control.state == null && control.genesis == null && control.admission == null &&
                control.transition == null && control.fromRevision == null && control.fromDigest == null &&
                control.headRevision == null && control.chain.isEmpty() &&
                runCatching {GroupStatements.decodeInvite(NetworkCodec.encode(control.invite))}.isSuccess)
            GroupControlKind.STATE_UPDATE -> require(control.proposalV2==null && control.approvalV2==null &&
                control.evidenceEventId==null && control.transition != null && control.state == null &&
                control.genesis == null && control.admission == null && control.invite == null &&
                control.inviteId == null && control.fromRevision == null && control.fromDigest == null &&
                control.headRevision == null && control.chain.isEmpty() &&
                (control.certificateV2==null ||
                    (control.transition.change.action==GroupAction.ADD &&
                        control.certificateV2.proposal.eventId==control.transition.change.eventId)))
            GroupControlKind.RESYNC_REQUEST -> require(v2Absent && control.fromRevision != null && control.fromRevision >= 1 &&
                control.fromDigest != null && control.state == null && control.genesis == null && control.admission == null &&
                control.invite == null && control.inviteId == null && control.transition == null &&
                control.headRevision == null && control.chain.isEmpty())
            GroupControlKind.RESYNC_RESPONSE -> require(v2Absent && control.state != null && control.chain.isNotEmpty() &&
                control.genesis == null && control.admission == null && control.invite == null &&
                control.inviteId == null && control.transition == null && control.fromRevision == null &&
                control.fromDigest == null && control.headRevision != null &&
                control.headRevision >= control.state.revision &&
                GroupStatements.digest(control.chain.last().next).contentEquals(GroupStatements.digest(control.state)))
            GroupControlKind.ADMISSION_V2_PROPOSAL -> require(control.proposalV2!=null &&
                control.inviteId==control.proposalV2.inviteId && control.approvalV2==null &&
                control.certificateV2==null && control.evidenceEventId==null &&
                control.state==null && control.genesis==null && control.admission==null &&
                control.invite==null && control.transition==null && control.chain.isEmpty() &&
                control.fromRevision==null && control.fromDigest==null && control.headRevision==null)
            GroupControlKind.ADMISSION_V2_APPROVAL -> require(control.approvalV2!=null &&
                control.inviteId==control.approvalV2.inviteId && control.proposalV2==null &&
                control.certificateV2==null && control.evidenceEventId==null &&
                control.state==null && control.genesis==null && control.admission==null &&
                control.invite==null && control.transition==null && control.chain.isEmpty() &&
                control.fromRevision==null && control.fromDigest==null && control.headRevision==null)
            GroupControlKind.ADMISSION_V2_EVIDENCE_REQUEST -> require(control.evidenceEventId!=null &&
                control.inviteId==null && control.proposalV2==null && control.approvalV2==null &&
                control.certificateV2==null && control.state==null && control.genesis==null &&
                control.admission==null && control.invite==null && control.transition==null &&
                control.chain.isEmpty() && control.fromRevision==null && control.fromDigest==null &&
                control.headRevision==null)
            GroupControlKind.ADMISSION_V2_EVIDENCE_RESPONSE -> require(control.evidenceEventId!=null &&
                control.certificateV2!=null && control.proposalV2==null && control.approvalV2==null &&
                control.inviteId==control.certificateV2.proposal.inviteId && control.state==null &&
                control.genesis==null && control.admission==null && control.invite==null &&
                control.transition==null && control.chain.isEmpty() && control.fromRevision==null &&
                control.fromDigest==null && control.headRevision==null)
            GroupControlKind.BASELINE_V1_PROPOSAL,GroupControlKind.BASELINE_V1_APPROVAL,
            GroupControlKind.BASELINE_V1_CERTIFICATE -> {
                require(v2Absent && control.inviteId==null && control.state==null &&
                    control.genesis==null && control.admission==null && control.invite==null &&
                    control.transition==null && control.chain.isEmpty() &&
                    control.fromRevision==null && control.fromDigest==null && control.headRevision==null)
                require(when(control.kind) {
                    GroupControlKind.BASELINE_V1_PROPOSAL -> control.baselineProposalV1!=null &&
                        control.baselineApprovalV1==null && control.baselineCertificateV1==null
                    GroupControlKind.BASELINE_V1_APPROVAL -> control.baselineApprovalV1!=null &&
                        control.baselineProposalV1==null && control.baselineCertificateV1==null
                    GroupControlKind.BASELINE_V1_CERTIFICATE -> control.baselineCertificateV1!=null &&
                        control.baselineProposalV1==null && control.baselineApprovalV1==null
                    else -> false
                })
            }
            GroupControlKind.GOVERNANCE_CHECKPOINT_SIGN_REQUEST_V1 -> require(
                control.governanceCheckpointV1!=null && control.governedAdmissionBindingV1!=null &&
                control.certificateV2!=null && control.admission!=null && control.invite!=null &&
                control.inviteId==control.invite.inviteId && control.state==null &&
                control.genesis==null && control.transition==null && control.chain.isEmpty() &&
                control.fromRevision==null && control.fromDigest==null && control.headRevision==null &&
                GroupGovernedAdmissionV1.verifyCheckpoint(control.governanceCheckpointV1,
                    control.admission.state,control.admission,control.certificateV2,
                    control.governedAdmissionBindingV1,requireOwner=false))
            GroupControlKind.GOVERNANCE_CHECKPOINT_SIGN_RESPONSE_V1 -> require(
                v2Absent && control.governanceCheckpointV1!=null &&
                control.governedAdmissionBindingV1==null && control.inviteId==null &&
                control.state==null && control.genesis==null && control.admission==null &&
                control.invite==null && control.transition==null && control.chain.isEmpty() &&
                control.fromRevision==null && control.fromDigest==null && control.headRevision==null)
            GroupControlKind.GOVERNANCE_BOOTSTRAP_V1 -> require(v2Absent &&
                control.governanceCheckpointV1!=null && control.governanceEntryV1!=null &&
                control.governedAdmissionBindingV1==null && control.inviteId==null &&
                control.state==null && control.genesis==null && control.admission==null &&
                control.invite==null && control.transition==null && control.chain.isEmpty() &&
                control.fromRevision==null && control.fromDigest==null && control.headRevision==null)
            GroupControlKind.GOVERNANCE_OWNER_SIGN_REQUEST,
            GroupControlKind.GOVERNANCE_OWNER_SIGN_RESPONSE,
            GroupControlKind.GOVERNANCE_ACTIVATION_PROPOSAL,
            GroupControlKind.GOVERNANCE_ACTIVATION_ACK,
            GroupControlKind.GOVERNANCE_ACTIVATION_COMMIT,
            GroupControlKind.GOVERNANCE_ACTIVATION_INSTALLED_ACK,
            GroupControlKind.GOVERNANCE_ACTIVATION_READY,
            GroupControlKind.GOVERNANCE_ENTRY,
            GroupControlKind.GOVERNANCE_ENTRY_APPLIED_ACK,
            GroupControlKind.GOVERNANCE_RESYNC_REQUEST_V1,
            GroupControlKind.GOVERNANCE_RESYNC_RESPONSE_V1,
            GroupControlKind.GOVERNANCE_RESYNC_RESPONSE_V2,
            GroupControlKind.GOVERNANCE_POLICY_ENTRY_V1,
            GroupControlKind.GOVERNANCE_POLICY_ENTRY_ACK_V1,
            GroupControlKind.GOVERNANCE_POLICY_PROPOSAL_V1,
            GroupControlKind.GOVERNANCE_CHANGE_PROPOSAL_V1,
            GroupControlKind.GOVERNANCE_CHANGE_SIGN_REQUEST_V1,
            GroupControlKind.GOVERNANCE_CHANGE_SIGN_RESPONSE_V1,
            GroupControlKind.GOVERNANCE_TRANSFER_REQUEST_V1,
            GroupControlKind.GOVERNANCE_TRANSFER_DECISION_V1,
            GroupControlKind.GOVERNANCE_INVITE_DELEGATION_V1,
            GroupControlKind.GOVERNANCE_CAPABILITY_ECHO -> {
                require(v2Absent && baselineAbsent && control.inviteId==null && control.state==null &&
                    control.genesis==null && control.admission==null && control.invite==null &&
                    control.transition==null && control.chain.isEmpty() && control.fromRevision==null &&
                    control.fromDigest==null && control.headRevision==null)
                require(when(control.kind) {
                    GroupControlKind.GOVERNANCE_OWNER_SIGN_REQUEST,
                    GroupControlKind.GOVERNANCE_OWNER_SIGN_RESPONSE,
                    GroupControlKind.GOVERNANCE_ACTIVATION_PROPOSAL -> control.governanceProposalV1!=null
                    GroupControlKind.GOVERNANCE_ACTIVATION_ACK -> control.governanceAckV1!=null
                    GroupControlKind.GOVERNANCE_ACTIVATION_COMMIT -> control.governanceCommitV1!=null
                    GroupControlKind.GOVERNANCE_ACTIVATION_INSTALLED_ACK -> control.governanceInstalledV1!=null
                    GroupControlKind.GOVERNANCE_ACTIVATION_READY -> control.governanceReadyV1!=null
                    GroupControlKind.GOVERNANCE_ENTRY -> control.governanceEntryV1!=null
                    GroupControlKind.GOVERNANCE_ENTRY_APPLIED_ACK -> control.governanceEntryAckV1!=null
                    GroupControlKind.GOVERNANCE_RESYNC_REQUEST_V1 -> control.governanceResyncRequestV1!=null
                    GroupControlKind.GOVERNANCE_RESYNC_RESPONSE_V1 -> control.governanceResyncResponseV1!=null
                    GroupControlKind.GOVERNANCE_RESYNC_RESPONSE_V2 -> control.governanceResyncResponseV2!=null
                    GroupControlKind.GOVERNANCE_POLICY_ENTRY_V1,
                    GroupControlKind.GOVERNANCE_POLICY_PROPOSAL_V1 -> control.governancePolicyEntryV1!=null
                    GroupControlKind.GOVERNANCE_POLICY_ENTRY_ACK_V1 -> control.governanceEntryAckV1!=null
                    GroupControlKind.GOVERNANCE_CHANGE_PROPOSAL_V1 -> control.governedChangeProposalV1!=null
                    GroupControlKind.GOVERNANCE_CHANGE_SIGN_REQUEST_V1,
                    GroupControlKind.GOVERNANCE_CHANGE_SIGN_RESPONSE_V1 -> control.governanceEntryV1!=null
                    GroupControlKind.GOVERNANCE_TRANSFER_REQUEST_V1 -> control.ownershipRequestV1!=null
                    GroupControlKind.GOVERNANCE_TRANSFER_DECISION_V1 -> control.ownershipDecisionV1!=null
                    GroupControlKind.GOVERNANCE_INVITE_DELEGATION_V1 -> control.inviteDelegationV1!=null
                    GroupControlKind.GOVERNANCE_CAPABILITY_ECHO -> governanceCount==0
                    else -> false
                })
            }
        }
    }
}
