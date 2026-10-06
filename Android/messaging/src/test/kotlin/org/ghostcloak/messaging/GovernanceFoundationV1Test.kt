package org.ghostcloak.messaging

import org.ghostcloak.crypto.EndpointRecords
import org.ghostcloak.identity.RandomIdentifiers
import org.ghostcloak.protocol.DeviceAuth
import org.ghostcloak.protocol.NetworkCodec
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec

class GovernanceFoundationV1Test {
    private class Records:EndpointRecords {
        val values=mutableMapOf<String,ByteArray>()
        var failPrefix:String?=null
        var failCommit=false
        private var depth=0
        override fun <T> transaction(block:()->T):T {
            if(depth>0) return block()
            val before=values.mapValues {it.value.copyOf()}.toMutableMap()
            depth++
            try {
                val result=block()
                if(failCommit) error("injected commit failure")
                return result
            } catch(error:Throwable) {values.clear();values.putAll(before);throw error}
            finally {depth--}
        }
        override fun read(key:String)=values[key]?.copyOf()
        override fun write(key:String,value:ByteArray) {
            if(failPrefix?.let(key::startsWith)==true) error("injected write failure")
            values[key]=value.copyOf()
        }
        override fun remove(key:String) {values.remove(key)}
        override fun keys(prefix:String)=values.keys.filter {it.startsWith(prefix)}
    }
    private data class Person(val member:GroupMember,val key:KeyPair)
    private data class Fixture(val records:Records,val state:GroupState,val people:List<Person>,
        val ledger:GroupLedger) {
        val owner get()=people.single {it.member.memberId==state.ownerId}
        val trusted=GroupTrustedPeer {true}
    }
    private fun key():KeyPair=KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()
    private fun sign(key:KeyPair,bytes:ByteArray)=Signature.getInstance("SHA256withECDSA").run {
        initSign(key.private);update(bytes);sign()
    }
    private fun fixture(count:Int=1,revision:Long=1):Fixture {
        val people=List(count) {
            val pair=key()
            Person(GroupMember(GroupIds.create(),RandomIdentifiers.create(),RandomIdentifiers.create(),
                pair.public.encoded,DeviceAuth.digest(byteArrayOf(it.toByte())),GroupRole.MEMBER,1),pair)
        }.sortedBy {it.member.memberId}
        val owner=people.first()
        val members=people.mapIndexed {index,p -> p.member.copy(
            role=when(index) {0 -> GroupRole.OWNER;1 -> if(count>2) GroupRole.ADMIN else GroupRole.MEMBER
                else -> GroupRole.MEMBER},joinedEpoch=(index+1).toLong())}
        val groupId=GroupIds.create()
        val genesisState=GroupState(groupId,1,1,owner.member.memberId,owner.member.memberId,
            listOf(members.first().copy(joinedEpoch=1)),ByteArray(32))
        val genesis=GroupGenesis(genesisState,sign(owner.key,GroupStatements.genesis(genesisState)))
        val records=Records()
        val ledger=GroupLedger(records,GroupTrustedPeer {true},owner.member.memberId)
        assertEquals(GroupApply.ACCEPTED,ledger.acceptGenesis(genesis))
        val state=if(count==1 && revision==1L) genesisState else GroupState(groupId,revision,
            count.toLong(),owner.member.memberId,owner.member.memberId,members,ByteArray(32))
        if(state!==genesisState) records.transaction {
            records.write("group/state/v1/$groupId",NetworkCodec.encode(GroupRecord(state,genesis=genesis)))
        }
        return Fixture(records,state,people,ledger)
    }
    private fun baseline(f:Fixture) {
        val unsigned=GroupAuthorityBaselineV1.unsignedProposal(f.state,GroupIds.create())
        val proposal=unsigned.copy(coordinatorSignature=sign(f.owner.key,
            GroupAuthorityBaselineV1.proposalStatement(unsigned)))
        val approvals=f.people.map {person ->
            val unsignedApproval=GroupAuthorityBaselineV1.unsignedApproval(proposal,person.member.memberId)
            unsignedApproval.copy(signature=sign(person.key,
                GroupAuthorityBaselineV1.approvalStatement(unsignedApproval)))
        }
        val certificate=GroupAuthorityBaselineV1.certificate(proposal,approvals)
        val own=approvals.single {it.approverId==f.state.ownerId}
        val store=GroupAuthorityBaselineStore(f.records)
        store.saveOwn(OwnBaselineApprovalV1(f.state.groupId,proposal.proposalId,f.state.revision,
            GroupAuthorityBaselineV1.proposalDigest(proposal),proposal.memberSetDigest,own))
        store.activate(f.state,certificate,f.state.ownerId)
    }
    private fun install(f:Fixture):GovernanceHeadFoundationV1 {
        baseline(f)
        assertEquals(GroupApply.ACCEPTED,f.ledger.installGovernanceBarrierForFutureActivation(
            f.state.groupId,DeviceAuth.digest(byteArrayOf(4,5,6))))
        return GovernanceFoundationStore(f.records).head(f.state.groupId)!!
    }
    private fun transition(f:Fixture,parent:GroupState,change:GroupChange):GroupTransition {
        val next=GroupRules.derive(parent,change,emptySet(),f.trusted)
        val actor=f.people.single {it.member.memberId==change.actorId}
        val coordinator=f.people.single {it.member.memberId==parent.coordinatorId}
        return GroupTransition(change,next,sign(actor.key,GroupStatements.actor(parent,change,next)),
            sign(coordinator.key,GroupStatements.coordinator(parent,change,next)))
    }
    private fun profile(f:Fixture,parent:GroupState)=transition(f,parent,
        GroupChange(GroupIds.create(),GroupAction.PROFILE,parent.ownerId,
            newProfileRevision=parent.profileRevision+1,
            newProfileDigest=DeviceAuth.digest(parent.profileDigest+byteArrayOf(parent.revision.toByte()))))
    private fun add(f:Fixture):GroupTransition {
        val targetKey=key()
        val target=GroupMember(GroupIds.create(),RandomIdentifiers.create(),RandomIdentifiers.create(),
            targetKey.public.encoded,ByteArray(32),GroupRole.MEMBER,f.state.epoch+1)
        val unsigned=GroupInvite(GroupIds.create(),f.state.groupId,GroupStatements.digest(f.state),
            f.state.revision,f.state.epoch,f.state.ownerId,target,byteArrayOf(),byteArrayOf())
        val offered=unsigned.copy(inviterSignature=sign(f.owner.key,GroupStatements.invite(unsigned)))
        val accepted=offered.copy(targetAcceptance=sign(targetKey,GroupStatements.acceptance(offered)))
        return transition(f,f.state,GroupChange(GroupIds.create(),GroupAction.ADD,f.state.ownerId,
            added=target,invite=accepted))
    }

