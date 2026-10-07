package org.ghostcloak.messaging

import kotlinx.serialization.Serializable
import org.ghostcloak.attachments.AttachmentDescriptor
import org.ghostcloak.attachments.AttachmentFormat
import org.ghostcloak.attachments.AttachmentKind
import org.ghostcloak.protocol.NetworkCodec

/** The descriptor, including its key and blob capability, is only carried in Signal plaintext. */
@Serializable data class GroupMediaV1(
    val version:Int=1,val groupId:String,val epoch:Long,val senderMemberId:String,
    val logicalId:String,val governanceActivationDigest:ByteArray,
    val governanceSequence:Long,val governanceHeadDigest:ByteArray,val policyDigest:ByteArray,
    val descriptor:AttachmentDescriptor,val kind:AttachmentKind,val caption:String="",
) { override fun toString()="GroupMediaV1(redacted)" }

internal object GroupMediaCodecV1 {
    const val MAX_BYTES=6144
    private val kinds=setOf(AttachmentKind.IMAGE,AttachmentKind.DOCUMENT,AttachmentKind.VOICE_NOTE)
    fun validate(value:GroupMediaV1) {
        require(value.version==1 && GroupIds.valid(value.groupId) && value.epoch>0 &&
            GroupIds.valid(value.senderMemberId) && GroupIds.valid(value.logicalId) &&
            value.governanceActivationDigest.size==32 && value.governanceHeadDigest.size==32 &&
            value.policyDigest.size==32 &&
            value.governanceSequence in 0..GroupGovernanceJournalV1.MAX_ENTRIES.toLong())
        value.descriptor.validate()
        require(value.kind in kinds && value.descriptor.kind==value.kind &&
            value.descriptor.disappearingSeconds==0)
        require(ConversationPayload.validateCaption(value.caption)==value.caption)
        val encoded=AttachmentFormat.encode(value.descriptor)
        try {require(encoded.size<=4096)} finally {encoded.fill(0)}
    }
    fun encode(value:GroupMediaV1):ByteArray {
        validate(value)
        return NetworkCodec.encode(value).also { require(it.size in 1..MAX_BYTES) }
    }
    fun decode(bytes:ByteArray):GroupMediaV1 {
        require(bytes.size in 1..MAX_BYTES)
        return NetworkCodec.decode<GroupMediaV1>(bytes,MAX_BYTES).also(::validate)
    }
}

@Serializable internal data class PendingGroupMediaV1(val senderDeviceId:String,val media:GroupMediaV1) {
    override fun toString()="PendingGroupMediaV1(redacted)"
}
