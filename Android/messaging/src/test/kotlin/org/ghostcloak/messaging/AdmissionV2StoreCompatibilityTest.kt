package org.ghostcloak.messaging

import kotlinx.serialization.Serializable
import org.ghostcloak.crypto.EndpointRecords
import org.ghostcloak.protocol.NetworkCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

class AdmissionV2StoreCompatibilityTest {
    @Serializable private data class OldPending(
        val offer:GroupControl,
        val proposal:AdmissionProposalV2,
        val approvals:List<AdmissionApprovalV2>,
        val invitationQueued:Boolean=false,
    )
    private class Records:EndpointRecords {
        private val values=mutableMapOf<String,ByteArray>()
        override fun <T> transaction(block:()->T)=block()
        override fun read(key:String)=values[key]?.copyOf()
        override fun write(key:String,value:ByteArray) {values[key]=value.copyOf()}
        override fun remove(key:String) {values.remove(key)}
        override fun keys(prefix:String)=values.keys.filter {it.startsWith(prefix)}
    }
    @Test fun oldCanonicalPendingRecordSurvivesUpgradeWithoutClearingGroupData() {
        val id=GroupIds.create()
        val owner=GroupMember(GroupIds.create(),GroupIds.create(),GroupIds.create(),
            byteArrayOf(1),ByteArray(32),GroupRole.OWNER,1)
        val state=GroupState(id,1,1,owner.memberId,owner.memberId,listOf(owner),ByteArray(32))
        val proposal=AdmissionProposalV2(groupId=id,parent=state,parentDigest=ByteArray(32),
            inviteId=GroupIds.create(),inviterId=owner.memberId,candidate=owner,
            candidateDigest=ByteArray(32),eventId=GroupIds.create(),inviterSignature=byteArrayOf(1))
        val offer=GroupControl(kind=GroupControlKind.INVITE,groupId=id,
            inviteId=proposal.inviteId)
        val records=Records()
        records.write("app/group/admission-v2/pending/$id",
            NetworkCodec.encode(OldPending(offer,proposal,emptyList(),true)))
        val upgraded=AdmissionV2Store(records).pending(id)
        assertNotNull(upgraded)
        assertEquals(proposal.inviteId,upgraded!!.proposal.inviteId)
        assertFalse(upgraded.setupQueued)
        assertEquals(true,upgraded.invitationQueued)
    }
}
