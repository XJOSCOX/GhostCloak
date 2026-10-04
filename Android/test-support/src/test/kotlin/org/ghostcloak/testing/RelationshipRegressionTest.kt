package org.ghostcloak.testing

import kotlinx.coroutines.runBlocking
import org.ghostcloak.crypto.*
import org.ghostcloak.messaging.*
import org.ghostcloak.protocol.*
import org.junit.Assert.*
import org.junit.Test

class RelationshipRegressionTest {
    @Test fun freshRequestAfterRejectStillUsesNormalMailboxAckAndDeliveryStatus()=runBlocking {
        AttachmentTest.Fixture().use {f->
            val a=f.person("alice");val b=f.person("bob")
            NetworkAccount(a.client,a.state).connect(b.state.ghostCloakId(),a.engine)
            val receiver=ConversationService(b.engine,LocalRepository(b.records));receiver.open()
            repeat(2) { cycle ->
                val packet=a.engine.encrypt(b.registration.deviceId,ConversationPayload.encode("private",0))
                a.client.call(ApiRequest.Send(packet.envelopeId,b.registration.routingId,EnvelopeCodec.encode(packet)))
                val delivery=b.client.call(ApiRequest.Fetch(includeSenders=true)).deliveries.single()
                receiver.acceptNetwork(EnvelopeCodec.decode(delivery.encryptedEnvelope),delivery.sender,delivery.receivedAt)
                b.client.call(ApiRequest.Ack(listOf(delivery.serverMessageId)))
                assertTrue(a.client.call(ApiRequest.Fetch(submissionIds=listOf(packet.envelopeId))).statuses.single().acknowledged)
                assertEquals(1,receiver.contacts().size);assertTrue(receiver.messagesForUi(a.registration.deviceId).isEmpty())
                assertEquals(packet.envelopeId,receiver.messages(a.registration.deviceId).single().localId)
                if(cycle==0) receiver.deleteRequest(a.registration.deviceId)
            }
        }
    }
    private class Pair {
        val ar=MemoryRecords(); val br=MemoryRecords()
        val ae=SignalProtocolEngine(ar); val be=SignalProtocolEngine(br)
        var elapsed=0L
        val repo=LocalRepository(br,ExpiryClock({1000000L+elapsed},{elapsed},{1}))
        val a=ConversationService(ae,LocalRepository(ar)); var b=ConversationService(be,repo)
        lateinit var aid:String; lateinit var bid:String; lateinit var profile:SenderProfile
        suspend fun open() {
            val ai=a.create("alice");val bi=b.create("bob")
            aid=ai.deviceId;bid=bi.deviceId
            profile=SenderProfile(ai.userId,aid,org.ghostcloak.identity.RandomIdentifiers.create(),"7K4M9Q2FX8DR")
            a.importCard(b.exportCard());b.serverReference(1000000)
        }
        suspend fun receive(attachment:Boolean=false):EncryptedEnvelope {
            val bytes=if(attachment) {
                val d=org.ghostcloak.attachments.AttachmentDescriptor(
                    id=org.ghostcloak.attachments.AttachmentFormat.newId(),key=ByteArray(16),
                    capability=ByteArray(32),digest=ByteArray(32),plaintextLength=1,
                    paddedLength=org.ghostcloak.attachments.AttachmentFormat.padded(1),
                    ciphertextLength=org.ghostcloak.attachments.AttachmentFormat.encryptedLength(org.ghostcloak.attachments.AttachmentFormat.padded(1)),
                    kind=org.ghostcloak.attachments.AttachmentKind.IMAGE,disappearingSeconds=0)
                ConversationPayload.encodeAttachment(d)
            } else ConversationPayload.encode("private",0)
            val packet=try {ae.encrypt(bid,bytes)} finally {bytes.fill(0)}
            b.acceptNetwork(packet,profile,1000000L+elapsed);return packet
        }
    }
    @Test fun rejectThenFreshTextRetainsPinAndReplayCannotRestoreOldContent()=runBlocking {
        val p=Pair();p.open();val first=p.receive();val pin=p.b.fingerprint(p.aid)
        val contact=p.repo.contact(p.aid)
        p.b.deleteRequest(p.aid)
        assertEquals(RelationshipState.DORMANT_UNACCEPTED,p.repo.relationshipState(p.aid))
        assertFalse(p.repo.isActiveContact(p.aid));assertFalse(contact.blocked)
        assertTrue(p.b.contacts().isEmpty());assertEquals(0,p.b.unreadCount())
        p.b.acceptNetwork(first,p.profile,1000000);assertTrue(p.b.contacts().isEmpty())
        val second=p.receive()
        assertEquals(RelationshipState.REQUEST_PENDING,p.repo.relationshipState(p.aid))
        assertTrue(p.b.messagesForUi(p.aid).isEmpty())
        assertEquals(second.envelopeId,p.b.messages(p.aid).single().localId)
        assertEquals(pin,p.b.fingerprint(p.aid));assertEquals(contact.contactId,p.repo.contact(p.aid).contactId)
        p.b.acceptNetwork(first,p.profile,1000000);assertEquals(1,p.b.messages(p.aid).size)
    }
    @Test fun explicitAddAfterRejectOrPendingReusesSessionAndIdentity()=runBlocking {
        for(reject in listOf(false,true)) {
            val p=Pair();p.open();p.receive();if(reject) p.b.deleteRequest(p.aid)
            val before=p.br.transaction {p.br.keys("session/").associateWith {p.br.read(it)!!}}
            val identity=p.b.open()!!;val contact=p.repo.contact(p.aid)
            val added=p.b.importCard(p.a.exportCard())
            assertEquals(contact.contactId,added.contactId);assertTrue(p.repo.isActiveContact(p.aid))
            assertEquals(1,p.repo.contacts().size)
            before.forEach {(k,v)->assertArrayEquals(v,p.br.transaction {p.br.read(k)})}
            assertArrayEquals(identity.publicKey,p.b.open()!!.publicKey)
            p.receive();assertFalse(p.b.messagesForUi(p.aid).isEmpty())
            try {p.b.importCard(p.a.exportCard());fail()} catch(e:AppFailure) {assertEquals(AppError.DUPLICATE_CONTACT,e.error)}
        }
    }
    @Test fun expiryThenNewRequestAndAddDoNotReviveOldEnvelope()=runBlocking {
        val p=Pair();p.open();val first=p.receive();p.elapsed=REQUEST_WINDOW
        assertTrue(p.b.contacts().isEmpty());assertEquals(RequestState.EXPIRED,p.repo.request(p.aid).state)
        p.b=ConversationService(p.be,p.repo);p.b.open()
        p.b.acceptNetwork(first,p.profile,1000000);assertTrue(p.b.contacts().isEmpty())
        p.receive();assertEquals(RelationshipState.REQUEST_PENDING,p.repo.relationshipState(p.aid))
        p.b.deleteRequest(p.aid);p.b.importCard(p.a.exportCard());assertTrue(p.repo.isActiveContact(p.aid))
        assertTrue(p.b.messages(p.aid).isEmpty())
    }
    @Test fun blockSuppressesNewRequestsAndExplicitAddCannotUnblock()=runBlocking {
        val p=Pair();p.open();p.receive();p.b.block(p.aid,true)
        p.receive()
        try {p.b.importCard(p.a.exportCard());fail()} catch(e:AppFailure) {assertEquals(AppError.BLOCKED,e.error)}
        assertEquals(RelationshipState.BLOCKED,p.repo.relationshipState(p.aid));assertTrue(p.b.messages(p.aid).isEmpty())
    }
    @Test fun explicitAddAfterExpiryKeepsOldContentDeleted()=runBlocking {
        val p=Pair();p.open();val first=p.receive();p.elapsed=REQUEST_WINDOW
        p.b.importCard(p.a.exportCard())
        assertTrue(p.repo.isActiveContact(p.aid));assertTrue(p.b.messages(p.aid).isEmpty())
        p.b.acceptNetwork(first,p.profile,1000000);assertTrue(p.b.messages(p.aid).isEmpty())
    }
    @Test fun dormantAddCannotBypassChangedIdentityWarning()=runBlocking {
        val p=Pair();p.open();p.receive();val pin=p.b.fingerprint(p.aid);p.b.deleteRequest(p.aid)
        val third=ConversationService(SignalProtocolEngine(MemoryRecords()),LocalRepository(MemoryRecords()))
        third.create("mallory")
        val original=ContactCardCodec.decode(p.a.exportCard())
        val replacement=ContactCardCodec.decode(third.exportCard())
        val forged=ContactCard(original.version,original.userId,original.ghostCloakId,original.deviceId,
            replacement.registrationId,replacement.identity,replacement.preKeyId,replacement.preKey,
            replacement.signedId,replacement.signedKey,replacement.signature,replacement.kyberId,
            replacement.kyberKey,replacement.kyberSignature)
        try {p.b.importCard(ContactCardCodec.encode(forged));fail()} catch(e:CryptoFailure) {assertEquals(CryptoError.IdentityChanged,e.error)}
        assertFalse(p.repo.isActiveContact(p.aid));assertEquals(pin,p.b.fingerprint(p.aid))
        assertNotNull(p.repo.pending(p.aid))
    }
    @Test fun rejectedPhotoThenNewPhotoRemainsHiddenAndUnavailableUntilAcceptance()=runBlocking {
        val p=Pair();p.open();val first=p.receive(true);p.b.deleteRequest(p.aid)
        assertFalse(p.repo.hasAttachment(p.aid,first.envelopeId))
        val second=p.receive(true);assertTrue(p.b.messagesForUi(p.aid).isEmpty())
        try {p.b.attachment(p.aid,second.envelopeId);fail()} catch(_:AppFailure) {}
        p.b.acceptRequest(p.aid);assertNotNull(p.b.attachment(p.aid,second.envelopeId))
        p.b.acceptNetwork(first,p.profile,1000000);assertFalse(p.repo.hasAttachment(p.aid,first.envelopeId))
    }
}
