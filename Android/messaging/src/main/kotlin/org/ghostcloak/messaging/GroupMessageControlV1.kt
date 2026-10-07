package org.ghostcloak.messaging

import kotlinx.serialization.Serializable
import org.ghostcloak.protocol.NetworkCodec

/** Message-level controls are Signal content, never governance entries or server metadata. */
@Serializable enum class GroupMessageControlKindV1 { REACTION, EDIT, DELETE_BY_SENDER }

@Serializable data class GroupMessageControlV1(
    val version:Int=1,
    val groupId:String,
    val activationDigest:ByteArray,
    val governanceSequence:Long,
    val governanceHeadDigest:ByteArray,
    val actorMemberId:String,
    val targetLogicalId:String,
    val controlId:String,
    val kind:GroupMessageControlKindV1,
    val revision:Long=0,
    val emoji:String?=null,
    val text:String?=null,
) { override fun toString()="GroupMessageControlV1(redacted)" }

internal object GroupMessageControlCodecV1 {
    const val MAX_BYTES=3584
    const val MAX_REVISION=1_000_000L
    fun validate(value:GroupMessageControlV1) {
        require(value.version==1 && listOf(value.groupId,value.actorMemberId,
            value.targetLogicalId,value.controlId).all(GroupIds::valid) &&
            value.activationDigest.size==32 && value.governanceHeadDigest.size==32 &&
            value.governanceSequence in 0..GroupGovernanceJournalV1.MAX_ENTRIES.toLong())
        when(value.kind) {
            GroupMessageControlKindV1.REACTION -> require(value.revision in 1..MAX_REVISION && value.text==null &&
                (value.emoji==null || value.emoji in ConversationPayload.reactionEmoji))
            GroupMessageControlKindV1.EDIT -> {
                require(value.revision in 1..MAX_REVISION && value.emoji==null && value.text!=null)
                val bytes=TextRules.encode(value.text)
                try {require(bytes.size in 1..GroupTextCodec.MAX_TEXT_BYTES)}
                finally {bytes.fill(0)}
            }
            GroupMessageControlKindV1.DELETE_BY_SENDER ->
                require(value.revision==0L && value.emoji==null && value.text==null)
        }
    }
    fun encode(value:GroupMessageControlV1):ByteArray {
        validate(value)
        return NetworkCodec.encode(value).also {require(it.size in 1..MAX_BYTES)}
    }
    fun decode(bytes:ByteArray):GroupMessageControlV1 {
        require(bytes.size in 1..MAX_BYTES)
        return NetworkCodec.decode<GroupMessageControlV1>(bytes,MAX_BYTES).also(::validate)
    }
}
