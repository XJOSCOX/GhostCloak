package org.ghostcloak.messaging

import org.ghostcloak.identity.RandomIdentifiers
import org.ghostcloak.protocol.DeviceAuth
import org.ghostcloak.protocol.NetworkCodec
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec

class GroupGovernanceV1Test {
    private data class Person(val member:GroupMember,val key:KeyPair)
    private fun key()=KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()
    private fun sign(key:KeyPair,bytes:ByteArray)=Signature.getInstance("SHA256withECDSA").run {
        initSign(key.private);update(bytes);sign()
    }
    private fun fixture(count:Int):Pair<GroupState,List<Person>> {
        val people=List(count) {
            val pair=key()
            Person(GroupMember(GroupIds.create(),RandomIdentifiers.create(),RandomIdentifiers.create(),
                pair.public.encoded,DeviceAuth.digest(byteArrayOf(it.toByte())),
                GroupRole.MEMBER,1),pair)
        }.sortedBy {it.member.memberId}
        val members=people.mapIndexed {index,p ->p.member.copy(
            role=if(index==0) GroupRole.OWNER else GroupRole.MEMBER,
            joinedEpoch=(index+1).toLong())}
        val state=GroupState(GroupIds.create(),count.toLong(),count.toLong(),
            members.first().memberId,members.first().memberId,members,ByteArray(32))
        return state to people
    }
    private fun signedProposal(state:GroupState,people:List<Person>,baseline:ByteArray):
        GroupGovernanceActivationProposalV1 {
        val unsigned=GroupGovernanceV1.unsignedProposal(state,baseline,GroupIds.create())
        return unsigned.copy(ownerSignature=sign(people.first().key,
            GroupGovernanceV1.proposalStatement(unsigned,true)),
            coordinatorSignature=sign(people.first().key,
                GroupGovernanceV1.proposalStatement(unsigned,false)))
    }
    private fun acks(proposal:GroupGovernanceActivationProposalV1,people:List<Person>)=people.map {p ->
        val unsigned=GroupGovernanceV1.unsignedAck(proposal,p.member.memberId)
        unsigned.copy(signature=sign(p.key,GroupGovernanceV1.ackStatement(unsigned)))
    }
    private fun installed(commit:GroupGovernanceActivationCommitV1,people:List<Person>)=people.map {p ->
        val unsigned=GroupGovernanceV1.unsignedInstalled(commit,p.member.memberId)
        unsigned.copy(signature=sign(p.key,GroupGovernanceV1.installedStatement(unsigned)))
    }
    @Test fun exactAllMemberCommitAndReadyRejectMissingForgedAndStaleProof() {
        for(count in listOf(2,3,5)) {
            val (state,people)=fixture(count)
            val baseline=DeviceAuth.digest(byteArrayOf(1,2,3))
            val proposal=signedProposal(state,people,baseline)
            assertTrue(GroupGovernanceV1.verifyProposal(proposal,state,baseline))
            assertFalse(GroupGovernanceV1.verifyProposal(proposal.copy(ownerSignature=ByteArray(64)),state,baseline))
            assertFalse(GroupGovernanceV1.verifyProposal(proposal,state.copy(revision=state.revision+1),baseline))
            val approvals=acks(proposal,people)
            val commit=GroupGovernanceV1.commit(proposal,approvals.reversed())
            assertTrue(GroupGovernanceV1.verifyCommit(commit,state,baseline))
            assertFalse(GroupGovernanceV1.verifyCommit(GroupGovernanceV1.commit(proposal,
                approvals.dropLast(1)),state,baseline))
            assertFalse(GroupGovernanceV1.verifyCommit(GroupGovernanceV1.commit(proposal,
                approvals+approvals.first()),state,baseline))
            assertFalse(GroupGovernanceV1.verifyCommit(commit.copy(digest=ByteArray(32)),state,baseline))
            val proofs=installed(commit,people)
            val ready=GroupGovernanceV1.ready(commit,proofs.reversed())
            assertTrue(GroupGovernanceV1.verifyReady(ready,state,baseline))
            assertFalse(GroupGovernanceV1.verifyReady(GroupGovernanceV1.ready(commit,
                proofs.dropLast(1)),state,baseline))
            assertFalse(GroupGovernanceV1.verifyReady(ready.copy(digest=ByteArray(32)),state,baseline))
            assertFalse(GroupGovernanceV1.verifyReady(ready,state,ByteArray(32)))
            val controls=listOf(
                GroupControl(kind=GroupControlKind.GOVERNANCE_OWNER_SIGN_REQUEST,
                    groupId=state.groupId,governanceProposalV1=proposal.copy(ownerSignature=byteArrayOf())),
                GroupControl(kind=GroupControlKind.GOVERNANCE_OWNER_SIGN_RESPONSE,
                    groupId=state.groupId,governanceProposalV1=proposal),
                GroupControl(kind=GroupControlKind.GOVERNANCE_ACTIVATION_PROPOSAL,
                    groupId=state.groupId,governanceProposalV1=proposal),
                GroupControl(kind=GroupControlKind.GOVERNANCE_ACTIVATION_ACK,
                    groupId=state.groupId,governanceAckV1=approvals.first()),
                GroupControl(kind=GroupControlKind.GOVERNANCE_ACTIVATION_COMMIT,
                    groupId=state.groupId,governanceCommitV1=commit),
                GroupControl(kind=GroupControlKind.GOVERNANCE_ACTIVATION_INSTALLED_ACK,
                    groupId=state.groupId,governanceInstalledV1=proofs.first()),
                GroupControl(kind=GroupControlKind.GOVERNANCE_ACTIVATION_READY,
                    groupId=state.groupId,governanceReadyV1=ready))
            controls.forEach {control ->
                val encoded=GroupControlCodec.encode(control)
                val frame=ConversationPayload.encodeGroup(control)
                assertTrue(encoded.size<GroupControlCodec.MAX_BYTES-1024)
                assertTrue(frame.size<=16_384-1024)
                assertEquals(control.kind,ConversationPayload.decode(frame).groupControl!!.kind)
                println("A6_2_SIZE members=$count kind=${control.kind} object=${when(control.kind) {
                    GroupControlKind.GOVERNANCE_OWNER_SIGN_REQUEST ->
                        NetworkCodec.encode(proposal.copy(ownerSignature=byteArrayOf())).size
                    GroupControlKind.GOVERNANCE_OWNER_SIGN_RESPONSE -> NetworkCodec.encode(proposal).size
                    GroupControlKind.GOVERNANCE_ACTIVATION_PROPOSAL -> NetworkCodec.encode(proposal).size
                    GroupControlKind.GOVERNANCE_ACTIVATION_ACK -> NetworkCodec.encode(approvals.first()).size
                    GroupControlKind.GOVERNANCE_ACTIVATION_COMMIT -> NetworkCodec.encode(commit).size
                    GroupControlKind.GOVERNANCE_ACTIVATION_INSTALLED_ACK -> NetworkCodec.encode(proofs.first()).size
                    else -> NetworkCodec.encode(ready).size
                }} control=${encoded.size} frame=${frame.size}")
            }
        }
    }
    @Test fun governanceEntryBindsCurrentHeadSignersAndWrappedTransition() {
        val (state,people)=fixture(2)
        val activation=DeviceAuth.digest(byteArrayOf(9))
        val head=GovernanceHeadFoundationV1(groupId=state.groupId,activationDigest=activation,
            sequence=0,headDigest=activation,stateRevision=state.revision,
            stateDigest=GroupStatements.digest(state))
        val change=GroupChange(GroupIds.create(),GroupAction.TIMER,state.ownerId,newTimer=30)
        val next=GroupRules.derive(state,change,emptySet(),GroupTrustedPeer {true})
        val transition=GroupTransition(change,next,
            sign(people.first().key,GroupStatements.actor(state,change,next)),
            sign(people.first().key,GroupStatements.coordinator(state,change,next)))
        val unsigned=GroupGovernanceEntryV1(groupId=state.groupId,activationDigest=activation,
            sequence=1,previousHeadDigest=head.headDigest,eventId=change.eventId,
            preRevision=state.revision,preDigest=GroupStatements.digest(state),
            actorId=change.actorId,action=change.action,
            transitionDigest=DeviceAuth.digest(NetworkCodec.encode(transition)),
            postRevision=next.revision,postDigest=GroupStatements.digest(next),transition=transition,
            actorSignature=byteArrayOf(),coordinatorSignature=byteArrayOf())
        val entry=unsigned.copy(actorSignature=sign(people.first().key,
            GroupGovernanceV1.actorStatement(unsigned)),coordinatorSignature=sign(people.first().key,
            GroupGovernanceV1.coordinatorStatement(unsigned)))
        assertTrue(GroupGovernanceV1.verifyEntry(entry,state,head))
        assertFalse(GroupGovernanceV1.verifyEntry(entry.copy(previousHeadDigest=ByteArray(32)),state,head))
        assertFalse(GroupGovernanceV1.verifyEntry(entry.copy(sequence=2),state,head))
        assertFalse(GroupGovernanceV1.verifyEntry(entry.copy(transitionDigest=ByteArray(32)),state,head))
        val unsignedAck=GroupGovernanceV1.unsignedApplied(entry,people[1].member.memberId)
        val ack=unsignedAck.copy(signature=sign(people[1].key,
            GroupGovernanceV1.appliedStatement(unsignedAck)))
        assertTrue(GroupGovernanceV1.verifyApplied(ack,entry))
        assertFalse(GroupGovernanceV1.verifyApplied(ack.copy(memberId=people[0].member.memberId),entry))
        val control=GroupControl(kind=GroupControlKind.GOVERNANCE_ENTRY,
            groupId=state.groupId,governanceEntryV1=entry)
        val bytes=GroupControlCodec.encode(control)
        assertTrue(bytes.size<GroupControlCodec.MAX_BYTES-1024)
        assertTrue(ConversationPayload.encodeGroup(control).size<=16_384-1024)
        val appliedControl=GroupControl(kind=GroupControlKind.GOVERNANCE_ENTRY_APPLIED_ACK,
            groupId=state.groupId,governanceEntryAckV1=ack)
        assertTrue(GroupControlCodec.encode(appliedControl).size<GroupControlCodec.MAX_BYTES-1024)
        assertTrue(ConversationPayload.encodeGroup(appliedControl).size<=16_384-1024)
        println("A6_2_SIZE entry=${NetworkCodec.encode(entry).size} control=${bytes.size} frame=${ConversationPayload.encodeGroup(control).size} ack=${NetworkCodec.encode(ack).size}")
        println("A6_2_SIZE entry_applied_ack_control=${GroupControlCodec.encode(appliedControl).size} frame=${ConversationPayload.encodeGroup(appliedControl).size}")
    }
    @Test fun delegatedCoordinatorCannotProduceOwnersActivationSignature() {
        val (plain,people)=fixture(3)
        val coordinator=plain.members[1].copy(role=GroupRole.ADMIN)
        val state=plain.copy(coordinatorId=coordinator.memberId,
            members=plain.members.map {if(it.memberId==coordinator.memberId) coordinator else it})
        val baseline=DeviceAuth.digest(byteArrayOf(44))
        val unsigned=GroupGovernanceV1.unsignedProposal(state,baseline,GroupIds.create())
        val coordinatorSigned=unsigned.copy(coordinatorSignature=sign(people[1].key,
            GroupGovernanceV1.proposalStatement(unsigned,false)))
        assertTrue(GroupGovernanceV1.verifyProposal(coordinatorSigned,state,baseline,false))
        assertFalse(GroupGovernanceV1.verifyProposal(coordinatorSigned,state,baseline))
        val forged=coordinatorSigned.copy(ownerSignature=sign(people[1].key,
            GroupGovernanceV1.proposalStatement(unsigned,true)))
        assertFalse(GroupGovernanceV1.verifyProposal(forged,state,baseline))
        val valid=coordinatorSigned.copy(ownerSignature=sign(people[0].key,
            GroupGovernanceV1.proposalStatement(unsigned,true)))
        assertTrue(GroupGovernanceV1.verifyProposal(valid,state,baseline))
    }
    @Test fun capabilityIsAuthenticatedPaddingAndOlderControlReadsNormally() {
        val control=GroupControl(kind=GroupControlKind.RESYNC_REQUEST,groupId=GroupIds.create(),
            fromRevision=1,fromDigest=ByteArray(32))
        val frame=ConversationPayload.encodeGroup(control)
        assertTrue(ConversationPayload.decode(frame).supportsGovernanceV1)
        val offset=16+GroupControlCodec.encode(control).size+"GC/baseline-v1!".length
        val legacy=frame.copyOf().also {it[offset]=0}
        assertFalse(ConversationPayload.decode(legacy).supportsGovernanceV1)
        assertEquals(control.kind,ConversationPayload.decode(legacy).groupControl!!.kind)
        val echo=GroupControl(kind=GroupControlKind.GOVERNANCE_CAPABILITY_ECHO,
            groupId=control.groupId)
        assertTrue(ConversationPayload.decode(ConversationPayload.encodeGroup(echo)).supportsGovernanceV1)
        assertTrue(runCatching {GroupControlCodec.encode(control.copy(
            governanceEntryAckV1=GroupGovernanceEntryAppliedAckV1(groupId=control.groupId,
                activationDigest=ByteArray(32),sequence=1,entryDigest=ByteArray(32),
                memberId=GroupIds.create(),signature=ByteArray(64))))}.isFailure)
    }
}
