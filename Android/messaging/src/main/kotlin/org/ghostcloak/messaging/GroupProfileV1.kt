package org.ghostcloak.messaging

import kotlinx.serialization.Serializable
import org.ghostcloak.protocol.DeviceAuth
import org.ghostcloak.protocol.NetworkCodec
import java.security.MessageDigest

/** Canonical group identity contains a commitment, never JPEG bytes. */
@Serializable data class GroupProfilePhotoRefV1(
    val version: Int = 1,
    val digest: ByteArray,
    val length: Int,
) { override fun toString() = "GroupProfilePhotoRefV1(redacted)" }

@Serializable data class GroupProfileV1(
    val version: Int = 1,
    val name: String = "Group",
    val about: String = "",
    val photo: GroupProfilePhotoRefV1? = null,
) { override fun toString() = "GroupProfileV1(redacted)" }

@Serializable data class GroupGovernanceProfileEntryV1(
    val version: Int = 1,
    val groupId: String,
    val activationDigest: ByteArray,
    val sequence: Long,
    val previousHeadDigest: ByteArray,
    val eventId: String,
    val stateRevision: Long,
    val stateDigest: ByteArray,
    val actorId: String,
    val preProfileDigest: ByteArray,
    val postProfileDigest: ByteArray,
    val profile: GroupProfileV1,
    val actorSignature: ByteArray,
    val coordinatorSignature: ByteArray,
) { override fun toString() = "GroupGovernanceProfileEntryV1(redacted)" }

/** Evidence only: possession of these bytes never advances the governance head. */
@Serializable data class GroupProfilePhotoCompanionV1(
    val version: Int = 1,
    val groupId: String,
    val activationDigest: ByteArray,
    val governanceSequence: Long,
    val governanceHeadDigest: ByteArray,
    val profileDigest: ByteArray,
    val photoDigest: ByteArray,
    val photoLength: Int,
    val photoBytes: ByteArray,
) { override fun toString() = "GroupProfilePhotoCompanionV1(redacted)" }

@Serializable data class GroupProfilePhotoRequestV1(
    val version: Int = 1,
    val groupId: String,
    val activationDigest: ByteArray,
    val governanceSequence: Long,
    val governanceHeadDigest: ByteArray,
    val profileDigest: ByteArray,
    val photoDigest: ByteArray,
    val requesterMemberId: String,
) { override fun toString() = "GroupProfilePhotoRequestV1(redacted)" }

@Serializable data class GroupGovernanceProfileResyncResponseV1(
    val version: Int = 1,
    val groupId: String,
    val activationDigest: ByteArray,
    val requesterSequence: Long,
    val requesterHeadDigest: ByteArray,
    val startSequence: Long,
    val startHeadDigest: ByteArray,
    val profileEntry: GroupGovernanceProfileEntryV1,
    val endSequence: Long,
    val endHeadDigest: ByteArray,
    val more: Boolean,
) { override fun toString() = "GroupGovernanceProfileResyncResponseV1(redacted)" }

internal object GroupProfileRulesV1 {
    const val MAX_NAME_UTF8 = 64
    const val MAX_ABOUT_UTF8 = 256
    const val MAX_ENTRY_BYTES = 1536
    const val MAX_COMPANION_BYTES = 9000
    val default = GroupProfileV1()

    private fun validText(value: String, maxBytes: Int, empty: Boolean): Boolean {
        if (value != value.trim() || (!empty && value.isEmpty()) ||
            value.toByteArray(Charsets.UTF_8).size > maxBytes ||
            value.any { Character.isISOControl(it) || it == '\u061c' ||
                it in '\u200e'..'\u200f' || it in '\u202a'..'\u202e' ||
                it in '\u2066'..'\u2069' }) return false
        // Kotlin's default UTF-8 encoder replaces unpaired surrogates. Reject them.
        return value.encodeToByteArray().decodeToString() == value
    }

    fun normalize(name: String, about: String, photo: GroupProfilePhotoRefV1?): GroupProfileV1 {
        require(sequenceOf(name,about).all {value -> value.none {
            Character.isISOControl(it) || it == '\u061c' ||
                it in '\u200e'..'\u200f' || it in '\u202a'..'\u202e' ||
                it in '\u2066'..'\u2069'
        }})
        return GroupProfileV1(name = name.trim(), about = about.trim(), photo = photo).also(::validate)
    }

    fun validate(value: GroupProfileV1) {
        require(value.version == 1 && validText(value.name, MAX_NAME_UTF8, false) &&
            validText(value.about, MAX_ABOUT_UTF8, true))
        value.photo?.let {
            require(it.version == 1 && it.digest.size == 32 &&
                it.length in 128..ProfileRules.MAX_PHOTO_BYTES)
        }
    }

    fun digest(activationDigest: ByteArray, value: GroupProfileV1): ByteArray {
        require(activationDigest.size == 32)
        validate(value)
        return DeviceAuth.digest("GhostCloak.GroupProfile.v1".encodeToByteArray() +
            activationDigest + NetworkCodec.encode(value))
    }
    fun validPhotoBytes(bytes:ByteArray,ref:GroupProfilePhotoRefV1):Boolean=runCatching {
        require(bytes.size==ref.length && bytes.size<=ProfileRules.MAX_PHOTO_BYTES &&
            MessageDigest.isEqual(DeviceAuth.digest(bytes),ref.digest))
        ProfileRules.photo(bytes)
        true
    }.getOrDefault(false)

