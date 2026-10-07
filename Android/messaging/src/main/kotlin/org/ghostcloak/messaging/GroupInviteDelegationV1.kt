package org.ghostcloak.messaging

import kotlinx.serialization.Serializable
import org.ghostcloak.crypto.EndpointRecords
import org.ghostcloak.identity.RandomIdentifiers
import org.ghostcloak.protocol.NetworkCodec

/** An actor-authorized request to the coordinator, bound to one exact group snapshot. */
@Serializable data class GroupInviteDelegationV1(
    val version:Int=1,
    val groupId:String,
    val requestId:String,
    val actorId:String,
    val targetDeviceId:String,
    val stateDigest:ByteArray,
    val governanceHeadDigest:ByteArray?=null,
    val actorSignature:ByteArray=byteArrayOf(),
) {
    override fun toString()="GroupInviteDelegationV1(redacted)"
}

internal object GroupInviteDelegationRulesV1 {
    private const val DOMAIN="GhostCloak.GroupGovernanceInviteDelegation.v1"
    fun valid(value:GroupInviteDelegationV1)=value.version==1 &&
        GroupIds.valid(value.groupId) && GroupIds.valid(value.requestId) &&
        GroupIds.valid(value.actorId) && RandomIdentifiers.valid(value.targetDeviceId) &&
        value.stateDigest.size==32 &&
        (value.governanceHeadDigest==null || value.governanceHeadDigest.size==32) &&
        value.actorSignature.size in 8..80 && NetworkCodec.encode(value).size<=1024

    fun statement(value:GroupInviteDelegationV1)=GroupStatements.governanceStatement(DOMAIN,
        NetworkCodec.encode(value.copy(actorSignature=byteArrayOf())))
}

/** A bounded replay ledger. Entries are never evicted while this group remains locally stored. */
internal class GroupInviteDelegationStoreV1(private val records:EndpointRecords) {
    private fun key(groupId:String,requestId:String):String {
        require(GroupIds.valid(groupId) && GroupIds.valid(requestId))
        return "app/group/invite-delegation-v1/$groupId/$requestId"
    }
    fun processed(groupId:String,requestId:String)=records.transaction {
        records.read(key(groupId,requestId))!=null
    }
    fun markProcessed(groupId:String,requestId:String)=records.transaction {
        val path=key(groupId,requestId)
        require(records.read(path)!=null ||
            records.keys("app/group/invite-delegation-v1/$groupId/").size<128)
        records.write(path,byteArrayOf(1))
    }
}
