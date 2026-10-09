package org.ghostcloak.messaging

import kotlinx.serialization.Serializable
import org.ghostcloak.protocol.GhostCloakIds
import org.ghostcloak.protocol.NetworkCodec
import java.nio.ByteBuffer
import java.security.MessageDigest

/** An owner-signed, admission-scoped routing hint. It never creates a direct contact. */
@Serializable data class GroupOwnerRouteV1(val memberId:String,val ghostCloakId:String)

@Serializable data class GroupOwnerIntroductionV1(
    val version:Int=1,
    val groupId:String,
    val parentDigest:ByteArray,
    val inviteId:String,
    val candidate:GroupMember,
    val routes:List<GroupOwnerRouteV1>,
    val ownerSignature:ByteArray,
) {
    override fun toString()="GroupOwnerIntroductionV1(redacted)"
}

object GroupOwnerIntroductionRulesV1 {
    private val domain="GhostCloak.GroupOwnerIntroduction.v1".toByteArray(Charsets.US_ASCII)
    const val MAX_BYTES=2_048

    fun statement(value:GroupOwnerIntroductionV1):ByteArray {
        val body=NetworkCodec.encode(value.copy(ownerSignature=byteArrayOf()))
        require(body.size<=MAX_BYTES)
        return ByteBuffer.allocate(4+4+domain.size+4+body.size).putInt(1).putInt(domain.size).put(domain)
            .putInt(body.size).put(body).array()
    }

    fun verify(value:GroupOwnerIntroductionV1,parent:GroupState):Boolean=runCatching {
        GroupStatements.validate(parent)
        require(value.version==1 && value.groupId==parent.groupId &&
            MessageDigest.isEqual(value.parentDigest,GroupStatements.digest(parent)) &&
            GroupIds.valid(value.inviteId) && value.parentDigest.size==32 &&
            value.candidate.role==GroupRole.MEMBER && value.candidate.joinedEpoch==parent.epoch+1 &&
            parent.members.none {it.memberId==value.candidate.memberId ||
                it.accountId==value.candidate.accountId || it.deviceId==value.candidate.deviceId})
        val expected=(parent.members.map {it.memberId}+value.candidate.memberId).sorted()
        require(value.routes.size==expected.size && value.routes.map {it.memberId}.sorted()==expected &&
            value.routes.map {it.ghostCloakId}.distinct().size==value.routes.size &&
            value.routes.all {GroupIds.valid(it.memberId) && GhostCloakIds.valid(it.ghostCloakId)})
        require(NetworkCodec.encode(value).size<=MAX_BYTES)
        val owner=parent.members.single {it.memberId==parent.ownerId}
        GroupStatements.verify(owner.authPublicKey,statement(value),value.ownerSignature)
    }.getOrDefault(false)

    /** Only queues a signed future handshake; it grants no membership or authority. */
    fun plausibleFutureParent(value:GroupOwnerIntroductionV1,parent:GroupState,
        current:GroupState):Boolean = verify(value,parent) &&
        parent.groupId==current.groupId && parent.ownerId==current.ownerId &&
        parent.coordinatorId==current.coordinatorId &&
        current.lifecycle==GroupLifecycle.ACTIVE && parent.lifecycle==GroupLifecycle.ACTIVE &&
        parent.revision>current.revision &&
        parent.revision-current.revision<=GroupStatements.MAX_MEMBERS &&
        parent.members.size>=current.members.size &&
        current.members.all {known -> parent.members.any {candidate ->
            candidate.memberId==known.memberId &&
                MessageDigest.isEqual(GroupStatements.digestMember(candidate),
                    GroupStatements.digestMember(known))
        }}
}
