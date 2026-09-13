package org.ghostcloak.testing

import org.ghostcloak.messaging.*
import org.ghostcloak.attachments.AttachmentFormat
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class AttachmentCapabilityTest {
    @Test fun capabilityIsAuthenticatedPaddingNotTextOrNewFraming() {
        val encoded=ConversationPayload.encode("hello",0)
        val decoded=ConversationPayload.decode(encoded)
        assertTrue(decoded.supportsAttachments);assertEquals("hello",decoded.body)
        // Exact old decoder layout: type remains 1, text length and content unchanged.
        val old=ByteBuffer.wrap(encoded);old.position(5);assertEquals(1,old.get().toInt())
        old.position(12);val length=old.int
        assertEquals("hello",ByteArray(length).also { old.get(it) }.decodeToString())
        assertFalse(ConversationPayload.decode("GhostCloak/padding/attachments/v1!".toByteArray()).supportsAttachments)
        val legacy=encoded.copyOf();legacy.fill(0,16+length,legacy.size)
        assertFalse(ConversationPayload.decode(legacy).supportsAttachments)
        val quoted=ConversationPayload.encode("GhostCloak/padding/attachments/v1!",0)
        quoted.fill(0,16+"GhostCloak/padding/attachments/v1!".length,quoted.size)
        assertFalse(ConversationPayload.decode(quoted).supportsAttachments)
    }
    @Test fun oldTextLimitsAndPaddingBoundariesRemainValid() {
        for(length in listOf(1,208,224,239,240,241,16368)) {
            val bytes=ConversationPayload.encode("x".repeat(length),30)
            assertEquals("x".repeat(length),ConversationPayload.decode(bytes).body)
            assertTrue(bytes.size<=16384);assertEquals(0,bytes.size%256)
        }
    }
    @Test fun displayFilenamesStayBoundedAndCannotBecomePaths() {
        for(value in listOf("../../private/secret.pdf","a\\b:c.pdf","\u202etest\u0000\u2066.pdf",".","..","","é".repeat(200))) {
            val name=AttachmentFormat.sanitizeFilename(value)
            assertTrue(name.toByteArray().size<=128)
            assertFalse(name.any { it.isISOControl() || it in "/\\:" || it in '\u202a'..'\u202e' || it in '\u2066'..'\u2069' })
            assertFalse(name in setOf("",".",".."))
        }
        assertTrue(AttachmentFormat.encryptedLength(AttachmentFormat.padded(20L*1048576))<26L*1048576)
    }
}
