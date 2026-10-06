package org.ghostcloak.testing

import org.ghostcloak.messaging.*
import org.ghostcloak.identity.RandomIdentifiers
import org.ghostcloak.protocol.DeviceAuth
import org.ghostcloak.protocol.Challenge
import org.ghostcloak.protocol.NetworkCodec
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec

class GroupStateTest {
    @Test fun fiveMemberMembershipControlFitsEncryptedBodyWithHeadroom() {
        val owner=person(GroupRole.OWNER)
        var state=genesis(owner).state
        var final:GroupTransition?=null
        for(epoch in 2L..5L) {
            final=add(state,owner,person(GroupRole.MEMBER,epoch))
            state=final.next
        }
        val control=GroupControl(kind=GroupControlKind.STATE_UPDATE,groupId=state.groupId,transition=final)
        val encoded=GroupControlCodec.encode(control)
        val frame=ConversationPayload.encodeGroup(control)
        println("P13_2_MAX_FIVE_CONTROL=${encoded.size} FRAME=${frame.size} HEADROOM=${org.ghostcloak.protocol.EnvelopeCodec.MAX_BODY-frame.size}")
        assertTrue(encoded.size<=GroupControlCodec.MAX_BYTES)
        assertTrue(frame.size<=org.ghostcloak.protocol.EnvelopeCodec.MAX_BODY)
        assertTrue(org.ghostcloak.protocol.EnvelopeCodec.MAX_BODY-frame.size>=2048)
    }
    private data class Person(val member:GroupMember,val key:KeyPair) {
        fun sign(bytes:ByteArray)=Signature.getInstance("SHA256withECDSA").run {
            initSign(key.private);update(bytes);sign()
        }
    }
    private fun person(role:GroupRole,epoch:Long=1):Person {
        val key=KeyPairGenerator.getInstance("EC").apply {initialize(ECGenParameterSpec("secp256r1"))}.generateKeyPair()
        return Person(GroupMember(GroupIds.create(),RandomIdentifiers.create(),RandomIdentifiers.create(),
            key.public.encoded,ByteArray(32) {it.toByte()},role,epoch),key)
    }
    private val trust=GroupTrustedPeer {true}
    private fun genesis(owner:Person):GroupGenesis {
        val state=GroupState(GroupIds.create(),1,1,owner.member.memberId,owner.member.memberId,
            listOf(owner.member),ByteArray(32))
        return GroupGenesis(state,owner.sign(GroupStatements.genesis(state)))
    }
    private fun event(previous:GroupState,change:GroupChange,next:GroupState,
                      actor:Person,coordinator:Person,target:Person?=null)=GroupTransition(change,next,
        actor.sign(GroupStatements.actor(previous,change,next)),
        coordinator.sign(GroupStatements.coordinator(previous,change,next)),
        target?.sign(GroupStatements.transferAcceptance(previous,change,next)) ?: byteArrayOf())
    private fun invite(state:GroupState,inviter:Person,target:Person):GroupInvite {
        val unsigned=GroupInvite(GroupIds.create(),state.groupId,GroupStatements.digest(state),state.revision,
            state.epoch,inviter.member.memberId,target.member,byteArrayOf(),byteArrayOf())
        val signed=unsigned.copy(inviterSignature=inviter.sign(GroupStatements.invite(unsigned)))
        return signed.copy(targetAcceptance=target.sign(GroupStatements.acceptance(signed)))
    }
    private fun add(previous:GroupState,inviter:Person,target:Person):GroupTransition {
        val change=GroupChange(GroupIds.create(),GroupAction.ADD,inviter.member.memberId,
            added=target.member,invite=invite(previous,inviter,target))
        return event(previous,change,GroupRules.derive(previous,change,emptySet(),trust),inviter,inviter)
    }
    @Test fun creationAddReplayAndReconstruction() {
        val owner=person(GroupRole.OWNER);val guest=person(GroupRole.MEMBER,2)
        val store=MemoryRecords();val first=GroupLedger(store,trust,owner.member.memberId)
        val g=genesis(owner)
        assertEquals(GroupApply.ACCEPTED,first.acceptGenesis(g))
        assertEquals(GroupApply.DUPLICATE,first.acceptGenesis(g))
        val add=add(g.state,owner,guest)
        assertEquals(2L,add.next.epoch)
        assertEquals(GroupApply.ACCEPTED,first.apply(g.state.groupId,add))
        val restarted=GroupLedger(store,trust,owner.member.memberId)
        assertEquals(2L,restarted.state(g.state.groupId)!!.revision)
        assertEquals(GroupApply.DUPLICATE,restarted.apply(g.state.groupId,add))
        val removeChange=GroupChange(GroupIds.create(),GroupAction.REMOVE,owner.member.memberId,guest.member.memberId)
        val remove=event(add.next,removeChange,GroupRules.derive(add.next,removeChange,setOf(add.change.invite!!.inviteId),trust),owner,owner)
        assertEquals(GroupApply.ACCEPTED,restarted.apply(g.state.groupId,remove))
        assertEquals(3L,restarted.state(g.state.groupId)!!.epoch)
        assertThrows(IllegalArgumentException::class.java) { GroupRules.derive(remove.next,add.change,emptySet(),trust) }
    }
    @Test fun authorizationForkAndOfflineChain() {
        val owner=person(GroupRole.OWNER);val a=person(GroupRole.MEMBER,2);val b=person(GroupRole.MEMBER,3)
        val g=genesis(owner);val store=MemoryRecords();val ledger=GroupLedger(store,trust,owner.member.memberId)
        ledger.acceptGenesis(g)
        val e2=add(g.state,owner,a)
        val e3=add(e2.next,owner,b)
        assertEquals(GroupApply.NEEDS_RESYNC,ledger.apply(g.state.groupId,e3))
        assertEquals(GroupApply.ACCEPTED,ledger.applySnapshot(g.state.groupId,GroupSnapshot(e3.next,listOf(e2,e3))))
        assertEquals(3L,ledger.state(g.state.groupId)!!.revision)
        val bad=GroupChange(GroupIds.create(),GroupAction.PROMOTE,a.member.memberId,b.member.memberId)
        assertThrows(IllegalArgumentException::class.java) { GroupRules.derive(e3.next,bad,emptySet(),trust) }
        val profile=GroupChange(GroupIds.create(),GroupAction.PROFILE,owner.member.memberId,
            newProfileRevision=1,newProfileDigest=ByteArray(32){1})
        val alt=GroupChange(GroupIds.create(),GroupAction.TIMER,owner.member.memberId,newTimer=3600)
        val signedProfile=event(e3.next,profile,GroupRules.derive(e3.next,profile,emptySet(),trust),owner,owner)
        val signedAlt=event(e3.next,alt,GroupRules.derive(e3.next,alt,emptySet(),trust),owner,owner)
        assertEquals(GroupApply.ACCEPTED,ledger.apply(g.state.groupId,signedProfile))
        assertEquals(GroupApply.FORKED,ledger.apply(g.state.groupId,signedAlt))
        assertTrue(GroupLedger(store,trust,owner.member.memberId).isForked(g.state.groupId))
    }
    @Test fun invalidSignaturesRolesAndBounds() {
        val owner=person(GroupRole.OWNER);val guest=person(GroupRole.MEMBER,2);val g=genesis(owner)
        val change=GroupChange(GroupIds.create(),GroupAction.ADD,owner.member.memberId,
            added=guest.member,invite=invite(g.state,owner,guest))
        val next=GroupRules.derive(g.state,change,emptySet(),trust)
        val valid=event(g.state,change,next,owner,owner)
        val ledger=GroupLedger(MemoryRecords(),trust,owner.member.memberId);ledger.acceptGenesis(g)
        assertEquals(GroupApply.REJECTED,ledger.apply(g.state.groupId,valid.copy(actorSignature=byteArrayOf(1))))
        assertEquals(GroupApply.ACCEPTED,ledger.apply(g.state.groupId,valid))
        assertFalse(GroupStatements.verify(owner.member.authPublicKey,
            GroupStatements.coordinator(g.state,change,next),valid.actorSignature))
        assertThrows(IllegalArgumentException::class.java) {
            GroupStatements.validate(g.state.copy(members=listOf(owner.member,owner.member)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            GroupStatements.validate(g.state.copy(members=(1..6).map {person(GroupRole.MEMBER).member}))
        }
        assertThrows(IllegalArgumentException::class.java) {GroupStatements.validate(g.state.copy(epoch=0))}
        assertThrows(IllegalArgumentException::class.java) {GroupStatements.validate(g.state.copy(revision=0))}
        assertThrows(IllegalArgumentException::class.java) {
            GroupStatements.decodeState(NetworkCodec.encode(g.state.copy(ownerId=GroupIds.create())))
        }
        assertFalse(DeviceAuth.digest(GroupStatements.genesis(g.state)).contentEquals(DeviceAuth.digest(byteArrayOf())))
    }
    @Test fun boundedOrderingAndPerformance() {
        val owner=person(GroupRole.OWNER);val g=genesis(owner)
        val start=System.nanoTime()
        repeat(250) {
            GroupStatements.digest(g.state)
            GroupStatements.decodeState(GroupStatements.bytes(g.state))
            assertEquals(GroupApply.ACCEPTED,GroupLedger(MemoryRecords(),trust,owner.member.memberId).acceptGenesis(g))
        }
        println("GROUP_STATE_250_CREATE_VALIDATE_MS="+((System.nanoTime()-start)/1_000_000))
        val guest=person(GroupRole.MEMBER,2);val signed=add(g.state,owner,guest)
        val signStart=System.nanoTime()
        repeat(100) {
            val statement=GroupStatements.actor(g.state,signed.change,signed.next)
            val signature=owner.sign(statement)
            assertTrue(GroupStatements.verify(owner.member.authPublicKey,statement,signature))
        }
        println("GROUP_STATE_100_SIGN_VERIFY_MS="+((System.nanoTime()-signStart)/1_000_000))
        val resyncStart=System.nanoTime()
        repeat(100) {
            val ledger=GroupLedger(MemoryRecords(),trust,owner.member.memberId)
            ledger.acceptGenesis(g)
            assertEquals(GroupApply.ACCEPTED,ledger.applySnapshot(g.state.groupId,
                GroupSnapshot(signed.next,listOf(signed))))
        }
        println("GROUP_STATE_100_RESYNC_MS="+((System.nanoTime()-resyncStart)/1_000_000))
        println("GROUP_STATE_ENCODED_BYTES=${GroupStatements.bytes(signed.next).size};"+
            "EVENT_BYTES=${NetworkCodec.encode(signed).size};INVITE_BYTES=${NetworkCodec.encode(signed.change.invite!!).size}")
    }
    @Test fun domainBindingAndMalformedInputs() {
        val owner=person(GroupRole.OWNER);val g=genesis(owner)
        val challenge=Challenge(RandomIdentifiers.create(),ByteArray(32),Long.MAX_VALUE,"test",
            owner.member.accountId,owner.member.deviceId,"login",ByteArray(32))
        assertFalse(DeviceAuth.verify(owner.member.authPublicKey,challenge,g.ownerSignature))
        assertFalse(GroupStatements.isGroupSigningStatement(DeviceAuth.statement(challenge)))
        assertTrue(GroupStatements.isGroupSigningStatement(GroupStatements.genesis(g.state)))
        assertThrows(Exception::class.java) {GroupStatements.decodeState(byteArrayOf(0,1,2))}
        assertThrows(Exception::class.java) {GroupStatements.decodeState(ByteArray(4097))}
        val changed=g.state.copy(previousDigest=ByteArray(32){1})
        assertThrows(IllegalArgumentException::class.java) {GroupStatements.validate(changed)}
        val bytes=GroupStatements.bytes(g.state)
        assertArrayEquals(bytes,GroupStatements.bytes(GroupStatements.decodeState(bytes)))
    }
    @Test fun ownerAdminMemberPermissionsAndTerminalState() {
        val owner=person(GroupRole.OWNER);val a=person(GroupRole.MEMBER,2);val b=person(GroupRole.MEMBER,3)
        val g=genesis(owner);val e2=add(g.state,owner,a);val e3=add(e2.next,owner,b)
        val promote=GroupChange(GroupIds.create(),GroupAction.PROMOTE,owner.member.memberId,a.member.memberId)
        val s4=GroupRules.derive(e3.next,promote,emptySet(),trust)
        assertEquals(GroupRole.ADMIN,s4.members.single {it.memberId==a.member.memberId}.role)
        assertEquals(4L,s4.epoch)
        val adminRemove=GroupChange(GroupIds.create(),GroupAction.REMOVE,a.member.memberId,b.member.memberId)
        val s5=GroupRules.derive(s4,adminRemove,emptySet(),trust)
        assertEquals(2,s5.members.size)
        assertThrows(IllegalArgumentException::class.java) {
            GroupRules.derive(s4,adminRemove.copy(targetId=owner.member.memberId),emptySet(),trust)
        }
        assertThrows(IllegalArgumentException::class.java) {
            GroupRules.derive(s4,promote.copy(actorId=a.member.memberId,targetId=b.member.memberId),emptySet(),trust)
        }
        val transfer=GroupChange(GroupIds.create(),GroupAction.TRANSFER_OWNER,owner.member.memberId,a.member.memberId)
        val transferred=GroupRules.derive(s5,transfer,emptySet(),trust)
        assertEquals(a.member.memberId,transferred.ownerId)
        val store=MemoryRecords();val ledger=GroupLedger(store,trust,owner.member.memberId)
        ledger.acceptGenesis(g);ledger.apply(g.state.groupId,e2);ledger.apply(g.state.groupId,e3)
        val e4=event(e3.next,promote,s4,owner,owner);ledger.apply(g.state.groupId,e4)
        val e5=event(s4,adminRemove,s5,a,owner);ledger.apply(g.state.groupId,e5)
        assertEquals(GroupApply.REJECTED,ledger.apply(g.state.groupId,event(s5,transfer,transferred,owner,owner)))
        assertEquals(GroupApply.ACCEPTED,ledger.apply(g.state.groupId,event(s5,transfer,transferred,owner,owner,a)))
        val dissolve=GroupChange(GroupIds.create(),GroupAction.DISSOLVE,a.member.memberId)
        val terminal=GroupRules.derive(transferred,dissolve,emptySet(),trust)
        assertEquals(GroupApply.ACCEPTED,ledger.apply(g.state.groupId,event(transferred,dissolve,terminal,a,owner)))
        assertEquals(GroupLifecycle.DISSOLVED,ledger.lifecycle(g.state.groupId))
        assertEquals(GroupApply.REJECTED,ledger.apply(g.state.groupId,event(transferred,dissolve,terminal,a,owner)))
    }
    @Test fun voluntaryLeaveNeedsCanonicalCoordinatorSignatureAndAdvancesEpoch() {
        val owner=person(GroupRole.OWNER);val member=person(GroupRole.MEMBER,2)
        val genesis=genesis(owner);val joined=add(genesis.state,owner,member)
        val change=GroupChange(GroupIds.create(),GroupAction.LEAVE,member.member.memberId,
            targetId=member.member.memberId)
        val next=GroupRules.derive(joined.next,change,emptySet(),trust)
        val signed=event(joined.next,change,next,member,owner)
        val store=MemoryRecords();val ledger=GroupLedger(store,trust,member.member.memberId)
        assertEquals(GroupApply.ACCEPTED,ledger.acceptGenesis(genesis))
        assertEquals(GroupApply.ACCEPTED,ledger.apply(genesis.state.groupId,joined))
        assertEquals(GroupApply.REJECTED,ledger.apply(genesis.state.groupId,
            signed.copy(coordinatorSignature=member.sign(GroupStatements.coordinator(joined.next,change,next)))))
        assertEquals(GroupApply.REMOVED,ledger.apply(genesis.state.groupId,signed))
        assertEquals(GroupLocalStatus.LEFT,ledger.status(genesis.state.groupId))
        assertEquals(3L,ledger.state(genesis.state.groupId)!!.epoch)
    }
    @Test fun finalSlotRaceNeverDerivesSixthMember() {
        val owner=person(GroupRole.OWNER)
        var state=genesis(owner).state
        for(epoch in 2L..4L) state=add(state,owner,person(GroupRole.MEMBER,epoch)).next
        val first=person(GroupRole.MEMBER,5)
        val loser=person(GroupRole.MEMBER,5)
        val stale=GroupChange(GroupIds.create(),GroupAction.ADD,owner.member.memberId,
            added=loser.member,invite=invite(state,owner,loser))
        val winner=add(state,owner,first)
        assertEquals(5,winner.next.members.size)
        assertThrows(IllegalArgumentException::class.java) {
            GroupRules.derive(winner.next,stale,emptySet(),trust)
        }
    }
    @Test fun removedMemberAndTrustChangeFailClosed() {
        val owner=person(GroupRole.OWNER);val guest=person(GroupRole.MEMBER,2)
        val g=genesis(owner);val e2=add(g.state,owner,guest)
        val remove=GroupChange(GroupIds.create(),GroupAction.REMOVE,owner.member.memberId,guest.member.memberId)
        val e3=event(e2.next,remove,GroupRules.derive(e2.next,remove,emptySet(),trust),owner,owner)
        val store=MemoryRecords();val ledger=GroupLedger(store,trust,guest.member.memberId)
        ledger.acceptGenesis(g)
        assertEquals(GroupApply.ACCEPTED,ledger.apply(g.state.groupId,e2))
        assertEquals(GroupApply.REMOVED,ledger.apply(g.state.groupId,e3))
        assertFalse(GroupMessageContext(g.state.groupId,3,guest.member.memberId,GroupIds.create()).allowedBy(e3.next))
        val changedTrust=GroupLedger(MemoryRecords(),GroupTrustedPeer {it.memberId!=owner.member.memberId},owner.member.memberId)
        assertEquals(GroupApply.REJECTED,changedTrust.acceptGenesis(g))
    }
    @Test fun inviteTargetAndParentArePinned() {
        val owner=person(GroupRole.OWNER);val target=person(GroupRole.MEMBER,2)
        val g=genesis(owner);val invited=invite(g.state,owner,target)
        assertTrue(GroupStatements.verifyInvite(invited,g.state,emptySet()))
        assertFalse(GroupStatements.verifyInvite(invited,g.state,setOf(invited.inviteId)))
        assertFalse(GroupStatements.verifyInvite(invited.copy(target=person(GroupRole.MEMBER,2).member),g.state,emptySet()))
        assertFalse(GroupStatements.verifyInvite(invited.copy(parentDigest=ByteArray(32){7}),g.state,emptySet()))
        assertThrows(Exception::class.java) {GroupStatements.decodeInvite(ByteArray(2049))}
        val untrusted=GroupTrustedPeer {it.memberId!=target.member.memberId}
        val change=GroupChange(GroupIds.create(),GroupAction.ADD,owner.member.memberId,
            added=target.member,invite=invited)
        assertThrows(IllegalArgumentException::class.java) {GroupRules.derive(g.state,change,emptySet(),untrusted)}
    }
    @Test fun wrongParentAndStaleAuthorityDoNotAdvance() {
        val owner=person(GroupRole.OWNER);val member=person(GroupRole.MEMBER,2)
        val g=genesis(owner);val e2=add(g.state,owner,member)
        val store=MemoryRecords();val ledger=GroupLedger(store,trust,owner.member.memberId)
        ledger.acceptGenesis(g);ledger.apply(g.state.groupId,e2)
        val profile=GroupChange(GroupIds.create(),GroupAction.PROFILE,owner.member.memberId,
            newProfileRevision=1,newProfileDigest=ByteArray(32){1})
        val next=GroupRules.derive(e2.next,profile,emptySet(),trust)
        val wrong=next.copy(previousDigest=ByteArray(32){9})
        val signed=event(e2.next,profile,wrong,owner,owner)
        assertEquals(GroupApply.FORKED,ledger.apply(g.state.groupId,signed))
        assertEquals(2L,ledger.state(g.state.groupId)!!.revision)
        val localStatus=ledger.status(g.state.groupId)
        assertEquals(GroupLocalStatus.FORKED,localStatus)
    }
    @Test fun demotedAdminCannotReuseOldAuthorityAndOrderingIsCanonical() {
        val owner=person(GroupRole.OWNER);val admin=person(GroupRole.MEMBER,2);val third=person(GroupRole.MEMBER,3)
        val g=genesis(owner);val addedAdmin=add(g.state,owner,admin)
        val promote=GroupChange(GroupIds.create(),GroupAction.PROMOTE,owner.member.memberId,admin.member.memberId)
        val promoted=GroupRules.derive(addedAdmin.next,promote,emptySet(),trust)
        val addThird=add(promoted,owner,third.copy(member=third.member.copy(joinedEpoch=promoted.epoch+1)))
        val demote=GroupChange(GroupIds.create(),GroupAction.DEMOTE,owner.member.memberId,admin.member.memberId)
        val demoted=GroupRules.derive(addThird.next,demote,emptySet(),trust)
        val forbidden=GroupChange(GroupIds.create(),GroupAction.REMOVE,admin.member.memberId,third.member.memberId)
        assertThrows(IllegalArgumentException::class.java) {GroupRules.derive(demoted,forbidden,emptySet(),trust)}
        assertThrows(IllegalArgumentException::class.java) {
            GroupStatements.validate(promoted.copy(members=promoted.members.reversed()))
        }
        repeat(100) {index ->
            assertThrows(IllegalArgumentException::class.java) {
                GroupStatements.validate(promoted.copy(revision=if(index%2==0) 0 else 513))
            }
        }
    }
}
