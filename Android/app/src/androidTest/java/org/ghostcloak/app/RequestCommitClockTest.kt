package org.ghostcloak.app

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.ghostcloak.attachments.*
import org.ghostcloak.crypto.*
import org.ghostcloak.identity.RandomIdentifiers
import org.ghostcloak.messaging.*
import org.ghostcloak.protocol.*
import org.ghostcloak.storage.EncryptedEndpointStore
import org.junit.Assert.*
import org.junit.Test

class RequestCommitClockTest {
    @Test fun encryptedStoreReopenRetainsPhotoDocumentCommitExpiryReplayAndFreshRequest()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val an=RandomIdentifiers.create();val bn=RandomIdentifiers.create()
        var elapsed=0L;var boot=1
        val clock=ExpiryClock({1000},{elapsed},{boot})
        EncryptedEndpointStore.open(context,an).use {ar->
            val ae=SignalProtocolEngine(ar);val a=ConversationService(ae,LocalRepository(ar));val ai=a.create("alice")
            lateinit var bi:org.ghostcloak.identity.DeviceIdentity
            val profile=SenderProfile(ai.userId,ai.deviceId,RandomIdentifiers.create(),"7K4M9Q2FX8DR")
            EncryptedEndpointStore.open(context,bn).use {br->
                val b=ConversationService(SignalProtocolEngine(br),LocalRepository(br,clock))
                bi=b.create("bob");a.importCard(b.exportCard())
            }
            val packets=mutableListOf<EncryptedEnvelope>()
            EncryptedEndpointStore.open(context,bn).use {br->
                val repo=LocalRepository(br,clock);val b=ConversationService(SignalProtocolEngine(br),repo);b.open()
                b.serverReference(1000000+6L*86400000)
                for(kind in listOf(AttachmentKind.IMAGE,AttachmentKind.DOCUMENT)) {
                    val padded=AttachmentFormat.padded(1)
                    val d=AttachmentDescriptor(id=AttachmentFormat.newId(),capability=ByteArray(32),key=ByteArray(16),
                        digest=ByteArray(32),plaintextLength=1,paddedLength=padded,
                        ciphertextLength=AttachmentFormat.encryptedLength(padded),kind=kind,filename="private.bin")
                    val packet=ae.encrypt(bi.deviceId,ConversationPayload.encodeAttachment(d));packets+=packet
                    b.acceptNetwork(packet,profile,1000000)
                    assertTrue(repo.hasAttachment(ai.deviceId,packet.envelopeId))
                }
                assertEquals(1000000+6L*86400000,repo.request(ai.deviceId).acceptedAt)
                assertTrue(b.messagesForUi(ai.deviceId).isEmpty())
            }
            elapsed=REQUEST_WINDOW-1
            EncryptedEndpointStore.open(context,bn).use {br->
                val repo=LocalRepository(br,clock);val b=ConversationService(SignalProtocolEngine(br),repo)
                assertArrayEquals(bi.publicKey,b.open()!!.publicKey);assertFalse(repo.requestExpired(ai.deviceId))
                assertEquals(2,b.messages(ai.deviceId).size)
            }
            boot++;elapsed=0
            EncryptedEndpointStore.open(context,bn).use {br->
                val repo=LocalRepository(br,clock);val b=ConversationService(SignalProtocolEngine(br),repo);b.open()
                try {b.acceptRequest(ai.deviceId);fail()}catch(_:IllegalArgumentException){}
                b.serverReference(1000000+6L*86400000+REQUEST_WINDOW)
                b.reconcileExpiry()
                assertEquals(RequestState.EXPIRED,repo.request(ai.deviceId).state)
                assertTrue(b.messages(ai.deviceId).isEmpty());assertEquals(0,b.unreadCount())
                for(packet in packets) {
                    assertFalse(repo.hasAttachment(ai.deviceId,packet.envelopeId));b.acceptNetwork(packet,profile,1000000)
                }
                assertTrue(b.contacts().isEmpty());assertTrue(br.transaction {br.keys("app/notification/").isEmpty()})
                assertTrue(br.transaction {br.keys("app/read/").isEmpty()})
                b.acceptNetwork(ae.encrypt(bi.deviceId,ConversationPayload.encode("fresh",0)),profile,1000000)
                assertEquals(RequestState.PENDING,repo.request(ai.deviceId).state)
                assertTrue(b.messagesForUi(ai.deviceId).isEmpty());assertFalse(repo.contact(ai.deviceId).blocked)
            }
        }
    }
}
