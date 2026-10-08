package org.ghostcloak.messaging

import kotlinx.serialization.Serializable
import org.ghostcloak.attachments.AttachmentDescriptor
import org.ghostcloak.attachments.AttachmentKind
import org.ghostcloak.protocol.NetworkCodec

/** Frozen v2/v3 text frames stay untouched; a timer-bearing text or reply uses this frame. */
@Serializable data class GroupTextV4(
    val version:Int=4,val groupId:String,val epoch:Long,val senderMemberId:String,
    val logicalId:String,val governanceActivationDigest:ByteArray,
    val governanceSequence:Long,val governanceHeadDigest:ByteArray,
    val policyDigest:ByteArray,val disappearingPolicyDigest:ByteArray,
    val disappearingSeconds:Int,val text:String,val replyToLogicalId:String?=null,
) { override fun toString()="GroupTextV4(redacted)" }

internal object GroupTextV4Codec {
    const val MAX_BYTES=4096
    fun validate(value:GroupTextV4) {
        require(value.version==4 && value.disappearingSeconds in
            GroupDisappearingRulesV1.allowedSeconds && value.disappearingSeconds!=0 &&
            value.disappearingPolicyDigest.size==32 &&
            (value.replyToLogicalId==null || GroupIds.valid(value.replyToLogicalId)))
        GroupTextV2Codec.validate(GroupTextV2(groupId=value.groupId,epoch=value.epoch,
            senderMemberId=value.senderMemberId,logicalId=value.logicalId,
            governanceActivationDigest=value.governanceActivationDigest,
            governanceSequence=value.governanceSequence,
            governanceHeadDigest=value.governanceHeadDigest,policyDigest=value.policyDigest,
            text=value.text))
    }
    fun encode(value:GroupTextV4):ByteArray {
        validate(value)
        return NetworkCodec.encode(value).also {require(it.size in 1..MAX_BYTES)}
    }
    fun decode(bytes:ByteArray):GroupTextV4 {
        require(bytes.size in 1..MAX_BYTES)
        return NetworkCodec.decode<GroupTextV4>(bytes,MAX_BYTES).also(::validate)
    }
}

@Serializable data class GroupMediaV2(
    val version:Int=2,val groupId:String,val epoch:Long,val senderMemberId:String,
    val logicalId:String,val governanceActivationDigest:ByteArray,
    val governanceSequence:Long,val governanceHeadDigest:ByteArray,
    val policyDigest:ByteArray,val disappearingPolicyDigest:ByteArray,
    val disappearingSeconds:Int,
    val descriptor:AttachmentDescriptor,val kind:AttachmentKind,val caption:String="",
) { override fun toString()="GroupMediaV2(redacted)" }

internal object GroupMediaCodecV2 {
    const val MAX_BYTES=6656
    fun validate(value:GroupMediaV2) {
        require(value.version==2 && value.disappearingSeconds in
            GroupDisappearingRulesV1.allowedSeconds && value.disappearingSeconds!=0 &&
            value.disappearingPolicyDigest.size==32)
        GroupMediaCodecV1.validate(GroupMediaV1(groupId=value.groupId,epoch=value.epoch,
            senderMemberId=value.senderMemberId,logicalId=value.logicalId,
            governanceActivationDigest=value.governanceActivationDigest,
            governanceSequence=value.governanceSequence,
            governanceHeadDigest=value.governanceHeadDigest,policyDigest=value.policyDigest,
            descriptor=value.descriptor,kind=value.kind,caption=value.caption))
    }
    fun encode(value:GroupMediaV2):ByteArray {
        validate(value)
        return NetworkCodec.encode(value).also {require(it.size in 1..MAX_BYTES)}
    }
    fun decode(bytes:ByteArray):GroupMediaV2 {
        require(bytes.size in 1..MAX_BYTES)
        return NetworkCodec.decode<GroupMediaV2>(bytes,MAX_BYTES).also(::validate)
    }
}

@Serializable internal data class PendingGroupTextV4(val senderDeviceId:String,val text:GroupTextV4) {
    override fun toString()="PendingGroupTextV4(redacted)"
}
@Serializable internal data class PendingGroupMediaV2(val senderDeviceId:String,val media:GroupMediaV2) {
    override fun toString()="PendingGroupMediaV2(redacted)"
}
