package org.ghostcloak.messaging

import kotlinx.serialization.Serializable
import org.ghostcloak.crypto.EndpointRecords
import org.ghostcloak.protocol.NetworkCodec
import java.security.MessageDigest

@Serializable internal data class PendingAdmissionV2(
    val offer:GroupControl,
    val proposal:AdmissionProposalV2,
    val approvals:List<AdmissionApprovalV2>,
    val invitationQueued:Boolean=false,
)

/** Bounded records in the protected endpoint store, separate from frozen v1 ledger bytes. */
internal class AdmissionV2Store(private val records:EndpointRecords) {
    private fun pendingKey(groupId:String):String {
        require(GroupIds.valid(groupId));return "app/group/admission-v2/pending/$groupId"
    }
    private fun ownKey(groupId:String,inviteId:String):String {
        require(GroupIds.valid(groupId) && GroupIds.valid(inviteId))
        return "app/group/admission-v2/own/$groupId/$inviteId"
    }
    private fun evidenceKey(groupId:String,eventId:String):String {
        require(GroupIds.valid(groupId) && GroupIds.valid(eventId))
        return "app/group/admission-v2/evidence/$groupId/$eventId"
    }
    private fun sentKey(groupId:String,inviteId:String,recipient:String):String {
        require(GroupIds.valid(groupId) && GroupIds.valid(inviteId))
        return "app/group/admission-v2/sent/$groupId/$inviteId/$recipient"
    }
    fun pending(groupId:String):PendingAdmissionV2?=records.transaction {
        records.read(pendingKey(groupId))?.let {NetworkCodec.decode(it,12_000)}
    }
    fun pendingGroups():List<String> = records.transaction {
        records.keys("app/group/admission-v2/pending/").take(64).mapNotNull {key ->
            key.removePrefix("app/group/admission-v2/pending/").takeIf(GroupIds::valid)
        }
    }
    fun savePending(groupId:String,value:PendingAdmissionV2)=records.transaction {
        require(value.proposal.groupId==groupId && value.offer.groupId==groupId &&
            value.offer.inviteId==value.proposal.inviteId && value.approvals.size<=GroupStatements.MAX_MEMBERS)
        val encoded=NetworkCodec.encode(value)
        require(encoded.size<=12_000)
        val key=pendingKey(groupId)
        require(records.read(key)!=null || records.keys("app/group/admission-v2/pending/").size<64)
        records.write(key,encoded)
    }
    fun clearPending(groupId:String)=records.transaction {records.remove(pendingKey(groupId))}
    fun own(groupId:String,inviteId:String):OwnAdmissionApprovalV2?=records.transaction {
        records.read(ownKey(groupId,inviteId))?.let {NetworkCodec.decode(it,1024)}
    }
    /** Other signers cannot stand in for this device's persisted verification. */
    fun matchesOwnApproval(parent:GroupState,certificate:AdmissionCertificateV2,localId:String):Boolean {
        if(parent.members.none {it.memberId==localId}) return false
        val proposal=certificate.proposal
        val saved=own(parent.groupId,proposal.inviteId) ?: return false
        val signed=certificate.approvals.singleOrNull {it.approverId==localId} ?: return false
        return saved.verificationVersion==2 && saved.ownMemberId==localId &&
            saved.parentRevision==parent.revision && saved.inviteId==proposal.inviteId &&
            MessageDigest.isEqual(saved.parentDigest,GroupStatements.digest(parent)) &&
            MessageDigest.isEqual(saved.proposalDigest,AdmissionV2.proposalDigest(proposal)) &&
            MessageDigest.isEqual(saved.candidateDigest,proposal.candidateDigest) &&
            NetworkCodec.encode(saved.approval).contentEquals(NetworkCodec.encode(signed))
    }
    fun saveOwn(groupId:String,value:OwnAdmissionApprovalV2)=records.transaction {
        require(value.approval.inviteId==value.inviteId && value.verificationVersion==2)
        val bytes=NetworkCodec.encode(value)
        require(bytes.size<=1024)
        require(records.keys("app/group/admission-v2/own/$groupId/").all {
            it==ownKey(groupId,value.inviteId)
        })
        val key=ownKey(groupId,value.inviteId)
        require(records.read(key)!=null || records.keys("app/group/admission-v2/own/").size<64)
        records.write(key,bytes)
    }
    fun removeOwn(groupId:String,inviteId:String)=records.transaction {
        records.remove(ownKey(groupId,inviteId))
        clearQueued(groupId,inviteId)
    }
    fun ownKeys():List<Pair<String,String>> = records.transaction {
        records.keys("app/group/admission-v2/own/").take(64).mapNotNull {key ->
            val parts=key.removePrefix("app/group/admission-v2/own/").split('/')
            if(parts.size==2 && GroupIds.valid(parts[0]) && GroupIds.valid(parts[1])) parts[0] to parts[1]
            else null
        }
    }
    fun evidence(groupId:String,eventId:String):AdmissionCertificateV2?=records.transaction {
        records.read(evidenceKey(groupId,eventId))?.let {
            NetworkCodec.decode<AdmissionCertificateV2>(it,AdmissionV2.MAX_CERTIFICATE_BYTES)
        }
    }
    fun saveEvidence(groupId:String,eventId:String,value:AdmissionCertificateV2)=records.transaction {
        require(value.proposal.groupId==groupId && value.proposal.eventId==eventId)
        val bytes=NetworkCodec.encode(value)
        require(bytes.size<=AdmissionV2.MAX_CERTIFICATE_BYTES)
        val key=evidenceKey(groupId,eventId)
        require(records.read(key)!=null ||
            records.keys("app/group/admission-v2/evidence/$groupId/").size<GroupStatements.MAX_EVENTS)
        records.write(key,bytes)
    }
    fun queued(groupId:String,inviteId:String,recipient:String):Boolean=records.transaction {
        records.read(sentKey(groupId,inviteId,recipient))!=null
    }
    fun markQueued(groupId:String,inviteId:String,recipient:String,outboxId:String)=records.transaction {
        val key=sentKey(groupId,inviteId,recipient)
        require(records.read(key)!=null ||
            records.keys("app/group/admission-v2/sent/$groupId/$inviteId/").size<=GroupStatements.MAX_MEMBERS)
        records.write(key,outboxId.toByteArray())
    }
    fun clearQueued(groupId:String,inviteId:String)=records.transaction {
        require(GroupIds.valid(groupId) && GroupIds.valid(inviteId))
        records.keys("app/group/admission-v2/sent/$groupId/$inviteId/").forEach(records::remove)
    }
}
