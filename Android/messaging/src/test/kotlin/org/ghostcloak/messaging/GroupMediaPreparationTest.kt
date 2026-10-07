package org.ghostcloak.messaging

import org.ghostcloak.attachments.AttachmentKind
import org.ghostcloak.crypto.EndpointRecords
import org.ghostcloak.identity.RandomIdentifiers
import org.ghostcloak.protocol.NetworkCodec
import org.ghostcloak.protocol.EncryptedEnvelope
import org.ghostcloak.protocol.EnvelopeCodec
import org.junit.Assert.*
import org.junit.Test

class GroupMediaPreparationTest {
    private class Records:EndpointRecords {
        private val values=mutableMapOf<String,ByteArray>()
        override fun <T> transaction(block:()->T):T {
            val prior=values.mapValues {it.value.copyOf()}
            return try {block()} catch(t:Throwable) {
                values.clear();values.putAll(prior);throw t
            }
        }
        override fun read(key:String)=values[key]?.copyOf()
        override fun write(key:String,value:ByteArray) {values[key]=value.copyOf()}
        override fun remove(key:String) {values.remove(key)}
        override fun keys(prefix:String)=values.keys.filter {it.startsWith(prefix)}
    }

    @Test fun fourRecipientPartialPreparationSurvivesRestartAndRejectsWrongHead() {
        val records=Records()
        val groupId=GroupIds.create();val id=GroupIds.create();val sender=GroupIds.create()
        val senderDevice=RandomIdentifiers.create()
        val peers=(1..4).map {RandomIdentifiers.create()}
        val head=ByteArray(32) {7};val digest=ByteArray(32) {9}
        val store=GroupChatStore(records)
        records.transaction {
            records.write("app/group-text/message/$groupId/$id",NetworkCodec.encode(
                GroupChatMessage(groupId,id,2,sender,true,"",store.nextOrder(),
                    peers.map(::GroupRecipient),mediaKind=AttachmentKind.IMAGE,
                    mediaBodyDigest=digest)))
            records.write("app/group-text/v2-binding/$groupId/$id",NetworkCodec.encode(
                GroupTextV2Binding(ByteArray(32) {1},3,head,ByteArray(32) {2})))
        }
        fun preparedSlot(index:Int,proofHead:ByteArray=head) {
            val outboxId=RandomIdentifiers.create()
            val envelopeId=RandomIdentifiers.create()
            store.markQueued(groupId,id,peers[index],outboxId)
            records.transaction {records.write("outbox/$outboxId",NetworkCodec.encode(
                OutboxEntry(outboxId,peers[index],OutboxState.CIPHERTEXT_READY,
                    ciphertext=EnvelopeCodec.encode(EncryptedEnvelope(1,envelopeId,
                        senderDevice,peers[index],2,byteArrayOf(1,2,3))),createdAt=1,
                    envelopeId=envelopeId,
                    groupMediaBinding=GroupMediaOutboxBinding(groupId,id,peers[index],
                        proofHead,ByteArray(32) {4},digest))))}
        }
        preparedSlot(0)
        assertFalse(GroupChatStore(records).markMediaDeliveryPreparedIfReady(groupId,id))
        assertEquals(1,GroupChatStore(records).message(groupId,id)!!.recipients.count {
            it.state==GroupRecipientState.QUEUED})
        preparedSlot(1)
        assertFalse(store.markMediaDeliveryPreparedIfReady(groupId,id))
        assertFalse(GroupChatStore(records).message(groupId,id)!!.mediaDeliveryPrepared)
        preparedSlot(2)
        preparedSlot(3,ByteArray(32) {8})
        assertFalse(GroupChatStore(records).markMediaDeliveryPreparedIfReady(groupId,id))
        val fourth=store.message(groupId,id)!!.recipients[3].outboxId!!
        val prior=NetworkCodec.decode<OutboxEntry>(records.read("outbox/$fourth")!!)
        records.transaction {records.write("outbox/$fourth",NetworkCodec.encode(
            OutboxEntry(fourth,peers[3],OutboxState.CIPHERTEXT_READY,
                ciphertext=byteArrayOf(1,2,3),createdAt=1,envelopeId=prior.envelopeId,
                groupMediaBinding=GroupMediaOutboxBinding(groupId,id,peers[3],head,
                    ByteArray(32) {4},digest))))}
        assertFalse(GroupChatStore(records).markMediaDeliveryPreparedIfReady(groupId,id))
        records.transaction {records.write("outbox/$fourth",NetworkCodec.encode(
            OutboxEntry(fourth,peers[3],OutboxState.CIPHERTEXT_READY,
                ciphertext=prior.ciphertext,createdAt=1,envelopeId=prior.envelopeId,
                groupMediaBinding=GroupMediaOutboxBinding(groupId,id,peers[3],head,
                    ByteArray(32) {4},digest))))}
        val restarted=GroupChatStore(records)
        assertTrue(restarted.markMediaDeliveryPreparedIfReady(groupId,id))
        assertTrue(GroupChatStore(records).message(groupId,id)!!.mediaDeliveryPrepared)
        assertTrue(restarted.markMediaDeliveryPreparedIfReady(groupId,id))
        assertEquals(4,restarted.message(groupId,id)!!.recipients.size)
        restarted.markRemoved(groupId,peers[3])
        assertNull(records.read("outbox/$fourth"))
        assertFalse(restarted.message(groupId,id)!!.mediaDeliveryPrepared)
        assertEquals(GroupRecipientState.UNAVAILABLE,restarted.message(groupId,id)!!.recipients[3].state)
        restarted.cancelStaleHead(groupId,null,null)
        assertFalse(restarted.message(groupId,id)!!.mediaDeliveryPrepared)
        assertTrue(restarted.message(groupId,id)!!.recipients.all {
            it.state==GroupRecipientState.UNAVAILABLE})
    }
}
