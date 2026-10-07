package org.ghostcloak.messaging

import kotlinx.serialization.Serializable
import org.ghostcloak.crypto.EndpointRecords
import org.ghostcloak.crypto.EndpointStorageFailure
import org.ghostcloak.identity.RandomIdentifiers
import org.ghostcloak.protocol.NetworkCodec

@Serializable internal data class PendingGroupMessageControlV1(
    val senderDeviceId:String,val control:GroupMessageControlV1,
) { override fun toString()="PendingGroupMessageControlV1(redacted)" }

@Serializable internal data class OutgoingGroupMessageControlV1(
    val control:GroupMessageControlV1,val recipients:List<GroupRecipient>,
) { override fun toString()="OutgoingGroupMessageControlV1(redacted)" }

/** Durable Signal-envelope intake, replay, and per-recipient fan-out for user controls. */
internal class GroupMessageControlStoreV1(private val records:EndpointRecords) {
    private val chats=GroupChatStore(records)
    private fun outgoingKey(groupId:String,id:String):String {
        require(GroupIds.valid(groupId) && GroupIds.valid(id))
        return "app/group-message-control-v1/outgoing/$groupId/$id"
    }
    private fun pendingKey(envelopeId:String):String {
        require(RandomIdentifiers.valid(envelopeId))
        return "app/group-message-control-v1/pending/$envelopeId"
    }
    private fun replayKey(groupId:String,id:String):String {
        require(GroupIds.valid(groupId) && GroupIds.valid(id))
        return "app/group-message-control-v1/replay/$groupId/$id"
    }
    private fun pendingTargetKey(groupId:String,targetId:String,controlId:String):String {
        require(listOf(groupId,targetId,controlId).all(GroupIds::valid))
        return "app/group-message-control-v1/pending-target/$groupId/$targetId/$controlId"
    }
    fun create(value:GroupMessageControlV1,recipients:List<GroupRecipient>)=records.transaction {
        GroupMessageControlCodecV1.validate(value)
        require(recipients.size in 1..4 && recipients.all {
            RandomIdentifiers.valid(it.deviceId) && it.state==GroupRecipientState.PENDING &&
                it.outboxId==null
        } && recipients.map {it.deviceId}.distinct().size==recipients.size)
        require(records.keys("app/group-message-control-v1/outgoing/").size<128)
        val key=outgoingKey(value.groupId,value.controlId)
        require(records.read(key)==null)
        records.write(key,NetworkCodec.encode(OutgoingGroupMessageControlV1(value,recipients)))
    }
    fun outgoing():List<OutgoingGroupMessageControlV1> = records.transaction {
        records.keys("app/group-message-control-v1/outgoing/").sorted().take(128).map {key ->
            NetworkCodec.decode<OutgoingGroupMessageControlV1>(
                records.read(key) ?: throw EndpointStorageFailure(),8192)
        }
    }
    fun markQueued(groupId:String,id:String,device:String,outboxId:String)=records.transaction {
        require(RandomIdentifiers.valid(outboxId))
        val key=outgoingKey(groupId,id)
        val old=NetworkCodec.decode<OutgoingGroupMessageControlV1>(
            records.read(key) ?: throw EndpointStorageFailure(),8192)
        require(old.recipients.single {it.deviceId==device}.state==GroupRecipientState.PENDING)
        records.write(key,NetworkCodec.encode(old.copy(recipients=old.recipients.map {
            if(it.deviceId==device) it.copy(state=GroupRecipientState.QUEUED,outboxId=outboxId)
            else it
        })))
        records.write("app/group-message-control-v1/outbox/$outboxId",key.encodeToByteArray())
    }
    fun markResult(outboxId:String,success:Boolean)=records.transaction {
        val link="app/group-message-control-v1/outbox/$outboxId"
        val key=records.read(link)?.decodeToString() ?: return@transaction
        val old=NetworkCodec.decode<OutgoingGroupMessageControlV1>(
            records.read(key) ?: throw EndpointStorageFailure(),8192)
        val next=old.copy(recipients=old.recipients.map {
            if(it.outboxId==outboxId) it.copy(state=if(success) GroupRecipientState.SENT
                else GroupRecipientState.UNAVAILABLE,outboxId=null) else it
        })
        records.remove(link)
        if(next.recipients.all {it.state in setOf(GroupRecipientState.SENT,
                GroupRecipientState.UNAVAILABLE)}) records.remove(key)
        else records.write(key,NetworkCodec.encode(next))
    }
    fun markUnavailable(groupId:String,id:String,device:String)=records.transaction {
        val key=outgoingKey(groupId,id)
        val old=NetworkCodec.decode<OutgoingGroupMessageControlV1>(
            records.read(key) ?: throw EndpointStorageFailure(),8192)
        val next=old.copy(recipients=old.recipients.map {
            if(it.deviceId==device && it.state==GroupRecipientState.PENDING)
                it.copy(state=GroupRecipientState.UNAVAILABLE) else it
        })
        if(next.recipients.all {it.state in setOf(GroupRecipientState.SENT,
                GroupRecipientState.UNAVAILABLE)}) records.remove(key)
        else records.write(key,NetworkCodec.encode(next))
    }
    fun cancelStale(groupId:String,sequence:Long,headDigest:ByteArray,
        activeDevices:Set<String>)=records.transaction {
        for(key in records.keys("app/group-message-control-v1/outgoing/$groupId/")) {
            val old=NetworkCodec.decode<OutgoingGroupMessageControlV1>(
                records.read(key) ?: throw EndpointStorageFailure(),8192)
            val stale=old.control.governanceSequence!=sequence ||
                !old.control.governanceHeadDigest.contentEquals(headDigest)
            val next=old.copy(recipients=old.recipients.map {recipient ->
                if(recipient.state !in setOf(GroupRecipientState.PENDING,GroupRecipientState.QUEUED) ||
                    (!stale && recipient.deviceId in activeDevices)) recipient else {
                    val accepted=recipient.outboxId?.let {outboxId ->
                        val outboxKey="outbox/$outboxId"
                        val entry=records.read(outboxKey)?.let {NetworkCodec.decode<OutboxEntry>(it)}
                        if(entry?.state!=OutboxState.SERVER_ACCEPTED) records.remove(outboxKey)
                        records.remove("app/group-message-control-v1/outbox/$outboxId")
                        entry?.state==OutboxState.SERVER_ACCEPTED
                    }==true
                    recipient.copy(state=if(accepted) GroupRecipientState.SENT else
                        GroupRecipientState.UNAVAILABLE,outboxId=null)
                }
            })
            if(next.recipients.all {it.state in setOf(GroupRecipientState.SENT,
                    GroupRecipientState.UNAVAILABLE)}) records.remove(key)
            else records.write(key,NetworkCodec.encode(next))
        }
    }
    fun outboxIds():Set<String> = records.transaction {
        records.keys("app/group-message-control-v1/outbox/").map {it.substringAfterLast('/')}.toSet()
    }
    fun queue(sender:String,envelopeId:String,value:GroupMessageControlV1)=records.transaction {
        require(RandomIdentifiers.valid(sender))
        GroupMessageControlCodecV1.validate(value)
        if(records.keys("app/group-message-control-v1/pending/").size>=128) return@transaction
        records.write(pendingKey(envelopeId),NetworkCodec.encode(PendingGroupMessageControlV1(sender,value)))
    }
    fun pending():List<Pair<String,PendingGroupMessageControlV1>> = records.transaction {
        records.keys("app/group-message-control-v1/pending/").sorted().take(16).map {key ->
            key.substringAfterLast('/') to NetworkCodec.decode<PendingGroupMessageControlV1>(
                records.read(key) ?: throw EndpointStorageFailure(),4096)
        }
    }
    fun discard(envelopeId:String)=records.transaction {records.remove(pendingKey(envelopeId))}
    fun apply(envelopeId:String,value:GroupMessageControlV1)=records.transaction {
        val replay=replayKey(value.groupId,value.controlId)
        if(records.read(replay)!=null) {discard(envelopeId);return@transaction false}
        if(records.keys("app/group-message-control-v1/replay/").size>=8192) {
            discard(envelopeId);return@transaction false
        }
        val target=chats.message(value.groupId,value.targetLogicalId)
        val applied=if(target==null && value.kind!=GroupMessageControlKindV1.DELETE_BY_SENDER) {
            if(chats.isModerated(value.groupId,value.targetLogicalId) ||
                chats.isSenderDeleted(value.groupId,value.targetLogicalId)) {
                records.write(replay,byteArrayOf(1));discard(envelopeId);return@transaction false
            }
            if(records.keys("app/group-message-control-v1/pending-target/").size>=128) false
            else {
                records.write(pendingTargetKey(value.groupId,value.targetLogicalId,value.controlId),
                    GroupMessageControlCodecV1.encode(value))
                true
            }
        } else when(value.kind) {
            GroupMessageControlKindV1.REACTION -> chats.react(value.groupId,
                value.targetLogicalId,value.actorMemberId,value.revision,value.emoji)
            GroupMessageControlKindV1.EDIT -> chats.edit(value.groupId,value.targetLogicalId,
                value.actorMemberId,value.revision,value.text!!)
            GroupMessageControlKindV1.DELETE_BY_SENDER -> chats.deleteBySender(value.groupId,
                value.targetLogicalId,value.actorMemberId)
        }
        records.write(replay,byteArrayOf(1))
        discard(envelopeId)
        applied
    }
    /** Called in the same transaction that first exposes a validated target message. */
    fun applyPendingTarget(groupId:String,targetId:String)=records.transaction {
        val prefix="app/group-message-control-v1/pending-target/$groupId/$targetId/"
        val pending=records.keys(prefix).map {key ->
            key to GroupMessageControlCodecV1.decode(
                records.read(key) ?: throw EndpointStorageFailure())
        }
        for((key,value) in pending) {
            when(value.kind) {
                GroupMessageControlKindV1.REACTION -> chats.react(groupId,targetId,
                    value.actorMemberId,value.revision,value.emoji)
                GroupMessageControlKindV1.EDIT -> chats.edit(groupId,targetId,
                    value.actorMemberId,value.revision,value.text!!)
                GroupMessageControlKindV1.DELETE_BY_SENDER -> Unit
            }
            records.remove(key)
        }
    }
    fun discardStalePendingTargets(groupId:String,sequence:Long,headDigest:ByteArray)=records.transaction {
        require(GroupIds.valid(groupId) && headDigest.size==32)
        for(key in records.keys("app/group-message-control-v1/pending-target/$groupId/")) {
            val value=GroupMessageControlCodecV1.decode(
                records.read(key) ?: throw EndpointStorageFailure())
            if(value.governanceSequence!=sequence ||
                !value.governanceHeadDigest.contentEquals(headDigest)) records.remove(key)
        }
    }
}
