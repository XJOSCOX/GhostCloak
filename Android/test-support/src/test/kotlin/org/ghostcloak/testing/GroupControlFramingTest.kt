package org.ghostcloak.testing

import kotlinx.coroutines.runBlocking
import org.ghostcloak.crypto.SignalProtocolEngine
import org.ghostcloak.messaging.ConversationPayload
import org.ghostcloak.messaging.ConversationService
import org.ghostcloak.messaging.GroupControl
import org.ghostcloak.messaging.GroupControlCodec
import org.ghostcloak.messaging.GroupControlKind
import org.ghostcloak.messaging.GroupIds
import org.ghostcloak.messaging.GroupText
import org.ghostcloak.messaging.AppFailure
import org.ghostcloak.messaging.LocalRepository
import org.junit.Assert.*
import org.junit.Test

class GroupControlFramingTest {
    @Test fun blockedClassifierNeverDecodesOrdinaryOrGroupUserText() {
        assertNull(ConversationPayload.decodeBlockedGroupSystem(ConversationPayload.encode("private text",0)))
        val groupText=ConversationPayload.encodeGroupText(GroupText(groupId=GroupIds.create(),
            epoch=1,senderMemberId=GroupIds.create(),logicalId=GroupIds.create(),text="secret"))
        assertNull(ConversationPayload.decodeBlockedGroupSystem(groupText))
        val control=ConversationPayload.encodeGroup(GroupControl(kind=GroupControlKind.RESYNC_REQUEST,
            groupId=GroupIds.create(),fromRevision=1,fromDigest=ByteArray(32)))
        assertEquals(GroupControlKind.RESYNC_REQUEST,
            ConversationPayload.decodeBlockedGroupSystem(control)?.kind)
        control[4]=99
        assertThrows(AppFailure::class.java) {ConversationPayload.decodeBlockedGroupSystem(control)}
    }
    @Test fun resyncIdentifiersRoundTripInApplicationFrame() {
        val id=GroupIds.create()
        val control=GroupControl(kind=GroupControlKind.RESYNC_REQUEST,groupId=id,
            fromRevision=3,fromDigest=ByteArray(32){it.toByte()})
        val frame=ConversationPayload.encodeGroup(control)
        assertTrue(frame.size<=16384)
        assertEquals(0,frame.size%256)
        val decoded=ConversationPayload.decode(frame).groupControl!!
        assertEquals(GroupControlKind.RESYNC_REQUEST,decoded.kind)
        assertEquals(id,decoded.groupId)
        assertArrayEquals(control.fromDigest,decoded.fromDigest)
    }

    @Test fun malformedAndOversizedControlsFailClosed() {
        val id=GroupIds.create()
        assertThrows(IllegalArgumentException::class.java) {
            GroupControlCodec.encode(GroupControl(kind=GroupControlKind.RESYNC_REQUEST,groupId=id,
                fromRevision=1,fromDigest=ByteArray(31)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            GroupControlCodec.decode(ByteArray(GroupControlCodec.MAX_BYTES+1))
        }
        val frame=ConversationPayload.encodeGroup(GroupControl(kind=GroupControlKind.RESYNC_REQUEST,
            groupId=id,fromRevision=1,fromDigest=ByteArray(32)))
        frame[5]=99
        assertThrows(Exception::class.java) {ConversationPayload.decode(frame)}
    }

    @Test fun membershipCapabilityIsAdvertisedInAuthenticatedPadding() {
        val payload=ConversationPayload.decode(ConversationPayload.encode("hello",0))
        assertTrue(payload.supportsGroups)
    }

    @Test fun sharedDisplayNameDoesNotCrowdOutGroupCapability() {
        for(name in listOf("JOSCOX","SRosier","A".repeat(32))) {
            val encoded=ConversationPayload.encode("Hi",0,displayName=name)
            val payload=ConversationPayload.decode(encoded)
            assertEquals("Hi",payload.body)
            assertTrue("group support missing for $name",payload.supportsGroups)
            assertTrue(payload.supportsAttachments)
            assertTrue(payload.supportsMedia)
            assertTrue(payload.supportsReactions)
            assertTrue(payload.supportsProfiles)
            assertTrue(payload.supportsDelete)
            assertTrue(payload.supportsEdit)
            if(name.length<16) assertEquals(name,payload.displayName)
        }
    }

    @Test fun acceptedEncryptedMessagePersistsGroupCapabilityForPicker()=runBlocking {
        val aliceRecords=MemoryRecords(); val bobRecords=MemoryRecords()
        val aliceEngine=SignalProtocolEngine(aliceRecords)
        val bobEngine=SignalProtocolEngine(bobRecords)
        val alice=ConversationService(aliceEngine,LocalRepository(aliceRecords))
        val bob=ConversationService(bobEngine,LocalRepository(bobRecords))
        val aliceId=alice.create("Alice").deviceId
        bob.create("JOSCOX")
        alice.importCard(bob.exportCard())
        bob.importCard(alice.exportCard())
        assertFalse(alice.contacts().single().groupCapable)
        alice.acceptNetwork(bobEngine.encrypt(aliceId,ConversationPayload.encode("Hi",0,displayName="JOSCOX")))
        assertTrue(alice.contacts().single().groupCapable)
        assertTrue(ConversationService(SignalProtocolEngine(aliceRecords),LocalRepository(aliceRecords))
            .contacts().single().groupCapable)
    }
}
