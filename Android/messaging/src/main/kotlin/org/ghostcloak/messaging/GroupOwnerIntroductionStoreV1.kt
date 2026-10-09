package org.ghostcloak.messaging

import org.ghostcloak.crypto.EndpointRecords
import org.ghostcloak.protocol.NetworkCodec
import org.ghostcloak.identity.RandomIdentifiers
import kotlinx.serialization.Serializable
import java.security.MessageDigest

/** Durable admission-scoped handshake evidence; it never changes direct-contact state. */
internal class GroupOwnerIntroductionStoreV1(private val records:EndpointRecords) {
    @Serializable data class Queued(val targetDeviceId:String,val control:GroupControl)
    private fun suffix(groupId:String,inviteId:String):String {
        require(GroupIds.valid(groupId) && GroupIds.valid(inviteId))
        return "$groupId/$inviteId"
    }
    private fun setupKey(groupId:String,inviteId:String)=
        "app/group/owner-introduction/setup/${suffix(groupId,inviteId)}"
    private fun helloKey(groupId:String,inviteId:String)=
        "app/group/owner-introduction/hello/${suffix(groupId,inviteId)}"
    private fun ackKey(groupId:String,inviteId:String,memberId:String):String {
        require(GroupIds.valid(memberId))
        return "app/group/owner-introduction/ack/${suffix(groupId,inviteId)}/$memberId"
    }
    fun markMode(parent:GroupState,proof:GroupOwnerIntroductionV1)=records.transaction {
        require(GroupOwnerIntroductionRulesV1.verify(proof,parent))
        val key="app/group/owner-introduction/mode/${parent.groupId}"
        if(records.read(key)==null) records.write(key,GroupControlCodec.encode(GroupControl(
            version=2,kind=GroupControlKind.OWNER_INTRO_SETUP_V1,groupId=parent.groupId,
            inviteId=proof.inviteId,state=parent,ownerIntroductionV1=proof)))
    }
    fun mode(groupId:String):Boolean=records.transaction {
        require(GroupIds.valid(groupId))
        records.read("app/group/owner-introduction/mode/$groupId")?.let {encoded ->
            val control=runCatching {GroupControlCodec.decode(encoded)}.getOrNull()
            val parent=control?.state
            val proof=control?.ownerIntroductionV1
            parent!=null && proof!=null && GroupOwnerIntroductionRulesV1.verify(proof,parent)
        }==true
    }
    fun saveSetup(parent:GroupState,proof:GroupOwnerIntroductionV1) = records.transaction {
        require(GroupOwnerIntroductionRulesV1.verify(proof,parent))
        val key=setupKey(parent.groupId,proof.inviteId)
        val encoded=NetworkCodec.encode(GroupControl(version=2,
            kind=GroupControlKind.OWNER_INTRO_SETUP_V1,groupId=parent.groupId,
            inviteId=proof.inviteId,state=parent,ownerIntroductionV1=proof))
        val old=records.read(key)
        require(old==null || MessageDigest.isEqual(old,encoded))
        if(old==null) {
            require(records.keys("app/group/owner-introduction/setup/").size<128)
            records.write(key,encoded)
        }
    }
    fun setup(groupId:String,inviteId:String):Pair<GroupState,GroupOwnerIntroductionV1>? =
        records.transaction {
            val data=records.read(setupKey(groupId,inviteId)) ?: return@transaction null
            val control=GroupControlCodec.decode(data)
            val parent=control.state ?: return@transaction null
            val proof=control.ownerIntroductionV1 ?: return@transaction null
            if(!GroupOwnerIntroductionRulesV1.verify(proof,parent)) return@transaction null
            parent to proof
        }
    fun saveHello(parent:GroupState,proof:GroupOwnerIntroductionV1)=records.transaction {
        require(GroupOwnerIntroductionRulesV1.verify(proof,parent))
        val key=helloKey(parent.groupId,proof.inviteId)
        val encoded=GroupControlCodec.encode(GroupControl(version=2,
            kind=GroupControlKind.OWNER_INTRO_SETUP_V1,groupId=parent.groupId,
            inviteId=proof.inviteId,state=parent,ownerIntroductionV1=proof))
        val old=records.read(key)
        require(old==null || MessageDigest.isEqual(old,encoded))
        if(old==null) records.write(key,encoded)
    }
    fun hasHello(proof:GroupOwnerIntroductionV1):Boolean=records.transaction {
        records.read(helloKey(proof.groupId,proof.inviteId))?.let {encoded ->
            val saved=GroupControlCodec.decode(encoded).ownerIntroductionV1
            saved!=null && MessageDigest.isEqual(NetworkCodec.encode(saved),NetworkCodec.encode(proof))
        }==true
    }
    fun pendingSetupForGroup(groupId:String):Pair<GroupState,GroupOwnerIntroductionV1>?=records.transaction {
        require(GroupIds.valid(groupId))
        records.keys("app/group/owner-introduction/setup/$groupId/").singleOrNull()?.let {key ->
            val control=GroupControlCodec.decode(records.read(key)!!)
            control.state?.let {parent -> control.ownerIntroductionV1?.let {proof ->
                if(GroupOwnerIntroductionRulesV1.verify(proof,parent)) parent to proof else null
            }}
        }
    }
    fun pendingHelloForGroup(groupId:String):Pair<GroupState,GroupOwnerIntroductionV1>?=records.transaction {
        require(GroupIds.valid(groupId))
        records.keys("app/group/owner-introduction/hello/$groupId/").singleOrNull()?.let {key ->
            val control=GroupControlCodec.decode(records.read(key)!!)
            control.state?.let {parent -> control.ownerIntroductionV1?.let {proof ->
                if(GroupOwnerIntroductionRulesV1.verify(proof,parent)) parent to proof else null
            }}
        }
    }
    fun saveAck(proof:GroupOwnerIntroductionV1,memberId:String)=records.transaction {
        require(proof.routes.any {it.memberId==memberId})
        val key=ackKey(proof.groupId,proof.inviteId,memberId)
        val digest=MessageDigest.getInstance("SHA-256").digest(NetworkCodec.encode(proof))
        val old=records.read(key)
        require(old==null || MessageDigest.isEqual(old,digest))
        if(old==null) records.write(key,digest)
    }
    fun hasAck(proof:GroupOwnerIntroductionV1,memberId:String):Boolean=records.transaction {
        val expected=MessageDigest.getInstance("SHA-256").digest(NetworkCodec.encode(proof))
        records.read(ackKey(proof.groupId,proof.inviteId,memberId))?.let {
            MessageDigest.isEqual(it,expected)
        }==true
    }
    fun clear(groupId:String,inviteId:String)=records.transaction {
        records.remove(setupKey(groupId,inviteId))
        records.remove(helloKey(groupId,inviteId))
        records.keys("app/group/owner-introduction/ack/${suffix(groupId,inviteId)}/").forEach(records::remove)
    }
    fun markQueued(outboxId:String,targetDeviceId:String,control:GroupControl)=records.transaction {
        require(RandomIdentifiers.valid(outboxId) && RandomIdentifiers.valid(targetDeviceId) &&
            control.version==2 && control.kind in setOf(
                GroupControlKind.OWNER_INTRO_HELLO_V1,GroupControlKind.OWNER_INTRO_ACK_V1))
        val key="app/group/owner-introduction/outbox/$outboxId"
        require(records.keys("app/group/owner-introduction/outbox/").size<128 || records.read(key)!=null)
        records.write(key,NetworkCodec.encode(Queued(targetDeviceId,control)))
    }
    fun queued():List<Pair<String,Queued>> =records.transaction {
        records.keys("app/group/owner-introduction/outbox/").sorted().take(16).map {key ->
            key.substringAfterLast('/') to NetworkCodec.decode<Queued>(records.read(key)!!,14_000)
        }
    }
    fun finishQueued(outboxId:String)=records.transaction {
        require(RandomIdentifiers.valid(outboxId))
        records.remove("app/group/owner-introduction/outbox/$outboxId")
    }
}
