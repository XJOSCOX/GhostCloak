package org.ghostcloak.messaging

import kotlinx.serialization.Serializable
import org.ghostcloak.crypto.EndpointRecords
import org.ghostcloak.protocol.NetworkCodec
import java.security.MessageDigest

/** A6.1 local-only fence. This is not an activation certificate or a wire format. */
@Serializable internal data class GovernanceBarrierV1(
    val version:Int=1,
    val groupId:String,
    val activationStateRevision:Long,
    val activationStateDigest:ByteArray,
    val activationDigest:ByteArray,
    val baselineCertificateDigest:ByteArray,
) {
    override fun toString()="GovernanceBarrierV1(redacted)"
}

/** Temporary local atomicity companion; A6.2 will bind signed journal entries to it. */
@Serializable internal data class GovernanceHeadFoundationV1(
    val version:Int=1,
    val groupId:String,
    val activationDigest:ByteArray,
    val sequence:Long,
    val headDigest:ByteArray,
    val stateRevision:Long,
    val stateDigest:ByteArray,
) {
    override fun toString()="GovernanceHeadFoundationV1(redacted)"
}

/** All operations must run inside the caller's EndpointRecords transaction. */
internal class GovernanceFoundationStore(private val records:EndpointRecords) {
    private fun key(groupId:String,kind:String):String {
        require(GroupIds.valid(groupId))
        return "app/group/governance-foundation-v1/$kind/$groupId"
    }
    private fun same(a:ByteArray,b:ByteArray)=MessageDigest.isEqual(a,b)
    fun barrier(groupId:String):GovernanceBarrierV1?=records.read(key(groupId,"barrier"))?.let {
        require(it.size in 1..512)
        NetworkCodec.decode<GovernanceBarrierV1>(it,512).also {value ->
            require(value.version==1 && value.groupId==groupId &&
                value.activationStateRevision in 1..GroupStatements.MAX_EVENTS.toLong() &&
                value.activationStateDigest.size==32 && value.activationDigest.size==32 &&
                value.baselineCertificateDigest.size==32)
        }
    }
    fun head(groupId:String):GovernanceHeadFoundationV1?=records.read(key(groupId,"head"))?.let {
        require(it.size in 1..512)
        NetworkCodec.decode<GovernanceHeadFoundationV1>(it,512).also {value ->
            require(value.version==1 && value.groupId==groupId &&
                value.sequence in 0..GroupStatements.MAX_EVENTS.toLong() &&
                value.stateRevision in 1..GroupStatements.MAX_EVENTS.toLong() &&
                value.activationDigest.size==32 && value.headDigest.size==32 &&
                value.stateDigest.size==32)
        }
    }
    fun install(barrier:GovernanceBarrierV1) {
        require(this.barrier(barrier.groupId)==null && head(barrier.groupId)==null)
        require(barrier.version==1 && barrier.activationStateDigest.size==32 &&
            barrier.activationDigest.size==32 && barrier.baselineCertificateDigest.size==32)
        val head=GovernanceHeadFoundationV1(groupId=barrier.groupId,
            activationDigest=barrier.activationDigest,sequence=0,
            headDigest=barrier.activationDigest,stateRevision=barrier.activationStateRevision,
            stateDigest=barrier.activationStateDigest)
        val barrierBytes=NetworkCodec.encode(barrier)
        val headBytes=NetworkCodec.encode(head)
        require(barrierBytes.size<=512 && headBytes.size<=512)
        records.write(key(barrier.groupId,"head"),headBytes)
        records.write(key(barrier.groupId,"barrier"),barrierBytes)
    }
    fun matches(expected:GovernanceHeadFoundationV1,actual:GovernanceHeadFoundationV1):Boolean=
        expected.version==actual.version && expected.groupId==actual.groupId &&
            same(expected.activationDigest,actual.activationDigest) &&
            expected.sequence==actual.sequence && same(expected.headDigest,actual.headDigest) &&
            expected.stateRevision==actual.stateRevision && same(expected.stateDigest,actual.stateDigest)

    /** Returns false for stale or mismatched expected heads. No network or caller callback runs here. */
    fun advance(expected:GovernanceHeadFoundationV1,next:GroupState,nextHeadDigest:ByteArray):Boolean {
        require(nextHeadDigest.size==32)
        val current=head(expected.groupId) ?: return false
        val fence=barrier(expected.groupId) ?: return false
        if(!matches(expected,current) || !same(current.activationDigest,fence.activationDigest) ||
            same(current.headDigest,nextHeadDigest) ||
            next.groupId!=expected.groupId || current.stateRevision+1!=next.revision ||
            current.sequence>=GroupStatements.MAX_EVENTS ||
            current.sequence+1!=next.revision-fence.activationStateRevision)
            return false
        val advanced=current.copy(sequence=current.sequence+1,headDigest=nextHeadDigest,
            stateRevision=next.revision,stateDigest=GroupStatements.digest(next))
        val bytes=NetworkCodec.encode(advanced)
        require(bytes.size<=512)
        records.write(key(expected.groupId,"head"),bytes)
        return true
    }
}