    @Test fun barrierRequiresExactActiveA5AndIsDurableAndIdempotent() {
        val f=fixture()
        val activation=DeviceAuth.digest(byteArrayOf(4,5,6))
        assertEquals(GroupApply.REJECTED,f.ledger.installGovernanceBarrierForFutureActivation(
            f.state.groupId,activation))
        val head=install(f)
        assertEquals(0L,head.sequence)
        assertEquals(f.state.revision,head.stateRevision)
        assertEquals(GroupApply.DUPLICATE,GroupLedger(f.records,f.trusted,f.state.ownerId)
            .installGovernanceBarrierForFutureActivation(f.state.groupId,activation))
        assertEquals(GroupApply.REJECTED,f.ledger.installGovernanceBarrierForFutureActivation(
            f.state.groupId,ByteArray(32)))
        assertEquals(GroupApply.REJECTED,GroupLedger(f.records,f.trusted,f.state.ownerId)
            .apply(f.state.groupId,profile(f,f.state)))
        assertNotNull(GovernanceFoundationStore(f.records).barrier(f.state.groupId))
    }

    @Test fun staleBaselineCannotInstallBarrier() {
        val f=fixture();baseline(f)
        assertEquals(GroupApply.ACCEPTED,f.ledger.apply(f.state.groupId,profile(f,f.state)))
        assertEquals(GroupApply.REJECTED,f.ledger.installGovernanceBarrierForFutureActivation(
            f.state.groupId,DeviceAuth.digest(byteArrayOf(4,5,6))))
        assertNull(GovernanceFoundationStore(f.records).barrier(f.state.groupId))
    }

    @Test fun rawMutationAndLegacyAddAreFencedButPreBarrierAddWorks() {
        val old=fixture();val legacyAdd=add(old)
        assertEquals(GroupApply.ACCEPTED,old.ledger.apply(old.state.groupId,legacyAdd))
        val f=fixture();val event=add(f);install(f)
        var sideEffect=false
        assertEquals(GroupApply.REJECTED,f.ledger.apply(f.state.groupId,event) {sideEffect=true})
        assertFalse(sideEffect)
        assertEquals(f.state.revision,f.ledger.state(f.state.groupId)!!.revision)
        assertEquals(GroupApply.REJECTED,GroupLedger(f.records,f.trusted,f.state.ownerId)
            .apply(f.state.groupId,event))
    }

