package org.ghostcloak.testing

import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.ghostcloak.attachments.*
import org.ghostcloak.backend.*
import org.ghostcloak.crypto.SignalProtocolEngine
import org.ghostcloak.messaging.*
import org.ghostcloak.protocol.*
import org.ghostcloak.transport.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

class ViewOnceTest {
    private class Wire : IdempotentMessageTransport {
        var offline=false
        val sent=mutableListOf<EncryptedEnvelope>()
        override fun receive()=emptyFlow<EncryptedEnvelope>()
        override suspend fun send(routingDestination:String,envelope:EncryptedEnvelope)=Unit
        override suspend fun submit(submissionId:String,routingDestination:String,envelope:EncryptedEnvelope):String {
            if(offline) throw ApiFailure(503,"offline")
            sent+=envelope
            return submissionId
        }
    }
    private class Pairing {
        val ar=MemoryRecords();val br=MemoryRecords()
        val ae=SignalProtocolEngine(ar);val be=SignalProtocolEngine(br)
        var now=1_000_000L
        val clock=ExpiryClock({now},{now},{1})
        val ap=LocalRepository(ar,clock);val bp=LocalRepository(br,clock)
        val a=ConversationService(ae,ap);var b=ConversationService(be,bp)
        val wire=Wire();val outbox=DurableOutbox(ar,ae,wire)
        lateinit var aid:String;lateinit var bid:String
        suspend fun open() {
            aid=a.create("Alice").deviceId;bid=b.create("Bob").deviceId
            a.importCard(b.exportCard());b.importCard(a.exportCard())
        }
        fun reopen() { b=ConversationService(be,bp) }
    }
    @Test fun textRevealIsDurableBeforeExposureAndRestartConsumesWithoutReceipt()=runBlocking {
        val p=Pairing();p.open()
        val outgoing=p.a.sendNetwork(p.bid,"one secret",p.outbox,true)
        assertEquals(MessageState.SERVER_ACCEPTED,outgoing.state)
        assertEquals("",p.ap.messages(p.bid).single().body)
        assertEquals("View Once message",p.a.messagesForUi(p.bid).single().body)
        val packet=p.wire.sent.single()
        p.b.acceptNetwork(packet)
        assertEquals("View Once message",p.b.messagesForUi(p.aid).single().body)
        assertEquals(ViewOnceState.AVAILABLE,p.bp.messages(p.aid).single().viewOnceState)
        assertEquals("one secret",p.b.beginViewOnce(p.aid,packet.envelopeId).body)
        assertEquals(ViewOnceState.REVEALING,p.bp.messages(p.aid).single().viewOnceState)
        p.reopen() // Process recreation: constructor finalizes interrupted reveal.
        assertEquals(ViewOnceState.CONSUMED,p.bp.messages(p.aid).single().viewOnceState)
        assertEquals("",p.bp.messages(p.aid).single().body)
        assertEquals("View Once message expired",p.b.messagesForUi(p.aid).single().body)
        try {p.b.beginViewOnce(p.aid,packet.envelopeId);fail("reopened")}
        catch(_:AppFailure) {}
        p.b.acceptNetwork(packet)
        assertEquals(1,p.b.messages(p.aid).size)
        assertEquals(1,p.wire.sent.size) // Revealing/consuming did not send any envelope.
    }
    @Test fun offlineSenderRetainsBodyOnlyUntilDurableServerAcceptance()=runBlocking {
        val p=Pairing();p.open();p.wire.offline=true
        val queued=p.a.sendNetwork(p.bid,"retry secret",p.outbox,true)
        assertEquals(MessageState.PENDING,queued.state)
        assertEquals("retry secret",p.ap.messages(p.bid).single().body)
        assertEquals("View Once message",p.a.messagesForUi(p.bid).single().body)
        p.wire.offline=false;p.a.retryNetwork(p.outbox)
        assertEquals("",p.ap.messages(p.bid).single().body)
        assertEquals(MessageState.SERVER_ACCEPTED,p.ap.messages(p.bid).single().state)
    }
    @Test fun photoHasNoInlineSummaryAndCannotDownloadBeforeRevealOrAfterConsume()=runBlocking {
        val p=Pairing();p.open()
        val descriptor=AttachmentDescriptor(id=AttachmentFormat.newId(),key=ByteArray(16),
            capability=ByteArray(32),digest=ByteArray(32),plaintextLength=1,
            paddedLength=AttachmentFormat.padded(1),
            ciphertextLength=AttachmentFormat.encryptedLength(AttachmentFormat.padded(1)),
            kind=AttachmentKind.IMAGE,disappearingSeconds=0)
        val packet=p.ae.encrypt(p.bid,ConversationPayload.encodeAttachment(descriptor,viewOnce=true))
        p.b.acceptNetwork(packet)
        assertNull(p.b.messagesForUi(p.aid).single().attachment)
        assertEquals("View Once photo",p.b.messagesForUi(p.aid).single().body)
        assertFalse(p.bp.attachmentAvailable(p.aid,packet.envelopeId))
        p.b.beginViewOnce(p.aid,packet.envelopeId)
        assertTrue(p.bp.attachmentAvailable(p.aid,packet.envelopeId))
        p.b.consumeViewOnce(p.aid,packet.envelopeId)
        assertFalse(p.bp.attachmentAvailable(p.aid,packet.envelopeId))
        assertNull(p.bp.attachment(p.aid,packet.envelopeId))
        p.reopen();p.b.acceptNetwork(packet)
        assertEquals("View Once photo expired",p.b.messagesForUi(p.aid).single().body)
    }
    @Test fun requestAcceptDoesNotConsumeAndRemoveBlockExpiryCannotResurrect()=runBlocking {
        val p=Pairing();p.open()
        p.b.removeContact(p.aid)
        val packet=p.ae.encrypt(p.bid,ConversationPayload.encode("request secret",30,viewOnce=true))
        p.b.acceptNetwork(packet)
        assertTrue(p.b.messagesForUi(p.aid).isEmpty())
        try {p.b.beginViewOnce(p.aid,packet.envelopeId);fail("request opened")}
        catch(_:AppFailure) {}
        p.b.acceptRequest(p.aid)
        assertEquals(ViewOnceState.AVAILABLE,p.bp.messages(p.aid).single().viewOnceState)
        p.b.beginViewOnce(p.aid,packet.envelopeId);p.b.consumeViewOnce(p.aid,packet.envelopeId)
        p.b.removeContact(p.aid);p.b.importCard(p.a.exportCard())
        assertEquals("",p.bp.messages(p.aid).single().body)
        p.b.block(p.aid,true);p.b.block(p.aid,false)
        p.b.acceptNetwork(packet)
        assertEquals(ViewOnceState.CONSUMED,p.bp.messages(p.aid).single().viewOnceState)
        p.now+=31_000;p.b.reconcileExpiry()
        assertTrue(p.b.messages(p.aid).isEmpty())
        try {p.b.beginViewOnce(p.aid,packet.envelopeId);fail("expired opened")}
        catch(_:AppFailure) {}
    }
    @Test fun blockingDiscardsAlreadyAvailableViewOnceAndUnblockCannotReviveIt()=runBlocking {
        val p=Pairing();p.open()
        val packet=p.ae.encrypt(p.bid,ConversationPayload.encode("blocked secret",0,viewOnce=true))
        p.b.acceptNetwork(packet)
        assertEquals(ViewOnceState.AVAILABLE,p.bp.messages(p.aid).single().viewOnceState)
        p.b.block(p.aid,true)
        assertEquals(ViewOnceState.CONSUMED,p.bp.messages(p.aid).single().viewOnceState)
        assertEquals("",p.bp.messages(p.aid).single().body)
        p.b.block(p.aid,false);p.b.importCard(p.a.exportCard())
        p.b.acceptNetwork(packet)
        try {p.b.beginViewOnce(p.aid,packet.envelopeId);fail("block revived item")}
        catch(_:AppFailure) {}
    }
    @Test fun unknownViewOnceTypeFailsClosedOnLegacyParserAndMalformedPolicyIsRejected() {
        val text=ConversationPayload.encode("secret",0,viewOnce=true)
        assertEquals(5,text[5].toInt())
        assertEquals(ViewOnceKind.TEXT,ConversationPayload.decode(text).viewOnceKind)
        text[5]=7
        try {ConversationPayload.decode(text);fail("unknown type")}
        catch(_:AppFailure) {}
        text[5]=5;text[6]=1
        try {ConversationPayload.decode(text);fail("conflicting header")}
        catch(_:AppFailure) {}
    }
    @Test fun encryptedPhotoTransferRemovesRecipientKeyAndBothCachesAfterConsumption()=runBlocking {
        AttachmentTest.Fixture().use { fixture ->
            val a=fixture.person("alice");val b=fixture.person("bob")
            NetworkAccount(a.client,a.state).connect(b.state.ghostCloakId(),a.engine)
            val ar=LocalRepository(a.records);val br=LocalRepository(b.records)
            ar.save(Contact("synthetic",b.registration.accountId,"Bob",b.registration.deviceId))
            val sender=ConversationService(a.engine,ar);sender.open()
            val receiver=ConversationService(b.engine,br);receiver.open()
            val payload=byteArrayOf(1,3,5,7,9)
            val descriptor=a.store.prepare(payload.inputStream(),payload.size.toLong(),AttachmentKind.IMAGE,0){true}
            a.store.upload(descriptor.id,a.bulk){true}
            val outbox=DurableOutbox(a.records,a.engine,NetworkMailboxTransport(a.client,a.state))
            val sent=sender.sendAttachment(b.registration.deviceId,descriptor,outbox,true,true) {
                a.store.bind(descriptor.id,"${it.conversationId}/${it.localId}")
            }
            assertEquals(MessageState.SERVER_ACCEPTED,sent.state)
            assertNull(ar.attachment(b.registration.deviceId,sent.localId))
            a.store.reconcileReferences(ar.retainedAttachmentReferences(),emptySet())
            assertNull(a.store.entry(descriptor.id))
            val delivery=b.client.call(ApiRequest.Fetch(includeSenders=true)).deliveries.single()
            val packet=EnvelopeCodec.decode(delivery.encryptedEnvelope)
            receiver.acceptNetwork(packet,delivery.sender)
            assertTrue(receiver.messagesForUi(a.registration.deviceId).isEmpty())
            receiver.acceptRequest(a.registration.deviceId)
            assertEquals("View Once photo",receiver.messagesForUi(a.registration.deviceId).single().body)
            assertFalse(br.attachmentAvailable(a.registration.deviceId,packet.envelopeId))
            receiver.beginViewOnce(a.registration.deviceId,packet.envelopeId)
            val received=receiver.attachment(a.registration.deviceId,packet.envelopeId)!!
            b.store.download(received,"${a.registration.deviceId}/${packet.envelopeId}",b.bulk){
                br.attachmentAvailable(a.registration.deviceId,packet.envelopeId)
            }.use { verified ->
                val output=ByteArrayOutputStream();verified.copyTo(output)
                assertArrayEquals(payload,output.toByteArray())
            }
            receiver.consumeViewOnce(a.registration.deviceId,packet.envelopeId)
            b.store.reconcileReferences(br.retainedAttachmentReferences(),emptySet())
            assertNull(br.attachment(a.registration.deviceId,packet.envelopeId))
            assertNull(b.store.entry(descriptor.id))
            assertTrue(File(b.directory,"download").listFiles().orEmpty().isEmpty())
            b.client.call(ApiRequest.Ack(listOf(delivery.serverMessageId)))
            assertTrue(a.client.call(ApiRequest.Fetch(submissionIds=listOf(sent.localId))).statuses.single().acknowledged)
        }
    }
}
