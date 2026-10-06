package org.ghostcloak.testing

import org.ghostcloak.messaging.*
import org.ghostcloak.identity.RandomIdentifiers
import org.junit.Assert.*
import org.junit.Test

class GroupTextTest {
    private fun sample(text:String="hello")=GroupText(groupId=GroupIds.create(),epoch=2,
        senderMemberId=GroupIds.create(),logicalId=GroupIds.create(),text=text)

    @Test fun payloadRoundTripAndHeadroom() {
        val value=sample("a".repeat(GroupTextCodec.MAX_TEXT_BYTES))
        val frame=ConversationPayload.encodeGroupText(value)
        assertTrue(frame.size<=4096)
        assertTrue(frame.size<16384/2)
        assertEquals(0,frame.size%256)
        assertEquals(value,ConversationPayload.decode(frame).groupText)
        assertThrows(IllegalArgumentException::class.java) {
            ConversationPayload.encodeGroupText(sample("a".repeat(GroupTextCodec.MAX_TEXT_BYTES+1)))
        }
        val wrong=frame.clone().also {it[5]=99}
        assertThrows(Exception::class.java) {ConversationPayload.decode(wrong)}
        val malformed=frame.clone().also {it[16]=0}
        assertThrows(Exception::class.java) {ConversationPayload.decode(malformed)}
    }

    @Test fun replaySurvivesStoreRecreationAndOwnRecipientPlanStaysFixed() {
        val records=MemoryRecords()
        val a=GroupChatStore(records)
        val value=sample()
        val first=RandomIdentifiers.create()
        a.queue(RandomIdentifiers.create(),first,value)
        assertTrue(a.accept(first,a.pending().single().second))
        assertEquals(1,a.messages(value.groupId).size)
        val restarted=GroupChatStore(records)
        val duplicate=RandomIdentifiers.create()
        restarted.queue(RandomIdentifiers.create(),duplicate,value)
        assertFalse(restarted.accept(duplicate,restarted.pending().single().second))
        assertEquals(1,restarted.messages(value.groupId).size)
        val own=sample("outgoing")
        val peer1=RandomIdentifiers.create();val peer2=RandomIdentifiers.create()
        restarted.create(GroupChatMessage(own.groupId,own.logicalId,own.epoch,own.senderMemberId,true,
            own.text,restarted.nextOrder(),listOf(GroupRecipient(peer1),GroupRecipient(peer2))))
        val outboxId=RandomIdentifiers.create()
        restarted.markQueued(own.groupId,own.logicalId,peer1,outboxId)
        assertEquals(setOf(outboxId),GroupChatStore(records).outboxIds())
        restarted.markResult(outboxId,true)
        assertTrue(restarted.outboxIds().isEmpty())
        assertEquals(GroupRecipientState.SENT,restarted.message(own.groupId,own.logicalId)!!.recipients[0].state)
        assertEquals(GroupRecipientState.PENDING,restarted.message(own.groupId,own.logicalId)!!.recipients[1].state)
        restarted.markUnavailable(own.groupId,own.logicalId,peer2)
        assertEquals(GroupRecipientState.UNAVAILABLE,restarted.message(own.groupId,own.logicalId)!!.recipients[1].state)
    }

    @Test fun canonicalContextRejectsNonmemberWrongGroupAndOldOrFutureEpoch() {
        val group=GroupIds.create();val member=GroupIds.create();val other=GroupIds.create()
        val state=GroupState(group,3,2,member,member,listOf(GroupMember(member,
            RandomIdentifiers.create(),RandomIdentifiers.create(),byteArrayOf(1),byteArrayOf(2),
            GroupRole.OWNER,1)),ByteArray(32))
        val id=GroupIds.create()
        assertTrue(GroupMessageContext(group,2,member,id).allowedBy(state))
        assertFalse(GroupMessageContext(other,2,member,id).allowedBy(state))
        assertFalse(GroupMessageContext(group,1,member,id).allowedBy(state))
        assertFalse(GroupMessageContext(group,3,member,id).allowedBy(state))
        assertFalse(GroupMessageContext(group,2,other,id).allowedBy(state))
        assertFalse(GroupMessageContext(group,2,member,id).allowedBy(state.copy(lifecycle=GroupLifecycle.DISSOLVED)))
    }

    @Test fun restartKeepsRecipientSetAndEpochBarrierCancelsOnlyUnsentFanout() {
        val records=MemoryRecords();val store=GroupChatStore(records);val value=sample("before change")
        val b=RandomIdentifiers.create();val c=RandomIdentifiers.create()
        store.create(GroupChatMessage(value.groupId,value.logicalId,value.epoch,value.senderMemberId,
            true,value.text,store.nextOrder(),listOf(GroupRecipient(b),GroupRecipient(c))))
        val bOutbox=RandomIdentifiers.create()
        store.markQueued(value.groupId,value.logicalId,b,bOutbox)
        store.markResult(bOutbox,true)
        // The process disappears after one recipient is accepted and before the other is queued.
        val restored=GroupChatStore(records)
        assertEquals(listOf(b,c),restored.message(value.groupId,value.logicalId)!!.recipients.map {it.deviceId})
        restored.cancelStale(value.groupId,value.epoch+1)
        assertEquals(listOf(GroupRecipientState.SENT,GroupRecipientState.UNAVAILABLE),
            restored.message(value.groupId,value.logicalId)!!.recipients.map {it.state})
    }
}