    @Test fun legacyAdmissionCannotReplaceBarrierAndGenesisCannotEraseIt() {
        val f=fixture();install(f)
        val before=f.records.read("group/state/v1/${f.state.groupId}")!!
        val head=GovernanceFoundationStore(f.records).head(f.state.groupId)!!
        val proof=GroupAdmission(f.state,GroupIds.create(),f.state.members.single(),
            ByteArray(64),ByteArray(64))
        assertEquals(GroupApply.REJECTED,f.ledger.acceptAdmission(proof))
        val signed=GroupGenesis(f.state,sign(f.owner.key,GroupStatements.genesis(f.state)))
        assertEquals(GroupApply.DUPLICATE,f.ledger.acceptGenesis(signed))
        assertArrayEquals(before,f.records.read("group/state/v1/${f.state.groupId}"))
        assertTrue(GovernanceFoundationStore(f.records).matches(head,
            GovernanceFoundationStore(f.records).head(f.state.groupId)!!))
        assertNotNull(GovernanceFoundationStore(f.records).barrier(f.state.groupId))
    }

    @Test fun allExistingRawManagementActionsStopAtBarrier() {
        val f=fixture(3,10);install(f)
        val admin=f.state.members.single {it.role==GroupRole.ADMIN}.memberId
        val member=f.state.members.single {it.role==GroupRole.MEMBER}.memberId
        val changes=listOf(
            GroupChange(GroupIds.create(),GroupAction.REMOVE,f.state.ownerId,targetId=member),
            GroupChange(GroupIds.create(),GroupAction.PROMOTE,f.state.ownerId,targetId=member),
            GroupChange(GroupIds.create(),GroupAction.DEMOTE,f.state.ownerId,targetId=admin),
            GroupChange(GroupIds.create(),GroupAction.DISSOLVE,f.state.ownerId))
        changes.forEach {change ->
            val event=transition(f,f.state,change)
            assertEquals(change.action.toString(),GroupApply.REJECTED,
                f.ledger.apply(f.state.groupId,event))
        }
        assertEquals(10L,f.ledger.state(f.state.groupId)!!.revision)
    }

    @Test fun legacySnapshotCanReachExactAnchorButCannotCrossIt() {
        val f=fixture(1,10);val original=f.records.read("group/state/v1/${f.state.groupId}")!!
        val eleven=profile(f,f.state)
        val twelve=profile(f,eleven.next)
        val thirteen=profile(f,twelve.next)
        assertEquals(GroupApply.ACCEPTED,f.ledger.apply(f.state.groupId,eleven))
        assertEquals(GroupApply.ACCEPTED,f.ledger.apply(f.state.groupId,twelve))
        val anchor=Fixture(f.records,twelve.next,f.people,f.ledger)
        install(anchor)
        f.records.write("group/state/v1/${f.state.groupId}",original)
        assertThrows(IllegalStateException::class.java) {f.ledger.state(f.state.groupId)}
        assertEquals(GroupApply.REJECTED,f.ledger.applySnapshot(f.state.groupId,
            GroupSnapshot(thirteen.next,listOf(eleven,twelve,thirteen))))
        assertEquals(10L,f.ledger.stateForLegacyReplay(f.state.groupId)!!.revision)
        assertEquals(GroupApply.ACCEPTED,f.ledger.applySnapshot(f.state.groupId,
            GroupSnapshot(twelve.next,listOf(eleven,twelve))))
        assertEquals(12L,f.ledger.state(f.state.groupId)!!.revision)
        assertEquals(GroupApply.REJECTED,f.ledger.applySnapshot(f.state.groupId,
            GroupSnapshot(thirteen.next,listOf(thirteen))))
    }

    @Test fun invalidClaimAtAnchorCannotForceForkButValidAlternateAnchorDoes() {
        val f=fixture(1,10)
        val eleven=profile(f,f.state)
        val twelve=profile(f,eleven.next)
        assertEquals(GroupApply.ACCEPTED,f.ledger.apply(f.state.groupId,eleven))
        val atEleven=f.records.read("group/state/v1/${f.state.groupId}")!!
        assertEquals(GroupApply.ACCEPTED,f.ledger.apply(f.state.groupId,twelve))
        install(Fixture(f.records,twelve.next,f.people,f.ledger))
        f.records.write("group/state/v1/${f.state.groupId}",atEleven)
        val alternate=transition(f,eleven.next,GroupChange(GroupIds.create(),GroupAction.PROFILE,
            f.state.ownerId,newProfileRevision=eleven.next.profileRevision+1,
            newProfileDigest=DeviceAuth.digest(byteArrayOf(98))))
        val forged=alternate.copy(actorSignature=ByteArray(64))
        assertEquals(GroupApply.REJECTED,f.ledger.apply(f.state.groupId,forged))
        assertFalse(f.ledger.isForked(f.state.groupId))
        assertEquals(GroupApply.FORKED,f.ledger.apply(f.state.groupId,alternate))
        assertTrue(f.ledger.isForked(f.state.groupId))
        assertNotNull(GovernanceFoundationStore(f.records).barrier(f.state.groupId))
    }

