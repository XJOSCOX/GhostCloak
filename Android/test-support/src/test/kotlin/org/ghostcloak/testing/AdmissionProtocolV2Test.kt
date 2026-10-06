package org.ghostcloak.testing

import org.ghostcloak.messaging.*
import org.ghostcloak.crypto.EndpointStorageFailure
import org.ghostcloak.identity.RandomIdentifiers
import org.ghostcloak.protocol.DeviceAuth
import org.ghostcloak.protocol.NetworkCodec
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec

class AdmissionProtocolV2Test {
    private fun key():KeyPair=KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()
    private fun sign(key:KeyPair,body:ByteArray):ByteArray=Signature.getInstance("SHA256withECDSA").run {
        initSign(key.private);update(body);sign()
    }
    private data class Person(val member:GroupMember,val key:KeyPair)
    private fun person(epoch:Long=1):Person {
        val key=key()
        return Person(GroupMember(GroupIds.create(),RandomIdentifiers.create(),RandomIdentifiers.create(),
            key.public.encoded,ByteArray(32).also {java.security.SecureRandom().nextBytes(it)},
            GroupRole.MEMBER,epoch),key)
    }
    private fun fixture(n:Int):Triple<GroupState,List<Person>,AdmissionProposalV2> {
        val people=List(n) {person()}.sortedBy {it.member.memberId}
        val owner=people.first()
        val members=people.map {it.member.copy(role=if(it==owner) GroupRole.OWNER else GroupRole.MEMBER)}
        val parent=GroupState(GroupIds.create(),n.toLong(),n.toLong(),owner.member.memberId,
            owner.member.memberId,members,ByteArray(32))
        val candidate=person(n+1L).member
        val unsigned=AdmissionProposalV2(groupId=parent.groupId,parent=parent,parentDigest=GroupStatements.digest(parent),
            inviteId=GroupIds.create(),inviterId=owner.member.memberId,candidate=candidate,
            candidateDigest=AdmissionV2.candidateDigest(candidate),eventId=GroupIds.create(),
            inviterSignature=byteArrayOf())
        val signed=unsigned.copy(inviterSignature=sign(owner.key,AdmissionV2.proposalStatement(unsigned)))
        return Triple(parent,people,signed)
    }
    private fun approvals(people:List<Person>,proposal:AdmissionProposalV2)=people.map {p ->
        val unsigned=AdmissionV2.approval(proposal,p.member.memberId,byteArrayOf())
        unsigned.copy(signature=sign(p.key,AdmissionV2.approvalStatement(unsigned)))
    }
    @Test fun exactRosterCertificateRequiresEverySignatureAndCanonicalOrder() {
        for(n in 2..4) {
            val (parent,people,proposal)=fixture(n)
            assertTrue(AdmissionV2.verifyProposal(proposal,parent))
            val all=approvals(people,proposal)
            val cert=AdmissionV2.certificate(proposal,all.reversed())
            assertTrue(AdmissionV2.verifyCertificate(cert,parent))
            assertArrayEquals(cert.digest,AdmissionV2.certificate(proposal,all).digest)
            assertFalse(AdmissionV2.verifyCertificate(AdmissionV2.certificate(proposal,all.dropLast(1)),parent))
            assertFalse(AdmissionV2.verifyCertificate(AdmissionV2.certificate(proposal,all+all.first()),parent))
            assertFalse(AdmissionV2.verifyCertificate(AdmissionV2.certificate(proposal,
                all.dropLast(1)+all.last().copy(signature=ByteArray(all.last().signature.size))),parent))
            assertFalse(AdmissionV2.verifyCertificate(AdmissionV2.certificate(proposal,
                all.dropLast(1)+all.last().copy(approverId=GroupIds.create())),parent))
            assertFalse(AdmissionV2.verifyCertificate(AdmissionV2.certificate(proposal,
                all.dropLast(1)+all.last().copy(inviteId=GroupIds.create())),parent))
            val changed=proposal.copy(candidate=proposal.candidate.copy(deviceId=RandomIdentifiers.create()))
            assertFalse(AdmissionV2.verifyProposal(changed,parent))
            assertFalse(AdmissionV2.verifyProposal(proposal.copy(candidate=proposal.candidate.copy(
                authPublicKey=person().member.authPublicKey)),parent))
            assertFalse(AdmissionV2.verifyProposal(proposal.copy(candidate=proposal.candidate.copy(
                signalIdentityDigest=ByteArray(32))),parent))
            assertFalse(AdmissionV2.verifyProposal(proposal.copy(groupId=GroupIds.create()),parent))
            assertFalse(AdmissionV2.verifyCertificate(cert,parent.copy(revision=parent.revision+1),))
        }
    }
    @Test fun fiveMemberEvidenceFitsExistingMaintenanceLimit() {
        // A five-member group is already full, so measure a four-member parent plus the fifth candidate.
        val (parent,people,proposal)=fixture(4)
        val cert=AdmissionV2.certificate(proposal,approvals(people,proposal))
        assertTrue(AdmissionV2.verifyCertificate(cert,parent))
        assertTrue(NetworkCodec.encode(proposal).size<=AdmissionV2.MAX_PROPOSAL_BYTES)
        assertTrue(NetworkCodec.encode(cert).size<=AdmissionV2.MAX_CERTIFICATE_BYTES)
        assertTrue(NetworkCodec.encode(cert).size<GroupControlCodec.MAX_BYTES-1024)
        val response=GroupControl(kind=GroupControlKind.ADMISSION_V2_EVIDENCE_RESPONSE,
            groupId=parent.groupId,inviteId=proposal.inviteId,evidenceEventId=proposal.eventId,
            certificateV2=cert)
        val responseBytes=GroupControlCodec.encode(response)
        assertTrue(responseBytes.size<GroupControlCodec.MAX_BYTES-1024)
        assertEquals(GroupControlKind.ADMISSION_V2_EVIDENCE_RESPONSE,
            GroupControlCodec.decode(responseBytes).kind)
        val key=people.single {it.member.memberId==parent.ownerId}.key
        val admissionBody=GroupStatements.admission(parent,proposal.inviteId,proposal.candidate)
        val admission=GroupAdmission(parent,proposal.inviteId,proposal.candidate,
            sign(key,admissionBody),sign(key,admissionBody))
        val unsignedInvite=GroupInvite(proposal.inviteId,parent.groupId,GroupStatements.digest(parent),
            parent.revision,parent.epoch,parent.coordinatorId,proposal.candidate,byteArrayOf(),byteArrayOf())
        val invite=unsignedInvite.copy(inviterSignature=sign(key,GroupStatements.invite(unsignedInvite)))
        val inviteBytes=GroupControlCodec.encode(GroupControl(kind=GroupControlKind.INVITE,
            groupId=parent.groupId,inviteId=proposal.inviteId,state=parent,
            admission=admission,invite=invite,certificateV2=cert))
        assertTrue(inviteBytes.size<GroupControlCodec.MAX_BYTES-1024)
        println("A3_SIZE proposal=${NetworkCodec.encode(proposal).size} approval=${NetworkCodec.encode(approvals(people,proposal).first()).size} certificate=${NetworkCodec.encode(cert).size} evidenceResponse=${responseBytes.size} invitation=${inviteBytes.size}")
    }
    @Test fun malformedAndOversizedAdmissionEvidenceFailsClosed() {
        val (parent,people,proposal)=fixture(2)
        val valid=AdmissionV2.certificate(proposal,approvals(people,proposal))
        assertFalse(AdmissionV2.verifyProposal(proposal.copy(inviterSignature=ByteArray(6_000)),parent))
        assertFalse(AdmissionV2.verifyCertificate(valid.copy(digest=ByteArray(32)),parent))
        assertFalse(AdmissionV2.verifyCertificate(valid.copy(approvals=valid.approvals.reversed()),parent))
        assertTrue(runCatching {GroupControlCodec.decode(ByteArray(GroupControlCodec.MAX_BYTES+1))}.isFailure)
        assertTrue(runCatching {GroupControlCodec.decode(byteArrayOf(1,2,3,4))}.isFailure)
    }
    @Test fun authenticatedPaddingAdvertisesAdmissionVersionWithoutChangingText() {
        val encoded=ConversationPayload.encode("hello",0)
        val decoded=ConversationPayload.decode(encoded)
        assertEquals("hello",decoded.body)
        assertTrue(decoded.supportsGroups)
        assertTrue(decoded.supportsAdmissionV2)
        val corrupt=encoded.copyOf()
        val marker="GC/admission-v2!".toByteArray()
        val index=corrupt.indices.firstOrNull {offset ->
            offset+marker.size<=corrupt.size && corrupt.copyOfRange(offset,offset+marker.size).contentEquals(marker)
        } ?: error("marker absent")
        corrupt[index]=0
        assertFalse(ConversationPayload.decode(corrupt).supportsAdmissionV2)
    }
    @Test fun replayedAdmissionBeforeLocalRemovalStillAnchorsAtomically() {
        val owner=person();val local=person(2);val candidate=person(3)
        val trust=GroupTrustedPeer {true}
        val genesisState=GroupState(GroupIds.create(),1,1,owner.member.memberId,
            owner.member.memberId,listOf(owner.member.copy(role=GroupRole.OWNER)),ByteArray(32))
        val genesis=GroupGenesis(genesisState,sign(owner.key,GroupStatements.genesis(genesisState)))
        fun add(parent:GroupState,target:Person):GroupTransition {
            val unsigned=GroupInvite(GroupIds.create(),parent.groupId,GroupStatements.digest(parent),
                parent.revision,parent.epoch,parent.coordinatorId,target.member,byteArrayOf(),byteArrayOf())
            val offered=unsigned.copy(inviterSignature=sign(owner.key,GroupStatements.invite(unsigned)))
            val invite=offered.copy(targetAcceptance=sign(target.key,GroupStatements.acceptance(offered)))
            val change=GroupChange(GroupIds.create(),GroupAction.ADD,parent.ownerId,
                added=target.member,invite=invite)
            val next=GroupRules.derive(parent,change,emptySet(),trust)
            return GroupTransition(change,next,sign(owner.key,GroupStatements.actor(parent,change,next)),
                sign(owner.key,GroupStatements.coordinator(parent,change,next)))
        }
        val joined=add(genesisState,local)
        val admitted=add(joined.next,candidate)
        val removeChange=GroupChange(GroupIds.create(),GroupAction.REMOVE,owner.member.memberId,
            targetId=local.member.memberId)
        val removedState=GroupRules.derive(admitted.next,removeChange,emptySet(),trust)
        val removed=GroupTransition(removeChange,removedState,
            sign(owner.key,GroupStatements.actor(admitted.next,removeChange,removedState)),
            sign(owner.key,GroupStatements.coordinator(admitted.next,removeChange,removedState)))
        val records=MemoryRecords()
        val ledger=GroupLedger(records,trust,local.member.memberId)
        assertEquals(GroupApply.ACCEPTED,ledger.acceptGenesis(genesis))
        assertEquals(GroupApply.ACCEPTED,ledger.apply(genesisState.groupId,joined))
        val key="app/group/historical-authority/${genesisState.groupId}/${candidate.member.memberId}"
        records.failWritePrefix="app/group/historical-authority/"
        assertThrows(EndpointStorageFailure::class.java) {
            ledger.applySnapshot(genesisState.groupId,GroupSnapshot(removedState,listOf(admitted,removed))) {_,chain ->
                assertEquals(2,chain.size)
                records.write(key,byteArrayOf(1))
            }
        }
        assertEquals(joined.next.revision,ledger.state(genesisState.groupId)!!.revision)
        assertNull(records.read(key))
        records.failWritePrefix=null
        assertEquals(GroupApply.REMOVED,
            ledger.applySnapshot(genesisState.groupId,GroupSnapshot(removedState,listOf(admitted,removed))) {_,chain ->
                assertEquals(2,chain.size)
                records.write(key,byteArrayOf(1))
            })
        assertEquals(removedState.revision,ledger.state(genesisState.groupId)!!.revision)
        assertNotNull(records.read(key))
    }
}
