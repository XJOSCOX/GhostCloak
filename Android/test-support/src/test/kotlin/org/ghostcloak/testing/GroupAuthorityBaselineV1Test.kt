package org.ghostcloak.testing

import org.ghostcloak.messaging.*
import org.ghostcloak.identity.RandomIdentifiers
import org.ghostcloak.protocol.DeviceAuth
import org.ghostcloak.protocol.NetworkCodec
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec

class GroupAuthorityBaselineV1Test {
    private fun key():KeyPair=KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()
    private fun sign(key:KeyPair,body:ByteArray)=Signature.getInstance("SHA256withECDSA").run {
        initSign(key.private);update(body);sign()
    }
    private data class Person(val member:GroupMember,val key:KeyPair)
    private fun fixture(n:Int):Triple<GroupState,List<Person>,GroupAuthorityBaselineProposalV1> {
        val people=List(n) {
            val key=key()
            Person(GroupMember(GroupIds.create(),RandomIdentifiers.create(),RandomIdentifiers.create(),
                key.public.encoded,DeviceAuth.digest(RandomIdentifiers.create().toByteArray()),
                GroupRole.MEMBER,1),key)
        }.sortedBy {it.member.memberId}
        val owner=people.first()
        val state=GroupState(GroupIds.create(),n.toLong(),n.toLong(),owner.member.memberId,
            owner.member.memberId,people.map {it.member.copy(role=if(it==owner) GroupRole.OWNER else GroupRole.MEMBER)},
            ByteArray(32))
        val unsigned=GroupAuthorityBaselineV1.unsignedProposal(state,GroupIds.create())
        return Triple(state,people,unsigned.copy(coordinatorSignature=sign(owner.key,
            GroupAuthorityBaselineV1.proposalStatement(unsigned))))
    }
    private fun approvals(people:List<Person>,proposal:GroupAuthorityBaselineProposalV1)=
        people.map {person ->
            val unsigned=GroupAuthorityBaselineV1.unsignedApproval(proposal,person.member.memberId)
            unsigned.copy(signature=sign(person.key,GroupAuthorityBaselineV1.approvalStatement(unsigned)))
        }
    @Test fun exactCurrentRosterNeedsEveryIndependentSignature() {
        for(n in 1..5) {
            val (state,people,proposal)=fixture(n)
            assertTrue(GroupAuthorityBaselineV1.verifyProposal(proposal,state))
            val all=approvals(people,proposal)
            val certificate=GroupAuthorityBaselineV1.certificate(proposal,all.reversed())
            assertTrue(GroupAuthorityBaselineV1.verifyCertificate(certificate,state))
            assertArrayEquals(certificate.digest,GroupAuthorityBaselineV1.certificate(proposal,all).digest)
            assertFalse(GroupAuthorityBaselineV1.verifyCertificate(
                GroupAuthorityBaselineV1.certificate(proposal,all.dropLast(1)),state))
            assertFalse(GroupAuthorityBaselineV1.verifyCertificate(
                GroupAuthorityBaselineV1.certificate(proposal,all+all.first()),state))
            assertFalse(GroupAuthorityBaselineV1.verifyCertificate(certificate.copy(digest=ByteArray(32)),state))
            assertFalse(GroupAuthorityBaselineV1.verifyCertificate(certificate,state.copy(
                revision=state.revision+1)))
            assertFalse(GroupAuthorityBaselineV1.verifyCertificate(certificate,state.copy(
                groupId=GroupIds.create())))
            assertFalse(GroupAuthorityBaselineV1.verifyCertificate(certificate,state.copy(
                members=state.members.mapIndexed {index,member -> if(index==0)
                    member.copy(signalIdentityDigest=ByteArray(32)) else member})))
            assertFalse(GroupAuthorityBaselineV1.verifyCertificate(certificate,state.copy(
                members=state.members.mapIndexed {index,member -> if(index==0)
                    member.copy(authPublicKey=key().public.encoded) else member})))
            assertFalse(GroupAuthorityBaselineV1.verifyProposal(proposal.copy(
                members=proposal.members.dropLast(1)),state))
            assertFalse(GroupAuthorityBaselineV1.verifyProposal(proposal.copy(
                memberSetDigest=ByteArray(32)),state))
            assertFalse(GroupAuthorityBaselineV1.verifyProposal(proposal.copy(
                ownerId=GroupIds.create()),state))
            assertFalse(GroupAuthorityBaselineV1.verifyProposal(proposal.copy(
                coordinatorSignature=ByteArray(64)),state))
            val wrong=all.first().copy(memberSetDigest=ByteArray(32))
            assertFalse(GroupAuthorityBaselineV1.verifyApproval(wrong,proposal))
            assertFalse(GroupAuthorityBaselineV1.verifyApproval(all.first().copy(
                groupId=GroupIds.create()),proposal))
            assertFalse(GroupAuthorityBaselineV1.verifyApproval(all.first().copy(
                stateRevision=proposal.state.revision+1),proposal))
            assertFalse(GroupAuthorityBaselineV1.verifyApproval(all.first().copy(
                approverId=GroupIds.create()),proposal))
            assertFalse(GroupAuthorityBaselineV1.verifyCertificate(
                GroupAuthorityBaselineV1.certificate(proposal,all.drop(1)+wrong),state))
        }
    }
    @Test fun baselineSizesFitExistingType13AndEncryptedBodyWithHeadroom() {
        for(n in listOf(2,3,5)) {
            val (state,people,proposal)=fixture(n)
            val all=approvals(people,proposal)
            val certificate=GroupAuthorityBaselineV1.certificate(proposal,all)
            val control=GroupControl(kind=GroupControlKind.BASELINE_V1_CERTIFICATE,
                groupId=state.groupId,baselineCertificateV1=certificate)
            val encoded=GroupControlCodec.encode(control)
            val frame=ConversationPayload.encodeGroup(control)
            assertTrue(encoded.size<GroupControlCodec.MAX_BYTES-1024)
            assertTrue(frame.size<=16_384-1024)
            assertEquals(GroupControlKind.BASELINE_V1_CERTIFICATE,GroupControlCodec.decode(encoded).kind)
            assertEquals(GroupControlKind.BASELINE_V1_CERTIFICATE,
                ConversationPayload.decode(frame).groupControl!!.kind)
            println("A5_SIZE members=$n proposal=${NetworkCodec.encode(proposal).size} approval=${NetworkCodec.encode(all.first()).size} certificate=${NetworkCodec.encode(certificate).size} control=${encoded.size} frame=${frame.size}")
        }
    }
    @Test fun baselineControlCannotSmuggleLegacyUserOrOtherProtocolFields() {
        val (state,people,proposal)=fixture(2)
        val cert=GroupAuthorityBaselineV1.certificate(proposal,approvals(people,proposal))
        assertTrue(runCatching {GroupControlCodec.encode(GroupControl(
            kind=GroupControlKind.BASELINE_V1_CERTIFICATE,groupId=state.groupId,
            baselineCertificateV1=cert,fromRevision=2))}.isFailure)
        assertTrue(runCatching {GroupControlCodec.encode(GroupControl(
            kind=GroupControlKind.RESYNC_REQUEST,groupId=state.groupId,
            fromRevision=1,fromDigest=ByteArray(32),baselineCertificateV1=cert))}.isFailure)
        assertTrue(runCatching {GroupControlCodec.decode(ByteArray(GroupControlCodec.MAX_BYTES+1))}.isFailure)
    }
    @Test fun capabilityPaddingLeavesLegacyMaintenanceControlReadable() {
        val id=GroupIds.create()
        val control=GroupControl(kind=GroupControlKind.RESYNC_REQUEST,groupId=id,
            fromRevision=1,fromDigest=ByteArray(32))
        val frame=ConversationPayload.encodeGroup(control)
        val decoded=ConversationPayload.decode(frame)
        assertEquals(GroupControlKind.RESYNC_REQUEST,decoded.groupControl!!.kind)
        assertTrue(decoded.supportsBaselineV1)
        val marker="GC/baseline-v1!".toByteArray()
        val markerOffset=16+GroupControlCodec.encode(control).size
        assertArrayEquals(marker,frame.copyOfRange(markerOffset,markerOffset+marker.size))
        val oldFrame=frame.copyOf().also {it[markerOffset]=0}
        assertEquals(GroupControlKind.RESYNC_REQUEST,
            ConversationPayload.decode(oldFrame).groupControl!!.kind)
        assertFalse(ConversationPayload.decode(oldFrame).supportsBaselineV1)
    }
}
