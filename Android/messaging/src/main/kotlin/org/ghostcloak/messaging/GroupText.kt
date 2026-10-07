package org.ghostcloak.messaging

import kotlinx.serialization.Serializable
import org.ghostcloak.crypto.EndpointRecords
import org.ghostcloak.crypto.EndpointStorageFailure
import org.ghostcloak.identity.RandomIdentifiers
import org.ghostcloak.protocol.NetworkCodec

/** This content is framed inside a pairwise authenticated Signal message, never sent as routing metadata. */
@Serializable data class GroupText(val version:Int=1,val groupId:String,val epoch:Long,
    val senderMemberId:String,val logicalId:String,val text:String) {
    override fun toString()="GroupText(redacted)"
}

object GroupTextCodec {
    const val MAX_TEXT_BYTES=2048
    const val MAX_BYTES=3072
    fun validate(value:GroupText) {
        require(value.version==1 && GroupIds.valid(value.groupId) && value.epoch>0 &&
            GroupIds.valid(value.senderMemberId) && GroupIds.valid(value.logicalId))
        val bytes=TextRules.encode(value.text)
        try {require(bytes.size in 1..MAX_TEXT_BYTES)} finally {bytes.fill(0)}
    }
    fun encode(value:GroupText):ByteArray {
        validate(value)
        return NetworkCodec.encode(value).also {require(it.size in 1..MAX_BYTES)}
    }
    fun decode(bytes:ByteArray):GroupText {
        require(bytes.size in 1..MAX_BYTES)
        return NetworkCodec.decode<GroupText>(bytes,MAX_BYTES).also(::validate)
    }
}

enum class GroupRecipientState { PENDING, QUEUED, SENT, UNAVAILABLE }
@Serializable data class GroupRecipient(val deviceId:String,val state:GroupRecipientState=GroupRecipientState.PENDING,
    val outboxId:String?=null)
@Serializable data class GroupChatMessage(val groupId:String,val logicalId:String,val epoch:Long,
    val senderMemberId:String,val outgoing:Boolean,val text:String,val localOrder:Long,
    val recipients:List<GroupRecipient> = emptyList()) {
    override fun toString()="GroupChatMessage(redacted)"
}
@Serializable data class PendingGroupText(val senderDeviceId:String,val text:GroupText) {
    override fun toString()="PendingGroupText(redacted)"
}

