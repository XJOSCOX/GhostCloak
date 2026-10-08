package org.ghostcloak.messaging

import kotlinx.serialization.Serializable
import org.ghostcloak.attachments.AttachmentDescriptor
import org.ghostcloak.attachments.AttachmentKind
import org.ghostcloak.protocol.NetworkCodec

/** An ordering sequence is meaningful only for this sender and this exact governance head. */
@Serializable data class GroupTextV5(
    val version:Int=5,val groupId:String,val epoch:Long,val senderMemberId:String,
    val logicalId:String,val governanceActivationDigest:ByteArray,
    val governanceSequence:Long,val governanceHeadDigest:ByteArray,
    val policyDigest:ByteArray,val disappearingPolicyDigest:ByteArray,
    val disappearingSeconds:Int,val senderSequence:Long,
    val text:String,val replyToLogicalId:String?=null,
) { override fun toString()="GroupTextV5(redacted)" }

internal object GroupTextV5Codec {
    const val MAX_BYTES=4096
    fun validate(value:GroupTextV5) {
        require(value.version==5 && value.senderSequence in 1..4096 &&
            value.disappearingSeconds in GroupDisappearingRulesV1.allowedSeconds &&
            value.disappearingPolicyDigest.size==32 &&
            (value.replyToLogicalId==null || GroupIds.valid(value.replyToLogicalId)))
        GroupTextV2Codec.validate(GroupTextV2(groupId=value.groupId,epoch=value.epoch,
            senderMemberId=value.senderMemberId,logicalId=value.logicalId,
            governanceActivationDigest=value.governanceActivationDigest,
            governanceSequence=value.governanceSequence,
            governanceHeadDigest=value.governanceHeadDigest,policyDigest=value.policyDigest,
            text=value.text))
    }
    fun encode(value:GroupTextV5):ByteArray {
        validate(value)
        return NetworkCodec.encode(value).also {require(it.size in 1..MAX_BYTES)}
    }
    fun decode(bytes:ByteArray):GroupTextV5 {
        require(bytes.size in 1..MAX_BYTES)
        return NetworkCodec.decode<GroupTextV5>(bytes,MAX_BYTES).also(::validate)
    }
}

@Serializable data class GroupMediaV3(
    val version:Int=3,val groupId:String,val epoch:Long,val senderMemberId:String,
    val logicalId:String,val governanceActivationDigest:ByteArray,
    val governanceSequence:Long,val governanceHeadDigest:ByteArray,
    val policyDigest:ByteArray,val disappearingPolicyDigest:ByteArray,
    val disappearingSeconds:Int,val senderSequence:Long,
    val descriptor:AttachmentDescriptor,val kind:AttachmentKind,val caption:String="",
) { override fun toString()="GroupMediaV3(redacted)" }

internal object GroupMediaCodecV3 {
    const val MAX_BYTES=6656
    fun validate(value:GroupMediaV3) {
        require(value.version==3 && value.senderSequence in 1..4096 &&
            value.disappearingSeconds in GroupDisappearingRulesV1.allowedSeconds &&
            value.disappearingPolicyDigest.size==32)
        GroupMediaCodecV1.validate(GroupMediaV1(groupId=value.groupId,epoch=value.epoch,
            senderMemberId=value.senderMemberId,logicalId=value.logicalId,
            governanceActivationDigest=value.governanceActivationDigest,
            governanceSequence=value.governanceSequence,
            governanceHeadDigest=value.governanceHeadDigest,policyDigest=value.policyDigest,
            descriptor=value.descriptor,kind=value.kind,caption=value.caption))
    }
    fun encode(value:GroupMediaV3):ByteArray {
        validate(value)
        return NetworkCodec.encode(value).also {require(it.size in 1..MAX_BYTES)}
    }
    fun decode(bytes:ByteArray):GroupMediaV3 {
        require(bytes.size in 1..MAX_BYTES)
        return NetworkCodec.decode<GroupMediaV3>(bytes,MAX_BYTES).also(::validate)
    }
}

@Serializable internal data class PendingGroupTextV5(val senderDeviceId:String,val text:GroupTextV5) {
    override fun toString()="PendingGroupTextV5(redacted)"
}
@Serializable internal data class PendingGroupMediaV3(val senderDeviceId:String,val media:GroupMediaV3) {
    override fun toString()="PendingGroupMediaV3(redacted)"
}
