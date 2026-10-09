package org.ghostcloak.messaging

import org.ghostcloak.identity.RandomIdentifiers
import org.ghostcloak.protocol.DeviceAuth
import org.ghostcloak.protocol.GhostCloakIds
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec

class GroupOwnerIntroductionV1Test {
    @Test fun ownerSignatureBindsExactParentCandidateInviteAndRoutes() {
        val key=KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val owner=GroupMember(GroupIds.create(),RandomIdentifiers.create(),RandomIdentifiers.create(),
            key.public.encoded,DeviceAuth.digest(byteArrayOf(1)),GroupRole.OWNER,1)
        val candidate=GroupMember(GroupIds.create(),RandomIdentifiers.create(),RandomIdentifiers.create(),
            key.public.encoded,DeviceAuth.digest(byteArrayOf(2)),GroupRole.MEMBER,2)
        val parent=GroupState(GroupIds.create(),1,1,owner.memberId,owner.memberId,listOf(owner),ByteArray(32))
        val unsigned=GroupOwnerIntroductionV1(groupId=parent.groupId,
            parentDigest=GroupStatements.digest(parent),inviteId=GroupIds.create(),candidate=candidate,
            routes=listOf(GroupOwnerRouteV1(owner.memberId,GhostCloakIds.generate()),
                GroupOwnerRouteV1(candidate.memberId,GhostCloakIds.generate())),ownerSignature=byteArrayOf())
        val signature=Signature.getInstance("SHA256withECDSA").run {
            initSign(key.private);update(GroupOwnerIntroductionRulesV1.statement(unsigned));sign()
        }
        val proof=unsigned.copy(ownerSignature=signature)
        assertTrue(GroupOwnerIntroductionRulesV1.verify(proof,parent))
        assertFalse(GroupOwnerIntroductionRulesV1.verify(proof,parent.copy(epoch=2,revision=2)))
        assertFalse(GroupOwnerIntroductionRulesV1.verify(proof.copy(inviteId=GroupIds.create()),parent))
        assertFalse(GroupOwnerIntroductionRulesV1.verify(proof.copy(candidate=candidate.copy(
            signalIdentityDigest=DeviceAuth.digest(byteArrayOf(3)))),parent))
        assertFalse(GroupOwnerIntroductionRulesV1.verify(proof.copy(routes=proof.routes.reversed().map {
            if(it.memberId==owner.memberId) it.copy(ghostCloakId=GhostCloakIds.generate()) else it
        }),parent))
        assertFalse(GroupOwnerIntroductionRulesV1.verify(proof.copy(routes=proof.routes.take(1)),parent))
        val hello=GroupControl(version=2,kind=GroupControlKind.OWNER_INTRO_HELLO_V1,
            groupId=parent.groupId,inviteId=proof.inviteId,state=parent,
            ownerIntroductionV1=proof)
        assertTrue(GroupControlCodec.encode(GroupControlCodec.decode(
            GroupControlCodec.encode(hello))).contentEquals(GroupControlCodec.encode(hello)))
        assertFalse(runCatching {GroupControlCodec.encode(hello.copy(version=1))}.isSuccess)
        assertFalse(runCatching {GroupControlCodec.encode(hello.copy(inviteId=GroupIds.create()))}.isSuccess)
    }
}
