package org.ghostcloak.messaging

import org.ghostcloak.crypto.EndpointRecords
import org.ghostcloak.identity.RandomIdentifiers
import org.junit.Assert.*
import org.junit.Test

class GroupMessageControlsV1Test {
    private class MemoryRecords:EndpointRecords {
        private val entries=mutableMapOf<String,ByteArray>()
        override fun <T> transaction(block:()->T):T=block()
        override fun read(key:String)=entries[key]?.copyOf()
        override fun write(key:String,value:ByteArray) {entries.put(key,value.copyOf())?.fill(0)}
        override fun remove(key:String) {entries.remove(key)?.fill(0)}
        override fun keys(prefix:String)=entries.keys.filter {it.startsWith(prefix)}
    }
    private val group=GroupIds.create()
    private val alice=GroupIds.create()
    private val bob=GroupIds.create()
    private val target=GroupIds.create()
    private val activation=ByteArray(32) {1}
    private val head=ByteArray(32) {2}
    private val policy=ByteArray(32) {3}
    private fun text(id:String=target,sender:String=alice,body:String="original")=GroupTextV2(
        groupId=group,epoch=1,senderMemberId=sender,logicalId=id,
        governanceActivationDigest=activation,governanceSequence=1,
        governanceHeadDigest=head,policyDigest=policy,text=body)
    private fun control(kind:GroupMessageControlKindV1,actor:String=alice,
        revision:Long=0,emoji:String?=null,replacement:String?=null)=GroupMessageControlV1(
        groupId=group,activationDigest=activation,governanceSequence=1,
        governanceHeadDigest=head,actorMemberId=actor,targetLogicalId=target,
        controlId=GroupIds.create(),kind=kind,revision=revision,emoji=emoji,text=replacement)
    private fun accept(chats:GroupChatStore,value:GroupTextV2=text()) {
        val envelope=RandomIdentifiers.create()
        val sender=RandomIdentifiers.create()
        chats.queueV2(sender,envelope,value)
        assertTrue(chats.acceptV2(envelope,PendingGroupTextV2(sender,value)))
    }
    private fun apply(store:GroupMessageControlStoreV1,value:GroupMessageControlV1):Boolean {
        val envelope=RandomIdentifiers.create()
        store.queue(RandomIdentifiers.create(),envelope,value)
        return store.apply(envelope,value)
    }

    @Test fun replyAndControlFramesUseNewTypesWithoutChangingV2() {
        val original=text(body="a".repeat(GroupTextCodec.MAX_TEXT_BYTES))
        val reply=GroupTextV3(groupId=group,epoch=1,senderMemberId=bob,
            logicalId=GroupIds.create(),governanceActivationDigest=activation,
            governanceSequence=1,governanceHeadDigest=head,policyDigest=policy,
            text=original.text,replyToLogicalId=target)
        val v2=ConversationPayload.encodeGroupTextV2(original)
        val v3=ConversationPayload.encodeGroupTextV3(reply)
        assertEquals(15,v2[5].toInt())
        assertEquals(16,v3[5].toInt())
        assertEquals(target,ConversationPayload.decode(v3).groupTextV3!!.replyToLogicalId)
        assertEquals(original.text,ConversationPayload.decode(v2).groupTextV2!!.text)
        assertTrue(v3.size<=4096)
        val edit=control(GroupMessageControlKindV1.EDIT,revision=1,replacement=original.text)
        val frame=ConversationPayload.encodeGroupMessageControl(edit)
        assertEquals(17,frame[5].toInt())
        assertEquals(edit.text,ConversationPayload.decode(frame).groupMessageControl!!.text)
        assertTrue(frame.size<=4096)
        assertThrows(IllegalArgumentException::class.java) {
            ConversationPayload.encodeGroupMessageControl(edit.copy(text="b".repeat(2049)))
        }
    }

    @Test fun reactionOrderingRemovalAndTerminalClearSurviveRestart() {
        val records=MemoryRecords();val chats=GroupChatStore(records)
        accept(chats)
        val controls=GroupMessageControlStoreV1(records)
        assertTrue(apply(controls,control(GroupMessageControlKindV1.REACTION,bob,1,"👍")))
        assertTrue(apply(controls,control(GroupMessageControlKindV1.REACTION,bob,2,"❤️")))
        assertFalse(apply(controls,control(GroupMessageControlKindV1.REACTION,bob,1,"👍")))
        assertEquals("❤️",GroupChatStore(records).reactionBadges(group,target,null).single().emoji)
        assertTrue(apply(controls,control(GroupMessageControlKindV1.REACTION,bob,3,null)))
        assertTrue(GroupChatStore(records).reactionBadges(group,target,null).isEmpty())
        assertTrue(apply(controls,control(GroupMessageControlKindV1.REACTION,bob,4,"🙏")))
        assertTrue(apply(controls,control(GroupMessageControlKindV1.DELETE_BY_SENDER)))
        assertTrue(GroupChatStore(records).reactionBadges(group,target,null).isEmpty())
        assertFalse(apply(controls,control(GroupMessageControlKindV1.REACTION,bob,5,"😂")))
    }