    @Test fun incompleteBoundaryAndInstallerWriteFailureFailClosed() {
        val f=fixture();baseline(f)
        val groupId=f.state.groupId
        f.records.failPrefix="app/group/governance-foundation-v1/barrier/"
        assertThrows(IllegalStateException::class.java) {
            f.ledger.installGovernanceBarrierForFutureActivation(groupId,ByteArray(32))
        }
        f.records.failPrefix=null
        assertNull(GovernanceFoundationStore(f.records).head(groupId))
        assertNull(GovernanceFoundationStore(f.records).barrier(groupId))
        assertEquals(GroupApply.ACCEPTED,f.ledger.installGovernanceBarrierForFutureActivation(
            groupId,DeviceAuth.digest(byteArrayOf(4,5,6))))
        f.records.remove("app/group/governance-foundation-v1/barrier/$groupId")
        assertThrows(IllegalStateException::class.java) {f.ledger.state(groupId)}
        assertThrows(IllegalStateException::class.java) {f.ledger.apply(groupId,profile(f,f.state))}
    }

    @Test fun governedTransitionAndHeadCommitOrRollbackTogether() {
        val f=fixture();val head=install(f);val event=profile(f,f.state)
        val nextDigest=DeviceAuth.digest(byteArrayOf(7,8,9))
        val groupKey="group/state/v1/${f.state.groupId}"
        val beforeState=f.records.read(groupKey)!!
        val headKey="app/group/governance-foundation-v1/head/${f.state.groupId}"
        val beforeHead=f.records.read(headKey)!!
        for(failingPrefix in listOf("app/group/governance-foundation-v1/head/","group/state/v1/")) {
            f.records.failPrefix=failingPrefix
            assertThrows(IllegalStateException::class.java) {
                f.ledger.applyGovernedTransition(f.state.groupId,head,event,nextDigest)
            }
            assertArrayEquals(beforeState,f.records.read(groupKey))
            assertArrayEquals(beforeHead,f.records.read(headKey))
            f.records.failPrefix=null
        }
        f.records.failCommit=true
        assertThrows(IllegalStateException::class.java) {
            f.ledger.applyGovernedTransition(f.state.groupId,head,event,nextDigest)
        }
        f.records.failCommit=false
        assertArrayEquals(beforeState,f.records.read(groupKey))
        assertArrayEquals(beforeHead,f.records.read(headKey))
        assertEquals(GroupApply.ACCEPTED,f.ledger.applyGovernedTransition(
            f.state.groupId,head,event,nextDigest))
        val after=GovernanceFoundationStore(f.records).head(f.state.groupId)!!
        assertEquals(1L,after.sequence)
        assertEquals(event.next.revision,f.ledger.state(f.state.groupId)!!.revision)
        assertEquals(GroupApply.REJECTED,f.ledger.applyGovernedTransition(
            f.state.groupId,head,event,nextDigest))
    }

    @Test fun wrongAnchorAndConflictingGovernedSuccessorFailClosed() {
        val f=fixture();val head=install(f)
        val event=profile(f,f.state)
        val rival=transition(f,f.state,GroupChange(GroupIds.create(),GroupAction.PROFILE,
            f.state.ownerId,newProfileRevision=1,
            newProfileDigest=DeviceAuth.digest(byteArrayOf(99))))
        assertEquals(GroupApply.ACCEPTED,f.ledger.applyGovernedTransition(f.state.groupId,
            head,event,DeviceAuth.digest(byteArrayOf(1))))
        val nextHead=GovernanceFoundationStore(f.records).head(f.state.groupId)!!
        assertEquals(GroupApply.FORKED,f.ledger.applyGovernedTransition(f.state.groupId,
            nextHead,rival,DeviceAuth.digest(byteArrayOf(2))))
        assertTrue(f.ledger.isForked(f.state.groupId))
        assertNotNull(GovernanceFoundationStore(f.records).barrier(f.state.groupId))
        assertEquals(GroupApply.REJECTED,f.ledger.apply(f.state.groupId,profile(f,event.next)))

        val other=fixture();install(other)
        val wrong=other.state.copy(profileDigest=DeviceAuth.digest(byteArrayOf(1)))
        other.records.write("group/state/v1/${other.state.groupId}",NetworkCodec.encode(
            GroupRecord(wrong,genesis=GroupGenesis(other.state,
                sign(other.owner.key,GroupStatements.genesis(other.state))))))
        assertThrows(IllegalStateException::class.java) {other.ledger.state(other.state.groupId)}
    }
}
