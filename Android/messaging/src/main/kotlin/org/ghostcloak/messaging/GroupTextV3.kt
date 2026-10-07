package org.ghostcloak.messaging

import kotlinx.serialization.Serializable
import org.ghostcloak.protocol.NetworkCodec

/** Separate frame preserves the frozen v2 serialization. Replies carry references, not snapshots. */
@Serializable data class GroupTextV3(
    val version:Int=3,val groupId:String,val epoch:Long,val senderMemberId:String,
    val logicalId:String,val governanceActivationDigest:ByteArray,
    val governanceSequence:Long,val governanceHeadDigest:ByteArray,
    val policyDigest:ByteArray,val text:String,val replyToLogicalId:String,
) { override fun toString()="GroupTextV3(redacted)" }

internal object GroupTextV3Codec {
    const val MAX_BYTES=GroupTextV2Codec.MAX_BYTES+64
    fun validate(value:GroupTextV3) {
        require(value.version==3 && GroupIds.valid(value.replyToLogicalId))
        GroupTextV2Codec.validate(GroupTextV2(groupId=value.groupId,epoch=value.epoch,
            senderMemberId=value.senderMemberId,logicalId=value.logicalId,
            governanceActivationDigest=value.governanceActivationDigest,
            governanceSequence=value.governanceSequence,
            governanceHeadDigest=value.governanceHeadDigest,policyDigest=value.policyDigest,
            text=value.text))
    }
    fun encode(value:GroupTextV3):ByteArray {
        validate(value)
        return NetworkCodec.encode(value).also {require(it.size in 1..MAX_BYTES)}
    }
    fun decode(bytes:ByteArray):GroupTextV3 {
        require(bytes.size in 1..MAX_BYTES)
        return NetworkCodec.decode<GroupTextV3>(bytes,MAX_BYTES).also(::validate)
    }
}

@Serializable internal data class PendingGroupTextV3(val senderDeviceId:String,val text:GroupTextV3) {
    override fun toString()="PendingGroupTextV3(redacted)"
}
