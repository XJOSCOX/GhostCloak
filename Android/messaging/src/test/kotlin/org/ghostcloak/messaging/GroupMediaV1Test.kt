package org.ghostcloak.messaging

import org.ghostcloak.attachments.AttachmentDescriptor
import org.ghostcloak.attachments.AttachmentFormat
import org.ghostcloak.attachments.AttachmentKind
import org.ghostcloak.crypto.EndpointRecords
import org.ghostcloak.identity.RandomIdentifiers
import org.junit.Assert.*
import org.junit.Test

class GroupMediaV1Test {
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
    private val target=GroupIds.create()
    private fun descriptor(kind:AttachmentKind=AttachmentKind.IMAGE):AttachmentDescriptor {
        val length=1024L
        val padded=AttachmentFormat.padded(length)
        return AttachmentDescriptor(id=AttachmentFormat.newId(),
            capability=ByteArray(32) {1},key=ByteArray(16) {2},digest=ByteArray(32) {3},
            plaintextLength=length,paddedLength=padded,
            ciphertextLength=AttachmentFormat.encryptedLength(padded),kind=kind,
            filename=if(kind==AttachmentKind.DOCUMENT) "notes.pdf" else null,
            durationMillis=if(kind==AttachmentKind.VOICE_NOTE) 2500 else null)
    }
    private fun media(kind:AttachmentKind=AttachmentKind.IMAGE,caption:String="hello")=GroupMediaV1(
        groupId=group,epoch=2,senderMemberId=alice,logicalId=target,
        governanceActivationDigest=ByteArray(32) {4},governanceSequence=1,
        governanceHeadDigest=ByteArray(32) {5},policyDigest=ByteArray(32) {6},
        descriptor=descriptor(kind),kind=kind,caption=caption)
    @Test fun separateFrameRoundTripAndBounds() {
        for(kind in listOf(AttachmentKind.IMAGE,AttachmentKind.DOCUMENT,AttachmentKind.VOICE_NOTE)) {
            val value=media(kind,"x".repeat(512))
            val frame=ConversationPayload.encodeGroupMedia(value)
            assertEquals(18,frame[5].toInt())
            assertTrue("Keep at least 1 KiB under the 8 KiB media frame cap",frame.size<=7168)
            val decoded=ConversationPayload.decode(frame).groupMedia!!
            assertEquals(kind,decoded.kind)
            assertEquals(value.caption,decoded.caption)
            assertArrayEquals(value.descriptor.key,decoded.descriptor.key)
            assertNull(ConversationPayload.decodeBlockedGroupSystemContent(frame))
            assertThrows(AppFailure::class.java) {
                ConversationPayload.encodeGroupMedia(value.copy(caption="x".repeat(513)))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            ConversationPayload.encodeGroupMedia(media().copy(kind=AttachmentKind.VIDEO))
        }
    }
    @Test fun acceptedMediaStoresDescriptorOnlyForActiveMessageAndReplaysOnce() {
        val records=MemoryRecords(); val chats=GroupChatStore(records)
        val value=media(); val sender=RandomIdentifiers.create();val envelope=RandomIdentifiers.create()
        chats.queueMedia(sender,envelope,value)
        assertTrue(chats.acceptMedia(envelope,PendingGroupMediaV1(sender,value)))
        assertEquals(AttachmentKind.IMAGE,chats.message(group,target)?.mediaKind)
        assertNotNull(LocalRepository(records).attachment(group,target))
        assertTrue("$group/$target" in LocalRepository(records).retainedAttachmentReferences())
        assertFalse(chats.acceptMedia(RandomIdentifiers.create(),PendingGroupMediaV1(sender,value)))
        assertEquals(1,chats.messages(group).size)
        assertTrue(chats.moderate(group,target))
        assertNull(LocalRepository(records).attachment(group,target))
        assertFalse("$group/$target" in LocalRepository(records).retainedAttachmentReferences())
        assertNull(chats.message(group,target)?.mediaCaption)
        assertEquals(GroupModerationState.REMOVED_BY_ADMIN,chats.message(group,target)?.moderationState)
    }
    @Test fun terminalBeforeArrivalNeverStoresCapabilityOrCaption() {
        val records=MemoryRecords();val chats=GroupChatStore(records)
        val value=media();val sender=RandomIdentifiers.create()
        assertTrue(chats.moderate(group,target))
        chats.queueMedia(sender,RandomIdentifiers.create(),value)
        assertTrue(chats.pendingMedia().isEmpty())
        assertNull(LocalRepository(records).attachment(group,target))
    }
    @Test fun outgoingMediaDeleteRetiresUnsentDescriptorsAndScrubsLocalSecrets() {
        val records=MemoryRecords();val chats=GroupChatStore(records);val value=media()
        val device=RandomIdentifiers.create()
        chats.createMedia(GroupChatMessage(group,target,value.epoch,alice,true,"",1,
            recipients=listOf(GroupRecipient(device)),mediaKind=value.kind,mediaCaption=value.caption),value)
        assertNotNull(LocalRepository(records).attachment(group,target))
        assertTrue(chats.deleteBySender(group,target,alice))
        assertNull(LocalRepository(records).attachment(group,target))
        val terminal=chats.message(group,target)!!
        assertNull(terminal.mediaCaption)
        assertEquals(GroupModerationState.DELETED_BY_SENDER,terminal.moderationState)
        assertEquals(GroupRecipientState.UNAVAILABLE,terminal.recipients.single().state)
    }
    @Test fun removingMemberCancelsUnsentMediaSlotWithoutChangingLogicalMessage() {
        val records=MemoryRecords();val chats=GroupChatStore(records);val value=media()
        val removed=RandomIdentifiers.create();val remaining=RandomIdentifiers.create()
        chats.createMedia(GroupChatMessage(group,target,value.epoch,alice,true,"",1,
            recipients=listOf(GroupRecipient(removed),GroupRecipient(remaining)),
            mediaKind=value.kind,mediaCaption=value.caption),value)
        chats.markRemoved(group,removed)
        val row=chats.message(group,target)!!
        assertEquals(1,chats.messages(group).size)
        assertEquals(GroupRecipientState.UNAVAILABLE,row.recipients.first {it.deviceId==removed}.state)
        assertEquals(GroupRecipientState.PENDING,row.recipients.first {it.deviceId==remaining}.state)
        assertNotNull(LocalRepository(records).attachment(group,target))
    }
}
