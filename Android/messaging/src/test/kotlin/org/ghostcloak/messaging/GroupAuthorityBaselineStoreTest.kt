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

class GroupAuthorityBaselineStoreTest {
    private class Records:EndpointRecords {
        var values=mutableMapOf<String,ByteArray>()
        var failPrefix:String?=null
        private var depth=0
        override fun <T> transaction(block:()->T):T {
            if(depth>0) return block()
            val before=values.mapValues {it.value.copyOf()}.toMutableMap()
            depth++
            try {return block()} catch(error:Throwable) {values=before;throw error}
            finally {depth--}
        }
        override fun read(key:String)=values[key]?.copyOf()
        override fun write(key:String,value:ByteArray) {
            if(failPrefix?.let(key::startsWith)==true) error("injected storage failure")
            values[key]=value.copyOf()
        }
        override fun remove(key:String) {values.remove(key)}
        override fun keys(prefix:String)=values.keys.filter {it.startsWith(prefix)}
    }
    private fun key():KeyPair=KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()
    private fun sign(key:KeyPair,body:ByteArray)=Signature.getInstance("SHA256withECDSA").run {
        initSign(key.private);update(body);sign()
    }
    private data class Fixture(val state:GroupState,val keys:Map<String,KeyPair>,
        val proposal:GroupAuthorityBaselineProposalV1,
        val certificate:GroupAuthorityBaselineCertificateV1)
    private fun fixture(count:Int=3):Fixture {
        val people=List(count) {
            val key=key()
            GroupMember(GroupIds.create(),RandomIdentifiers.create(),RandomIdentifiers.create(),
                key.public.encoded,DeviceAuth.digest(RandomIdentifiers.create().toByteArray()),
                GroupRole.MEMBER,1) to key
        }.sortedBy {it.first.memberId}
        val owner=people.first().first.memberId
        val state=GroupState(GroupIds.create(),12,12,owner,owner,people.mapIndexed {index,pair ->
            pair.first.copy(role=if(index==0) GroupRole.OWNER else GroupRole.MEMBER)
        },ByteArray(32))
        val unsigned=GroupAuthorityBaselineV1.unsignedProposal(state,GroupIds.create())
        val proposal=unsigned.copy(coordinatorSignature=sign(people.first().second,
            GroupAuthorityBaselineV1.proposalStatement(unsigned)))
        val approvals=people.map {(member,key) ->
            val unsignedApproval=GroupAuthorityBaselineV1.unsignedApproval(proposal,member.memberId)
            unsignedApproval.copy(signature=sign(key,
                GroupAuthorityBaselineV1.approvalStatement(unsignedApproval)))
        }
        return Fixture(state,people.associate {it.first.memberId to it.second},proposal,
            GroupAuthorityBaselineV1.certificate(proposal,approvals))
    }
    @Test fun activationAndAllHistoricalAnchorsCommitTogetherOrNotAtAll() {
        val f=fixture(5);val records=Records();val store=GroupAuthorityBaselineStore(records)
        val local=f.state.ownerId
        val approval=f.certificate.approvals.single {it.approverId==local}
        store.saveOwn(OwnBaselineApprovalV1(f.state.groupId,f.proposal.proposalId,12,
            GroupAuthorityBaselineV1.proposalDigest(f.proposal),f.proposal.memberSetDigest,approval))
        records.failPrefix="app/group/historical-existing-baseline/${f.state.groupId}/${f.state.members.last().memberId}"
        assertThrows(IllegalStateException::class.java) {store.activate(f.state,f.certificate,local)}
        assertNull(store.active(f.state.groupId))
        assertTrue(records.keys("app/group/historical-existing-baseline/${f.state.groupId}/").isEmpty())
        records.failPrefix=null
        store.activate(f.state,f.certificate,local)
        assertNotNull(store.active(f.state.groupId))
        assertEquals(5,records.keys("app/group/historical-existing-baseline/${f.state.groupId}/").size)
        val activation=records.read("app/group/baseline-v1/active/${f.state.groupId}")!!
        store.activate(f.state,f.certificate,local)
        assertArrayEquals(activation,records.read("app/group/baseline-v1/active/${f.state.groupId}"))
        assertThrows(IllegalArgumentException::class.java) {
            store.activate(f.state,f.certificate.copy(digest=ByteArray(32)),local)
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.activate(f.state.copy(revision=13),f.certificate,local)
        }
        println("A5_STORAGE activation=${activation.size} fiveAnchors=${
            records.keys("app/group/historical-existing-baseline/${f.state.groupId}/").sumOf {
                records.read(it)!!.size
            }}")
    }
    @Test fun baselineAndAdmissionHistoricalAuthorityAreProspectiveOnly() {
        val f=fixture();val records=Records();val history=HistoricalGroupAuthority(records)
        val member=f.state.members[1]
        f.state.members.forEach {history.anchorExistingBaseline(f.state,it,f.certificate.digest)}
        assertFalse(history.matchesAt(f.state.groupId,member,7))
        assertFalse(history.matchesAt(f.state.groupId,member,11))
        assertTrue(history.matchesAt(f.state.groupId,member,12))
        assertTrue(history.matchesAt(f.state.groupId,member,13))
        assertFalse(history.matchesAt(f.state.groupId,
            member.copy(signalIdentityDigest=ByteArray(32)),13))
        assertFalse(history.matchesAt(GroupIds.create(),member,13))
        val newMember=GroupMember(GroupIds.create(),RandomIdentifiers.create(),
            RandomIdentifiers.create(),key().public.encoded,ByteArray(32),GroupRole.MEMBER,13)
        records.write("app/group/historical-authority/${f.state.groupId}/${newMember.memberId}",
            NetworkCodec.encode(HistoricalGroupAuthorityV2(f.state.groupId,newMember,12,
                GroupStatements.digest(f.state),GroupIds.create(),ByteArray(32),ByteArray(32),
                GroupIds.create(),13,ByteArray(32))))
        assertFalse(history.matchesAt(f.state.groupId,newMember,12))
        assertTrue(history.matchesAt(f.state.groupId,newMember,13))
        val joinedState=f.state.copy(revision=13,epoch=13,
            previousDigest=GroupStatements.digest(f.state))
        val admission=GroupAdmission(joinedState,GroupIds.create(),newMember.copy(joinedEpoch=14),
            ByteArray(64),ByteArray(64))
        history.anchorBaseline(admission,f.state.members.first())
        assertFalse(history.matchesAt(f.state.groupId,f.state.members.first(),11))
        assertTrue(history.matchesAt(f.state.groupId,f.state.members.first(),12))
        assertTrue(history.matchesAt(f.state.groupId,f.state.members.first(),13))
        assertTrue(history.matchesAt(f.state.groupId,member,12))
        // Neither historical record is consulted by fresh GroupCurrentAuthority.
        assertNull(GroupCurrentAuthority(records).current(f.state.groupId,member.deviceId,
            member.signalIdentityDigest))
        assertNull(GroupCurrentAuthority(records).current(f.state.groupId,newMember.deviceId,
            newMember.signalIdentityDigest))
    }
    @Test fun historicalEvidenceCannotAuthorizeFreshStateUpdates() {
        val f=fixture(1);val records=Records()
        val owner=f.state.members.single()
        val ownerKey=f.keys.getValue(owner.memberId)
        val genesisState=GroupState(f.state.groupId,1,1,owner.memberId,owner.memberId,
            listOf(owner),ByteArray(32))
        val trusted=GroupLedger(records,GroupTrustedPeer {true},owner.memberId)
        assertEquals(GroupApply.ACCEPTED,trusted.acceptGenesis(GroupGenesis(genesisState,
            sign(ownerKey,GroupStatements.genesis(genesisState)))))
        var current=genesisState
        fun profile(parent:GroupState):GroupTransition {
            val change=GroupChange(GroupIds.create(),GroupAction.PROFILE,owner.memberId,
                newProfileRevision=parent.profileRevision+1,
                newProfileDigest=DeviceAuth.digest(parent.profileDigest+byteArrayOf(1)))
            val next=GroupRules.derive(parent,change,emptySet(),GroupTrustedPeer {true})
            return GroupTransition(change,next,
                sign(ownerKey,GroupStatements.actor(parent,change,next)),
                sign(ownerKey,GroupStatements.coordinator(parent,change,next)))
        }
        repeat(11) {
            val event=profile(current)
            assertEquals(GroupApply.ACCEPTED,trusted.apply(current.groupId,event))
            current=event.next
        }
        assertEquals(12L,current.revision)
        val history=HistoricalGroupAuthority(records)
        history.anchorExistingBaseline(current,owner,ByteArray(32))
        val next=profile(current)
        val untrusted=GroupLedger(records,GroupTrustedPeer {false},owner.memberId)
        assertEquals(GroupApply.REJECTED,untrusted.apply(current.groupId,next))
        assertEquals(12L,untrusted.state(current.groupId)!!.revision)
        assertEquals(GroupApply.ACCEPTED,untrusted.applySnapshot(current.groupId,
            GroupSnapshot(next.next,listOf(next)),historicalPeer={member,revision ->
                history.matchesAt(current.groupId,member,revision)
            }))
        assertEquals(13L,untrusted.state(current.groupId)!!.revision)
    }
}
