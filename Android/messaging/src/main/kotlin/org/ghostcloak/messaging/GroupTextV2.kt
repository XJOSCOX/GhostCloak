package org.ghostcloak.messaging

import kotlinx.serialization.Serializable
import org.ghostcloak.protocol.NetworkCodec

/** User content stays inside a recipient-specific authenticated Signal envelope. */
@Serializable data class GroupTextV2(
    val version:Int=2,val groupId:String,val epoch:Long,val senderMemberId:String,
    val logicalId:String,val governanceActivationDigest:ByteArray,
    val governanceSequence:Long,val governanceHeadDigest:ByteArray,
    val policyDigest:ByteArray,val text:String,
) { override fun toString()="GroupTextV2(redacted)" }

internal object GroupTextV2Codec {
    const val MAX_TEXT_BYTES=GroupTextCodec.MAX_TEXT_BYTES
    const val MAX_BYTES=3584
    fun validate(value:GroupTextV2) {
        require(value.version==2 && GroupIds.valid(value.groupId) && value.epoch>0 &&
            GroupIds.valid(value.senderMemberId) && GroupIds.valid(value.logicalId) &&
            value.governanceActivationDigest.size==32 &&
            value.governanceSequence in 0..GroupGovernanceJournalV1.MAX_ENTRIES.toLong() &&
            value.governanceHeadDigest.size==32 && value.policyDigest.size==32)
        val bytes=TextRules.encode(value.text)
        try {require(bytes.size in 1..MAX_TEXT_BYTES)} finally {bytes.fill(0)}
    }
    fun encode(value:GroupTextV2):ByteArray {
        validate(value)
        return NetworkCodec.encode(value).also {require(it.size in 1..MAX_BYTES)}
    }
    fun decode(bytes:ByteArray):GroupTextV2 {
        require(bytes.size in 1..MAX_BYTES)
        return NetworkCodec.decode<GroupTextV2>(bytes,MAX_BYTES).also(::validate)
    }
}

@Serializable internal data class PendingGroupTextV2(val senderDeviceId:String,val text:GroupTextV2) {
    override fun toString()="PendingGroupTextV2(redacted)"
}

@Serializable internal data class GroupTextV2Binding(
    val activationDigest:ByteArray,val sequence:Long,val headDigest:ByteArray,
    val policyDigest:ByteArray,
) {
    fun matches(head:GovernanceHeadFoundationV1,policy:GroupGovernancePolicyV1)=
        activationDigest.contentEquals(head.activationDigest) && sequence==head.sequence &&
            headDigest.contentEquals(head.headDigest) &&
            policyDigest.contentEquals(GroupGovernancePolicyRulesV1.digest(head.activationDigest,policy))
}
