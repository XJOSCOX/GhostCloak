package org.ghostcloak.messaging

import kotlinx.serialization.Serializable
import org.ghostcloak.protocol.DeviceAuth
import org.ghostcloak.protocol.NetworkCodec
import java.security.MessageDigest

/** A group-local policy. The activation digest separates identical settings in different groups. */
@Serializable data class GroupDisappearingPolicyV1(val version: Int = 1, val seconds: Int = 0) {
    override fun toString() = "GroupDisappearingPolicyV1(redacted)"
}

@Serializable data class GroupGovernanceTimerEntryV1(
    val version: Int = 1,
    val groupId: String,
    val activationDigest: ByteArray,
    val sequence: Long,
    val previousHeadDigest: ByteArray,
    val eventId: String,
    val stateRevision: Long,
    val stateDigest: ByteArray,
    val actorId: String,
    val preTimerDigest: ByteArray,
    val postTimerDigest: ByteArray,
    val seconds: Int,
    val actorSignature: ByteArray,
    val coordinatorSignature: ByteArray,
) { override fun toString() = "GroupGovernanceTimerEntryV1(redacted)" }

@Serializable data class GroupGovernanceTimerResyncResponseV1(
    val version:Int=1,val groupId:String,val activationDigest:ByteArray,
    val requesterSequence:Long,val requesterHeadDigest:ByteArray,
    val startSequence:Long,val startHeadDigest:ByteArray,
    val timerEntry:GroupGovernanceTimerEntryV1,
    val endSequence:Long,val endHeadDigest:ByteArray,val more:Boolean,
) { override fun toString()="GroupGovernanceTimerResyncResponseV1(redacted)" }

internal object GroupDisappearingRulesV1 {
    const val MAX_ENTRY_BYTES = 1536
    val allowedSeconds = setOf(0, 30, 300, 3600, 86400, 604800)
    fun initial() = GroupDisappearingPolicyV1()
    fun validate(value: GroupDisappearingPolicyV1) {
        require(value.version == 1 && value.seconds in allowedSeconds)
    }
    fun digest(activationDigest: ByteArray, value: GroupDisappearingPolicyV1): ByteArray {
        require(activationDigest.size == 32)
        validate(value)
        return DeviceAuth.digest("GhostCloak.GroupDisappearingPolicy.v1".encodeToByteArray() +
            activationDigest + NetworkCodec.encode(value))
    }
    fun change(prior: GroupDisappearingPolicyV1, seconds: Int): GroupDisappearingPolicyV1 {
        validate(prior)
        require(seconds in allowedSeconds && seconds != prior.seconds)
        return GroupDisappearingPolicyV1(seconds = seconds)
    }
    private fun body(value: GroupGovernanceTimerEntryV1) = NetworkCodec.encode(value.copy(
        actorSignature = byteArrayOf(), coordinatorSignature = byteArrayOf()))
    fun actorStatement(value: GroupGovernanceTimerEntryV1) = GroupStatements.governanceStatement(
        "GhostCloak.GroupGovernanceTimerActor.v1", body(value))
    fun coordinatorStatement(value: GroupGovernanceTimerEntryV1) = GroupStatements.governanceStatement(
        "GhostCloak.GroupGovernanceTimerCoordinator.v1", body(value))
    fun entryDigest(value: GroupGovernanceTimerEntryV1) = DeviceAuth.digest(NetworkCodec.encode(value))
    fun validResync(value:GroupGovernanceTimerResyncResponseV1):Boolean =
        value.version==1 && value.groupId==value.timerEntry.groupId &&
            value.activationDigest.size==32 && value.requesterHeadDigest.size==32 &&
            value.startHeadDigest.size==32 && value.endHeadDigest.size==32 &&
            value.startSequence==value.requesterSequence+1 &&
            value.endSequence==value.startSequence &&
            value.endSequence in 1..GroupGovernanceJournalV1.MAX_ENTRIES.toLong() &&
            value.timerEntry.sequence==value.startSequence &&
            MessageDigest.isEqual(value.timerEntry.previousHeadDigest,value.startHeadDigest) &&
            MessageDigest.isEqual(value.timerEntry.activationDigest,value.activationDigest) &&
            MessageDigest.isEqual(entryDigest(value.timerEntry),value.endHeadDigest)
    fun verifyEntry(value: GroupGovernanceTimerEntryV1, state: GroupState,
        head: GovernanceHeadFoundationV1, prior: GroupDisappearingPolicyV1): Boolean = runCatching {
        require(value.version == 1 && value.groupId == state.groupId &&
            MessageDigest.isEqual(value.activationDigest, head.activationDigest) &&
            value.sequence == head.sequence + 1 &&
            MessageDigest.isEqual(value.previousHeadDigest, head.headDigest) &&
            GroupIds.valid(value.eventId) && value.stateRevision == state.revision &&
            MessageDigest.isEqual(value.stateDigest, GroupStatements.digest(state)) &&
            MessageDigest.isEqual(value.preTimerDigest, digest(head.activationDigest, prior)))
        val actor = state.members.single { it.memberId == value.actorId }
        val coordinator = state.members.single { it.memberId == state.coordinatorId }
        require(state.lifecycle == GroupLifecycle.ACTIVE && actor.role != GroupRole.MEMBER)
        val next = change(prior, value.seconds)
        require(MessageDigest.isEqual(value.postTimerDigest, digest(head.activationDigest, next)) &&
            NetworkCodec.encode(value).size <= MAX_ENTRY_BYTES &&
            GroupStatements.verify(actor.authPublicKey, actorStatement(value), value.actorSignature) &&
            GroupStatements.verify(coordinator.authPublicKey, coordinatorStatement(value),
                value.coordinatorSignature))
        true
    }.getOrDefault(false)
}
