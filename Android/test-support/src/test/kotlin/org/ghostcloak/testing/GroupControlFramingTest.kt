package org.ghostcloak.testing

import org.ghostcloak.messaging.ConversationPayload
import org.ghostcloak.messaging.GroupControl
import org.ghostcloak.messaging.GroupControlCodec
import org.ghostcloak.messaging.GroupControlKind
import org.ghostcloak.messaging.GroupIds
import org.junit.Assert.*
import org.junit.Test

class GroupControlFramingTest {
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
}