/** All rows live in the existing SQLCipher endpoint store and are destroyed with it. */
class GroupChatStore(private val records:EndpointRecords) {
    private fun messageKey(groupId:String,id:String):String {
        require(GroupIds.valid(groupId) && GroupIds.valid(id));return "app/group-text/message/$groupId/$id"
    }
    private fun replayKey(groupId:String,sender:String,id:String):String {
        require(listOf(groupId,sender,id).all(GroupIds::valid))
        return "app/group-text/replay/$groupId/$sender/$id"
    }
    private fun pendingKey(envelopeId:String):String {
        require(RandomIdentifiers.valid(envelopeId));return "app/group-text/pending/$envelopeId"
    }
    private fun pendingV2Key(envelopeId:String):String {
        require(RandomIdentifiers.valid(envelopeId));return "app/group-text/pending-v2/$envelopeId"
    }
    private fun bindingKey(groupId:String,id:String):String {
        require(GroupIds.valid(groupId) && GroupIds.valid(id))
        return "app/group-text/v2-binding/$groupId/$id"
    }
    private fun decodeMessage(key:String)=NetworkCodec.decode<GroupChatMessage>(
        records.read(key) ?: throw EndpointStorageFailure(),8192)
    fun groups():List<String> = records.transaction {
        records.keys("app/group/member/").map {it.removePrefix("app/group/member/")}.filter(GroupIds::valid).sorted()
    }
    fun messages(groupId:String):List<GroupChatMessage> = records.transaction {
        require(GroupIds.valid(groupId))
        records.keys("app/group-text/message/$groupId/").map(::decodeMessage)
            .sortedWith(compareBy<GroupChatMessage> {it.localOrder}.thenBy {it.logicalId})
    }
    fun message(groupId:String,id:String):GroupChatMessage?=records.transaction {
        records.read(messageKey(groupId,id))?.let {NetworkCodec.decode<GroupChatMessage>(it,8192)}
    }
    fun create(value:GroupChatMessage)=records.transaction {
        require(value.outgoing && value.recipients.size in 1..4 && value.recipients.map {it.deviceId}.distinct().size==value.recipients.size)
        require(value.recipients.all {RandomIdentifiers.valid(it.deviceId) && it.state==GroupRecipientState.PENDING && it.outboxId==null})
        GroupTextCodec.validate(GroupText(groupId=value.groupId,epoch=value.epoch,
            senderMemberId=value.senderMemberId,logicalId=value.logicalId,text=value.text))
        require(records.keys("app/group-text/message/").size<4096 && records.read(messageKey(value.groupId,value.logicalId))==null)
        records.write(messageKey(value.groupId,value.logicalId),NetworkCodec.encode(value))
    }
    internal fun createV2(value:GroupChatMessage,binding:GroupTextV2Binding)=records.transaction {
        require(binding.activationDigest.size==32 && binding.headDigest.size==32 &&
            binding.policyDigest.size==32 &&
            binding.sequence in 0..GroupGovernanceJournalV1.MAX_ENTRIES.toLong())
        create(value)
        records.write(bindingKey(value.groupId,value.logicalId),NetworkCodec.encode(binding))
    }
    internal fun binding(groupId:String,id:String):GroupTextV2Binding?=records.transaction {
        records.read(bindingKey(groupId,id))?.let {NetworkCodec.decode<GroupTextV2Binding>(it,256)}
    }
    fun queue(sender:String,envelopeId:String,value:GroupText)=records.transaction {
        require(RandomIdentifiers.valid(sender));GroupTextCodec.validate(value)
        require(records.keys("app/group-text/pending/").size+
            records.keys("app/group-text/pending-v2/").size<128)
        records.write(pendingKey(envelopeId),NetworkCodec.encode(PendingGroupText(sender,value)))
    }
    fun queueV2(sender:String,envelopeId:String,value:GroupTextV2)=records.transaction {
        require(RandomIdentifiers.valid(sender));GroupTextV2Codec.validate(value)
        require(records.keys("app/group-text/pending/").size+
            records.keys("app/group-text/pending-v2/").size<128)
        val existing=records.keys("app/group-text/pending-v2/").count {key ->
            records.read(key)?.let {NetworkCodec.decode<PendingGroupTextV2>(it,4096)}
                ?.text?.groupId==value.groupId
        }
        require(existing<32)
        records.write(pendingV2Key(envelopeId),NetworkCodec.encode(PendingGroupTextV2(sender,value)))
    }
    internal fun pendingV2():List<Pair<String,PendingGroupTextV2>> = records.transaction {
        records.keys("app/group-text/pending-v2/").sorted().take(16).map {key ->
            key.removePrefix("app/group-text/pending-v2/") to NetworkCodec.decode<PendingGroupTextV2>(
                records.read(key) ?: throw EndpointStorageFailure(),4096)
        }
    }
    fun discardPendingV2(envelopeId:String)=records.transaction {records.remove(pendingV2Key(envelopeId))}
    internal fun acceptV2(envelopeId:String,pending:PendingGroupTextV2):Boolean=records.transaction {
        val value=pending.text
        val replay=replayKey(value.groupId,value.senderMemberId,value.logicalId)
        if(records.read(replay)!=null) {records.remove(pendingV2Key(envelopeId));return@transaction false}
        if(records.read(messageKey(value.groupId,value.logicalId))!=null) {
            require(records.keys("app/group-text/replay/").size<8192)
            records.write(replay,byteArrayOf(1));records.remove(pendingV2Key(envelopeId));return@transaction false
        }
        require(records.keys("app/group-text/replay/").size<8192 &&
            records.keys("app/group-text/message/").size<4096)
        val order=(records.read("app/group-text/order")?.decodeToString()?.toLongOrNull() ?: 0L)+1
        records.write("app/group-text/order",order.toString().encodeToByteArray())
        records.write(messageKey(value.groupId,value.logicalId),NetworkCodec.encode(GroupChatMessage(
            value.groupId,value.logicalId,value.epoch,value.senderMemberId,false,value.text,order)))
        records.write(replay,byteArrayOf(1))
        NotificationLedger.acceptedGroup(records,value.groupId,value.logicalId)
        records.remove(pendingV2Key(envelopeId))
        true
    }
    fun pending():List<Pair<String,PendingGroupText>> = records.transaction {
        records.keys("app/group-text/pending/").sorted().take(16).map {key ->
            key.removePrefix("app/group-text/pending/") to NetworkCodec.decode<PendingGroupText>(
                records.read(key) ?: throw EndpointStorageFailure(),4096)
        }
    }
    fun discardPending(envelopeId:String)=records.transaction {records.remove(pendingKey(envelopeId))}
    /** Replay marker and visible message are committed in the same endpoint transaction. */
    fun accept(envelopeId:String,pending:PendingGroupText):Boolean=records.transaction {
        val value=pending.text
        val replay=replayKey(value.groupId,value.senderMemberId,value.logicalId)
        if(records.read(replay)!=null) {records.remove(pendingKey(envelopeId));return@transaction false}
        if(records.read(messageKey(value.groupId,value.logicalId))!=null) {
            require(records.keys("app/group-text/replay/").size<8192)
            records.write(replay,byteArrayOf(1));records.remove(pendingKey(envelopeId));return@transaction false
        }
        // Never evict a replay marker while retained history might still display the message.
        require(records.keys("app/group-text/replay/").size<8192 &&
            records.keys("app/group-text/message/").size<4096)
        val order=(records.read("app/group-text/order")?.decodeToString()?.toLongOrNull() ?: 0L)+1
        records.write("app/group-text/order",order.toString().encodeToByteArray())
        records.write(messageKey(value.groupId,value.logicalId),NetworkCodec.encode(GroupChatMessage(
            value.groupId,value.logicalId,value.epoch,value.senderMemberId,false,value.text,order)))
        records.write(replay,byteArrayOf(1))
        NotificationLedger.acceptedGroup(records,value.groupId,value.logicalId)
        records.remove(pendingKey(envelopeId))
        true
    }
    fun nextOrder():Long=records.transaction {
        val order=(records.read("app/group-text/order")?.decodeToString()?.toLongOrNull() ?: 0L)+1
        records.write("app/group-text/order",order.toString().encodeToByteArray());order
    }
    fun markQueued(groupId:String,id:String,device:String,outboxId:String)=records.transaction {
        require(RandomIdentifiers.valid(outboxId))
        val key=messageKey(groupId,id);val old=decodeMessage(key)
        require(old.outgoing)
        val recipient=old.recipients.single {it.deviceId==device}
        require(recipient.state==GroupRecipientState.PENDING && recipient.outboxId==null)
        records.write(key,NetworkCodec.encode(old.copy(recipients=old.recipients.map {
            if(it.deviceId==device) it.copy(state=GroupRecipientState.QUEUED,outboxId=outboxId) else it
        })))
        records.write("app/group-text/outbox/$outboxId",key.encodeToByteArray())
    }
    fun markResult(outboxId:String,success:Boolean) = records.transaction {
        require(RandomIdentifiers.valid(outboxId))
        val link="app/group-text/outbox/$outboxId"
        val key=records.read(link)?.decodeToString() ?: return@transaction
        val old=decodeMessage(key)
        records.write(key,NetworkCodec.encode(old.copy(recipients=old.recipients.map {
            if(it.outboxId==outboxId) it.copy(state=if(success) GroupRecipientState.SENT else GroupRecipientState.UNAVAILABLE) else it
        })))
        records.remove(link)
    }
    fun outboxIds():Set<String> = records.transaction {
        records.keys("app/group-text/outbox/").map {it.removePrefix("app/group-text/outbox/")}.toSet()
    }
    fun markUnavailable(groupId:String,id:String,device:String)=records.transaction {
        val key=messageKey(groupId,id);val old=decodeMessage(key)
        require(old.outgoing)
        records.write(key,NetworkCodec.encode(old.copy(recipients=old.recipients.map {
            if(it.deviceId==device && it.state==GroupRecipientState.PENDING)
                it.copy(state=GroupRecipientState.UNAVAILABLE) else it
        })))
    }
    fun markRemoved(groupId:String,device:String) = records.transaction {
        require(GroupIds.valid(groupId) && RandomIdentifiers.valid(device))
        for(key in records.keys("app/group-text/message/$groupId/")) {
            val old=decodeMessage(key)
            if(!old.outgoing || old.recipients.none {it.deviceId==device && it.state in setOf(GroupRecipientState.PENDING,GroupRecipientState.QUEUED)}) continue
            val next=old.recipients.map {recipient ->
                if(recipient.deviceId==device && recipient.state in setOf(GroupRecipientState.PENDING,GroupRecipientState.QUEUED)) {
                    val accepted=recipient.outboxId?.let {id ->
                        val outboxKey="outbox/$id"
                        val entry=records.read(outboxKey)?.let {NetworkCodec.decode<OutboxEntry>(it)}
                        if(entry?.state!=OutboxState.SERVER_ACCEPTED) records.remove(outboxKey)
                        records.remove("app/group-text/outbox/$id")
                        entry?.state==OutboxState.SERVER_ACCEPTED
                    }==true
                    recipient.copy(state=if(accepted) GroupRecipientState.SENT else GroupRecipientState.UNAVAILABLE,outboxId=null)
                } else recipient
            }
            records.write(key,NetworkCodec.encode(old.copy(recipients=next)))
        }
    }
    fun cancelStale(groupId:String,currentEpoch:Long)=records.transaction {
        require(GroupIds.valid(groupId))
        for(key in records.keys("app/group-text/message/$groupId/")) {
            val old=decodeMessage(key)
            if(!old.outgoing || old.epoch==currentEpoch) continue
            val next=old.recipients.map {recipient ->
                if(recipient.state in setOf(GroupRecipientState.PENDING,GroupRecipientState.QUEUED)) {
                    val accepted=recipient.outboxId?.let {id ->
                        val outboxKey="outbox/$id"
                        val entry=records.read(outboxKey)?.let {NetworkCodec.decode<OutboxEntry>(it)}
                        if(entry?.state!=OutboxState.SERVER_ACCEPTED) records.remove(outboxKey)
                        records.remove("app/group-text/outbox/$id")
                        entry?.state==OutboxState.SERVER_ACCEPTED
                    }==true
                    recipient.copy(state=if(accepted) GroupRecipientState.SENT else GroupRecipientState.UNAVAILABLE,outboxId=null)
                } else recipient
            }
            records.write(key,NetworkCodec.encode(old.copy(recipients=next)))
        }
    }
    /** Submitted ciphertext is recorded as sent; every unsent old-head slot is retired. */
    internal fun cancelStaleHead(groupId:String,head:GovernanceHeadFoundationV1?,
        policy:GroupGovernancePolicyV1?)=records.transaction {
        require(GroupIds.valid(groupId))
        for(key in records.keys("app/group-text/message/$groupId/")) {
            val old=decodeMessage(key)
            if(!old.outgoing) continue
            val binding=binding(groupId,old.logicalId)
            if(head==null && binding==null || head!=null && policy!=null &&
                binding?.matches(head,policy)==true) continue
            val next=old.recipients.map {recipient ->
                if(recipient.state !in setOf(GroupRecipientState.PENDING,GroupRecipientState.QUEUED)) recipient
                else {
                    val accepted=recipient.outboxId?.let {id ->
                        val outboxKey="outbox/$id"
                        val entry=records.read(outboxKey)?.let {NetworkCodec.decode<OutboxEntry>(it)}
                        if(entry?.state!=OutboxState.SERVER_ACCEPTED) records.remove(outboxKey)
                        records.remove("app/group-text/outbox/$id")
                        entry?.state==OutboxState.SERVER_ACCEPTED
                    }==true
                    recipient.copy(state=if(accepted) GroupRecipientState.SENT else
                        GroupRecipientState.UNAVAILABLE,outboxId=null)
                }
            }
            records.write(key,NetworkCodec.encode(old.copy(recipients=next)))
        }
    }
}