    private fun body(value: GroupGovernanceProfileEntryV1) = NetworkCodec.encode(value.copy(
        actorSignature = byteArrayOf(), coordinatorSignature = byteArrayOf()))
    fun actorStatement(value: GroupGovernanceProfileEntryV1) = GroupStatements.governanceStatement(
        "GhostCloak.GroupGovernanceProfileActor.v1", body(value))
    fun coordinatorStatement(value: GroupGovernanceProfileEntryV1) = GroupStatements.governanceStatement(
        "GhostCloak.GroupGovernanceProfileCoordinator.v1", body(value))
    fun entryDigest(value: GroupGovernanceProfileEntryV1) = DeviceAuth.digest(NetworkCodec.encode(value))
    fun unsignedApplied(entry:GroupGovernanceProfileEntryV1,memberId:String)=
        GroupGovernanceEntryAppliedAckV1(groupId=entry.groupId,
            activationDigest=entry.activationDigest,sequence=entry.sequence,
            entryDigest=entryDigest(entry),memberId=memberId,signature=byteArrayOf())
    fun verifyApplied(value:GroupGovernanceEntryAppliedAckV1,
        entry:GroupGovernanceProfileEntryV1,state:GroupState):Boolean=runCatching {
        require(value.version==1 && value.groupId==entry.groupId &&
            MessageDigest.isEqual(value.activationDigest,entry.activationDigest) &&
            value.sequence==entry.sequence &&
            MessageDigest.isEqual(value.entryDigest,entryDigest(entry)))
        val member=state.members.single {it.memberId==value.memberId}
        require(GroupStatements.verify(member.authPublicKey,
            GroupGovernanceV1.appliedStatement(value),value.signature))
        true
    }.getOrDefault(false)

    fun verifyEntry(value: GroupGovernanceProfileEntryV1, state: GroupState,
        head: GovernanceHeadFoundationV1, current: GroupProfileV1): Boolean = runCatching {
        require(value.version == 1 && value.groupId == state.groupId &&
            MessageDigest.isEqual(value.activationDigest, head.activationDigest) &&
            value.sequence == head.sequence + 1 &&
            MessageDigest.isEqual(value.previousHeadDigest, head.headDigest) &&
            value.stateRevision == state.revision &&
            MessageDigest.isEqual(value.stateDigest, GroupStatements.digest(state)) &&
            GroupIds.valid(value.eventId) && state.lifecycle == GroupLifecycle.ACTIVE &&
            MessageDigest.isEqual(value.preProfileDigest, digest(head.activationDigest, current)) &&
            MessageDigest.isEqual(value.postProfileDigest, digest(head.activationDigest, value.profile)) &&
            !MessageDigest.isEqual(value.preProfileDigest, value.postProfileDigest) &&
            NetworkCodec.encode(value).size <= MAX_ENTRY_BYTES)
        val actor = state.members.single { it.memberId == value.actorId }
        require(actor.role in setOf(GroupRole.OWNER, GroupRole.ADMIN))
        val coordinator = state.members.single { it.memberId == state.coordinatorId }
        require(GroupStatements.verify(actor.authPublicKey, actorStatement(value), value.actorSignature) &&
            GroupStatements.verify(coordinator.authPublicKey, coordinatorStatement(value),
                value.coordinatorSignature))
        true
    }.getOrDefault(false)

    fun validCompanion(value: GroupProfilePhotoCompanionV1): Boolean = runCatching {
        require(value.version == 1 && GroupIds.valid(value.groupId) &&
            value.activationDigest.size == 32 && value.governanceHeadDigest.size == 32 &&
            value.profileDigest.size == 32 && value.photoDigest.size == 32 &&
            value.governanceSequence in 0..GroupGovernanceJournalV1.MAX_ENTRIES.toLong() &&
            value.photoLength == value.photoBytes.size &&
            value.photoLength in 128..ProfileRules.MAX_PHOTO_BYTES &&
            MessageDigest.isEqual(DeviceAuth.digest(value.photoBytes), value.photoDigest) &&
            NetworkCodec.encode(value).size <= MAX_COMPANION_BYTES)
        ProfileRules.photo(value.photoBytes)
        true
    }.getOrDefault(false)

    fun validPhotoRequest(value: GroupProfilePhotoRequestV1): Boolean =
        value.version == 1 && GroupIds.valid(value.groupId) &&
        GroupIds.valid(value.requesterMemberId) &&
        value.activationDigest.size == 32 && value.governanceHeadDigest.size == 32 &&
        value.profileDigest.size == 32 && value.photoDigest.size == 32 &&
        value.governanceSequence in 0..GroupGovernanceJournalV1.MAX_ENTRIES.toLong()

    fun validResync(value: GroupGovernanceProfileResyncResponseV1): Boolean =
        value.version == 1 && GroupIds.valid(value.groupId) &&
        value.activationDigest.size == 32 && value.requesterHeadDigest.size == 32 &&
        value.startHeadDigest.size == 32 && value.endHeadDigest.size == 32 &&
        value.requesterSequence in 0 until GroupGovernanceJournalV1.MAX_ENTRIES.toLong() &&
        value.startSequence == value.requesterSequence + 1 &&
        value.endSequence == value.startSequence &&
        (!value.more || value.endSequence < GroupGovernanceJournalV1.MAX_ENTRIES) &&
        value.profileEntry.sequence == value.startSequence &&
        NetworkCodec.encode(value.profileEntry).size <= MAX_ENTRY_BYTES &&
        value.profileEntry.groupId == value.groupId &&
        MessageDigest.isEqual(value.profileEntry.activationDigest, value.activationDigest) &&
        MessageDigest.isEqual(value.profileEntry.previousHeadDigest, value.startHeadDigest) &&
        MessageDigest.isEqual(entryDigest(value.profileEntry), value.endHeadDigest)
}