    @Test fun editsReplaceTextRejectWrongAuthorAndCannotResurrectTerminal() {
        val records=MemoryRecords();val chats=GroupChatStore(records);accept(chats)
        val controls=GroupMessageControlStoreV1(records)
        assertFalse(apply(controls,control(GroupMessageControlKindV1.EDIT,bob,1,replacement="forged")))
        assertTrue(apply(controls,control(GroupMessageControlKindV1.EDIT,alice,2,replacement="new")))
        assertFalse(apply(controls,control(GroupMessageControlKindV1.EDIT,alice,1,replacement="old")))
        assertEquals("new",GroupChatStore(records).message(group,target)!!.text)
        assertEquals(2,GroupChatStore(records).message(group,target)!!.editRevision)
        assertTrue(chats.moderate(group,target))
        assertFalse(apply(controls,control(GroupMessageControlKindV1.EDIT,alice,3,replacement="resurrect")))
        assertEquals("",GroupChatStore(records).message(group,target)!!.text)
        assertEquals(GroupModerationState.REMOVED_BY_ADMIN,
            GroupChatStore(records).message(group,target)!!.moderationState)
    }

    @Test fun deleteBeforeTargetChecksActualAuthorAndAdminAlwaysWins() {
        val records=MemoryRecords();val chats=GroupChatStore(records)
        val controls=GroupMessageControlStoreV1(records)
        assertTrue(apply(controls,control(GroupMessageControlKindV1.DELETE_BY_SENDER,bob)))
        assertTrue(apply(controls,control(GroupMessageControlKindV1.DELETE_BY_SENDER,alice)))
        accept(chats)
        val deleted=GroupChatStore(records).message(group,target)!!
        assertEquals("",deleted.text)
        assertEquals(GroupModerationState.DELETED_BY_SENDER,deleted.moderationState)
        assertFalse(apply(controls,control(GroupMessageControlKindV1.EDIT,alice,1,replacement="again")))
        assertTrue(chats.moderate(group,target))
        assertEquals(GroupModerationState.REMOVED_BY_ADMIN,
            GroupChatStore(records).message(group,target)!!.moderationState)
        assertFalse(apply(controls,control(GroupMessageControlKindV1.DELETE_BY_SENDER,alice)))
        assertEquals(GroupModerationState.REMOVED_BY_ADMIN,
            GroupChatStore(records).message(group,target)!!.moderationState)
    }

    @Test fun duplicateControlIsIdempotent() {
        val records=MemoryRecords();val chats=GroupChatStore(records);accept(chats)
        val value=control(GroupMessageControlKindV1.EDIT,alice,1,replacement="once")
        val store=GroupMessageControlStoreV1(records)
        assertTrue(apply(store,value))
        assertFalse(apply(GroupMessageControlStoreV1(records),value))
        assertEquals("once",chats.message(group,target)!!.text)
    }
    @Test fun wrongSenderDeleteBeforeTargetCannotHideAnotherMembersText() {
        val records=MemoryRecords();val controls=GroupMessageControlStoreV1(records)
        assertTrue(apply(controls,control(GroupMessageControlKindV1.DELETE_BY_SENDER,bob)))
        val chats=GroupChatStore(records);accept(chats)
        assertEquals("original",chats.message(group,target)!!.text)
        assertEquals(GroupModerationState.NONE,chats.message(group,target)!!.moderationState)
    }
    @Test fun adminModerationBeforeSenderDeleteRemainsCanonicalAfterRestart() {
        val records=MemoryRecords();val chats=GroupChatStore(records);accept(chats)
        assertTrue(chats.moderate(group,target))
        assertFalse(apply(GroupMessageControlStoreV1(records),
            control(GroupMessageControlKindV1.DELETE_BY_SENDER,alice)))
        val restored=GroupChatStore(records).message(group,target)!!
        assertEquals(GroupModerationState.REMOVED_BY_ADMIN,restored.moderationState)
        assertEquals("",restored.text)
    }
}
