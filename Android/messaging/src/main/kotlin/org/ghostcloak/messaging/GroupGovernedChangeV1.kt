package org.ghostcloak.messaging

import kotlinx.serialization.Serializable
import org.ghostcloak.crypto.EndpointRecords
import org.ghostcloak.protocol.NetworkCodec
import java.security.MessageDigest

/** Actor signs a frozen v1 transition before the coordinator can prepare its governance entry. */
@Serializable data class GroupGovernedChangeProposalV1(
    val version:Int=1,val groupId:String,val activationDigest:ByteArray,
    val parentSequence:Long,val parentHeadDigest:ByteArray,
    val parentStateDigest:ByteArray,val transition:GroupTransition,
) { override fun toString()="GroupGovernedChangeProposalV1(redacted)" }

/** Consent is scoped to the exact pre-state, governance head, change and resulting state. */
@Serializable data class GroupOwnershipRequestV1(
    val version:Int=1,val groupId:String,val activationDigest:ByteArray,
    val parentSequence:Long,val parentHeadDigest:ByteArray,
    val parentStateDigest:ByteArray,val transition:GroupTransition,
) { override fun toString()="GroupOwnershipRequestV1(redacted)" }

@Serializable data class GroupOwnershipDecisionV1(
    val version:Int=1,val request:GroupOwnershipRequestV1,val accepted:Boolean,
    val targetSignature:ByteArray=byteArrayOf(),
) { override fun toString()="GroupOwnershipDecisionV1(redacted)" }

internal class GroupGovernedChangeStoreV1(private val records:EndpointRecords) {
    private fun key(groupId:String,kind:String):String {
        require(GroupIds.valid(groupId))
        return "app/group/governed-change-v1/$kind/$groupId"
    }
    private inline fun <reified T> load(groupId:String,kind:String):T?=records.transaction {
        records.read(key(groupId,kind))?.let {NetworkCodec.decode<T>(it,12_000)}
    }
    private fun save(groupId:String,kind:String,value:Any)=records.transaction {
        val bytes=when(value) {
            is GroupGovernedChangeProposalV1 -> NetworkCodec.encode(value)
            is GroupGovernanceEntryV1 -> NetworkCodec.encode(value)
            is GroupOwnershipRequestV1 -> NetworkCodec.encode(value)
            else -> error("unsupported_group_change_record")
        }
        require(bytes.size<=12_000)
        val path=key(groupId,kind)
        require(records.read(path)!=null ||
            records.keys("app/group/governed-change-v1/$kind/").size<64)
        records.write(path,bytes)
    }
    fun own(groupId:String)=load<GroupGovernedChangeProposalV1>(groupId,"own")
    fun prepared(groupId:String)=load<GroupGovernanceEntryV1>(groupId,"prepared")
    fun outgoingTransfer(groupId:String)=load<GroupOwnershipRequestV1>(groupId,"transfer-out")
    fun incomingTransfer(groupId:String)=load<GroupOwnershipRequestV1>(groupId,"transfer-in")
    fun saveOwn(value:GroupGovernedChangeProposalV1)=save(value.groupId,"own",value)
    fun savePrepared(value:GroupGovernanceEntryV1)=save(value.groupId,"prepared",value)
    fun saveOutgoingTransfer(value:GroupOwnershipRequestV1)=save(value.groupId,"transfer-out",value)
    fun saveIncomingTransfer(value:GroupOwnershipRequestV1)=save(value.groupId,"transfer-in",value)
    fun clear(groupId:String,kind:String)=records.transaction {records.remove(key(groupId,kind))}
    private fun sentKey(groupId:String,phase:String):String {
        require(phase in setOf("proposal","sign-request","transfer-request"))
        return key(groupId,"sent")+"/$phase"
    }
    fun sent(groupId:String,phase:String)=records.transaction {
        records.read(sentKey(groupId,phase))!=null
    }
    fun markSent(groupId:String,phase:String,outboxId:String)=records.transaction {
        records.write(sentKey(groupId,phase),outboxId.encodeToByteArray())
    }
    fun clearSent(groupId:String)=records.transaction {
        records.keys(key(groupId,"sent")+"/").forEach(records::remove)
    }
    private fun groups(kind:String)=records.transaction {
        records.keys("app/group/governed-change-v1/$kind/").take(64).mapNotNull {
            it.removePrefix("app/group/governed-change-v1/$kind/").takeIf(GroupIds::valid)
        }
    }
    fun ownGroups()=groups("own")
    fun preparedGroups()=groups("prepared")
    fun outgoingTransferGroups()=groups("transfer-out")
    fun incomingTransfers():List<GroupOwnershipRequestV1> = records.transaction {
        records.keys("app/group/governed-change-v1/transfer-in/").take(64).mapNotNull {key ->
            records.read(key)?.let {NetworkCodec.decode<GroupOwnershipRequestV1>(it,12_000)}
        }
    }
    fun matchesHead(value:GroupGovernedChangeProposalV1,head:GovernanceHeadFoundationV1,
        state:GroupState):Boolean=value.groupId==state.groupId &&
        MessageDigest.isEqual(value.activationDigest,head.activationDigest) &&
        value.parentSequence==head.sequence &&
        MessageDigest.isEqual(value.parentHeadDigest,head.headDigest) &&
        MessageDigest.isEqual(value.parentStateDigest,GroupStatements.digest(state))
}
