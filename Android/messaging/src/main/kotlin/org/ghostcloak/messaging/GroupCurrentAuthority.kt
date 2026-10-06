package org.ghostcloak.messaging

import kotlinx.serialization.Serializable
import org.ghostcloak.crypto.EndpointRecords
import org.ghostcloak.protocol.NetworkCodec
import org.ghostcloak.protocol.DeviceAuth
import java.security.MessageDigest

/** Current group-system authority only. This never grants a direct-contact relationship. */
internal class GroupCurrentAuthority(private val records: EndpointRecords) {
    @Serializable private data class Anchor(
        val groupId: String,
        val member: GroupMember,
        val admissionDigest: ByteArray,
    )

    private fun key(groupId: String, memberId: String): String {
        require(GroupIds.valid(groupId) && GroupIds.valid(memberId))
        return "app/group/current-authority/$groupId/$memberId"
    }

    /** Call only in the transaction accepting signed admission or a validated canonical ADD. */
    fun anchor(state: GroupState, member: GroupMember, signedAdmissionDigest: ByteArray) {
        require(signedAdmissionDigest.size == 32 && state.members.any { sameIdentity(it,member) })
        records.write(key(state.groupId, member.memberId),
            NetworkCodec.encode(Anchor(state.groupId, member, signedAdmissionDigest)))
    }

    fun current(groupId: String, senderDeviceId: String, signalDigest: ByteArray): GroupMember? {
        if (!GroupIds.valid(groupId) || signalDigest.size != 32) return null
        val localId = records.read("app/group/member/$groupId")?.decodeToString() ?: return null
        val ledger = GroupLedger(records, GroupTrustedPeer { false }, localId)
        val state = ledger.state(groupId) ?: return null
        if (ledger.status(groupId) != GroupLocalStatus.ACTIVE || state.lifecycle != GroupLifecycle.ACTIVE)
            return null
        val member = state.members.singleOrNull { it.deviceId == senderDeviceId } ?: return null
        val bytes = records.read(key(groupId, member.memberId)) ?: return null
        val anchor = runCatching { NetworkCodec.decode<Anchor>(bytes, 1024) }.getOrNull() ?: return null
        if (anchor.groupId != groupId || anchor.admissionDigest.size != 32 ||
            !sameIdentity(anchor.member,member) ||
            !MessageDigest.isEqual(member.signalIdentityDigest, signalDigest)) return null
        return member
    }

    private fun sameIdentity(a:GroupMember,b:GroupMember):Boolean =
        a.memberId==b.memberId && a.accountId==b.accountId && a.deviceId==b.deviceId &&
            MessageDigest.isEqual(a.authPublicKey,b.authPublicKey) &&
            MessageDigest.isEqual(a.signalIdentityDigest,b.signalIdentityDigest)

    fun removeDeparted(state: GroupState) {
        val prefix = "app/group/current-authority/${state.groupId}/"
        val current = if (state.lifecycle == GroupLifecycle.ACTIVE) state.members.map { it.memberId }.toSet()
            else emptySet()
        records.keys(prefix).filter { it.removePrefix(prefix) !in current }.forEach(records::remove)
    }

    /** Upgrade only an already-verified member while its old direct key pin still exists. */
    fun anchorExistingBeforeBlock(deviceId:String,accountId:String) {
        val pinBytes=records.read("app/group-authority/$deviceId") ?: return
        val pin=runCatching {NetworkCodec.decode<GroupAuthorityPin>(pinBytes,512)}.getOrNull() ?: return
        if(pin.accountId!=accountId || pin.identityDigest.size!=32) return
        for(key in records.keys("app/group/member/")) {
            val groupId=key.removePrefix("app/group/member/")
            val localId=records.read(key)?.decodeToString() ?: continue
            val ledger=GroupLedger(records,GroupTrustedPeer {false},localId)
            if(ledger.status(groupId)!=GroupLocalStatus.ACTIVE) continue
            val state=ledger.state(groupId) ?: continue
            if(state.lifecycle!=GroupLifecycle.ACTIVE) continue
            val member=state.members.singleOrNull {it.deviceId==deviceId && it.accountId==accountId}
                ?: continue
            if(!MessageDigest.isEqual(pin.authPublicKey,member.authPublicKey) ||
                !MessageDigest.isEqual(pin.identityDigest,member.signalIdentityDigest)) continue
            if(current(groupId,deviceId,pin.identityDigest)!=null) continue
            val recordBytes=records.read("group/state/v1/$groupId") ?: continue
            val record=runCatching {NetworkCodec.decode<GroupRecord>(recordBytes,
                GroupStatements.MAX_LEDGER_BYTES)}.getOrNull() ?: continue
            val admission=record.admission?.takeIf {proof ->
                proof.state.members.any {sameIdentity(it,member)}
            }
            val event=record.events.firstOrNull {it.change.action==GroupAction.ADD &&
                it.change.added?.let {added -> sameIdentity(added,member)}==true}
            val signed=when {
                admission!=null -> NetworkCodec.encode(admission)
                event!=null -> NetworkCodec.encode(event)
                else -> continue
            }
            anchor(state,member,DeviceAuth.digest(signed))
        }
    }
}
