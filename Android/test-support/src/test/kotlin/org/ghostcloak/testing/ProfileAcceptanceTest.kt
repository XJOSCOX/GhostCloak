package org.ghostcloak.testing

import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.ghostcloak.crypto.*
import org.ghostcloak.identity.RandomIdentifiers
import org.ghostcloak.messaging.*
import org.ghostcloak.protocol.*
import org.ghostcloak.transport.IdempotentMessageTransport
import org.junit.Assert.*
import org.junit.Test

class ProfileAcceptanceTest {
    private class Wire : IdempotentMessageTransport {
        var offline=false
        val envelopes=mutableListOf<EncryptedEnvelope>()
        val submissions=mutableSetOf<String>()
        override fun receive()=emptyFlow<EncryptedEnvelope>()
        override suspend fun send(routingDestination:String,envelope:EncryptedEnvelope)=Unit
        override suspend fun submit(submissionId:String,routingDestination:String,envelope:EncryptedEnvelope):String {
            if(offline) throw ApiFailure(503,"offline")
            if(submissions.add(submissionId)) envelopes.add(envelope)
            return submissionId
        }
    }
    private class Pairing(nameA:String="Alice",nameB:String="Bob") {
        val ar=MemoryRecords(); val br=MemoryRecords()
        val ae=SignalProtocolEngine(ar); val be=SignalProtocolEngine(br)
        val a=ConversationService(ae,LocalRepository(ar)); val b=ConversationService(be,LocalRepository(br))
        val wire=Wire(); val outbox=DurableOutbox(br,be,wire)
        val aName=nameA; val bName=nameB
        lateinit var aid:String; lateinit var bid:String
        suspend fun prepare() {
            aid=a.create(aName).deviceId; bid=b.create(bName).deviceId
            a.importCard(b.exportCard())
            val first=ae.encrypt(bid,ConversationPayload.encode("Hello",0,displayName=aName))
            b.acceptNetwork(first,SenderProfile(ar.read("local/user")!!.decodeToString(),aid,
                RandomIdentifiers.create(),"7K4M9Q2FX8DR"))
        }
    }
    @Test fun acceptQueuesE2eeProfileAndUpdatesExistingContactWithoutMessageOrAlert()=runBlocking {
        val p=Pairing(); p.prepare()
        assertEquals("7K4M-9Q2F-X8DR",p.b.contacts().single().contact.visibleName)
        assertNotEquals("Bob",p.a.contacts().single().contact.visibleName)
        p.b.acceptRequest(p.aid,p.outbox)
        assertFalse(p.b.contacts().single().contact.request)
        assertEquals(1,p.outbox.pendingIds().size)
        p.b.retryNetwork(p.outbox)
        assertEquals(1,p.wire.envelopes.size)
        assertFalse(EnvelopeCodec.encode(p.wire.envelopes.single()).toString(Charsets.ISO_8859_1).contains("Bob"))
        val apiBytes=NetworkCodec.encode(ApiRequest.Send(RandomIdentifiers.create(),RandomIdentifiers.create(),
            EnvelopeCodec.encode(p.wire.envelopes.single())))
        assertFalse(apiBytes.toString(Charsets.ISO_8859_1).contains("Bob"))
        assertFalse(apiBytes.toString(Charsets.ISO_8859_1).contains("Alice"))
        p.a.acceptNetwork(p.wire.envelopes.single())
        assertEquals("Bob",p.a.contacts().single().contact.visibleName)
        assertEquals(1,p.a.contacts().size)
        assertTrue(p.a.messages(p.bid).isEmpty())
        assertEquals(0,p.a.unreadCount())
        assertTrue(NotificationLedger(p.ar).eligible().isEmpty())
        p.a.acceptNetwork(p.wire.envelopes.single())
        assertEquals(1,p.a.contacts().size)
        assertTrue(p.a.messages(p.bid).isEmpty())
        assertTrue(LocalRepository(p.br).profileIntents().isEmpty())
    }
    @Test fun offlineAcceptAndProcessRecreationKeepOneDurableResponse()=runBlocking {
        val p=Pairing(); p.prepare()
        p.wire.offline=true
        p.b.acceptRequest(p.aid,p.outbox)
        val recreatedEngine=SignalProtocolEngine(p.br)
        val recreated=ConversationService(recreatedEngine,LocalRepository(p.br))
        val recreatedOutbox=DurableOutbox(p.br,recreatedEngine,p.wire)
        assertFalse(recreated.contacts().single().contact.request)
        try { recreated.retryNetwork(recreatedOutbox); fail("Expected offline") } catch (_:ApiFailure) {}
        p.wire.offline=false
        recreated.retryNetwork(recreatedOutbox)
        recreated.retryNetwork(recreatedOutbox)
        assertEquals(1,p.wire.envelopes.size)
        p.a.acceptNetwork(p.wire.envelopes.single())
        assertEquals("Bob",p.a.contacts().single().contact.visibleName)
    }
    @Test fun crashAfterAtomicAcceptResumesAndRejectedRequestNeverSendsProfile()=runBlocking {
        val p=Pairing(); p.prepare()
        val crashing=DurableOutbox(p.br,p.be,p.wire,crash={ if(it==CrashPoint.AFTER_LOCAL) error("crash") })
        try { p.b.acceptRequest(p.aid,crashing); fail("Expected crash") } catch (_:IllegalStateException) {}
        assertFalse(LocalRepository(p.br).contact(p.aid).request)
        assertEquals(1,LocalRepository(p.br).profileIntents().size)
        val restarted=ConversationService(SignalProtocolEngine(p.br),LocalRepository(p.br))
        restarted.retryNetwork(DurableOutbox(p.br,SignalProtocolEngine(p.br),p.wire))
        assertEquals(1,p.wire.envelopes.size)
        val q=Pairing(); q.prepare(); q.b.deleteRequest(q.aid)
        assertTrue(q.outbox.pendingIds().isEmpty())
        assertTrue(LocalRepository(q.br).profileIntents().isEmpty())
    }
    @Test fun lostServerResponseReusesSubmissionAndDoesNotDuplicateProfile()=runBlocking {
        val p=Pairing();p.prepare();p.b.acceptRequest(p.aid,p.outbox)
        val crash=DurableOutbox(p.br,p.be,p.wire,crash={if(it==CrashPoint.AFTER_SERVER_ACCEPTANCE) error("crash")})
        try {p.b.retryNetwork(crash);fail("Expected lost response")}catch(_:IllegalStateException){}
        assertEquals(1,p.wire.envelopes.size)
        val restarted=ConversationService(SignalProtocolEngine(p.br),LocalRepository(p.br))
        restarted.retryNetwork(DurableOutbox(p.br,SignalProtocolEngine(p.br),p.wire))
        assertEquals(1,p.wire.envelopes.size)
        assertTrue(LocalRepository(p.br).profileIntents().isEmpty())
        p.a.acceptNetwork(p.wire.envelopes.single())
        p.a.acceptNetwork(p.wire.envelopes.single())
        assertEquals("Bob",p.a.contacts().single().contact.visibleName)
    }
    @Test fun localAliasWinsAndBlockedPeerDiscardsProfile()=runBlocking {
        val p=Pairing("Alex","Alex"); p.prepare()
        val before=p.a.contacts().single().contact
        LocalRepository(p.ar).save(before.copy(localAlias="Alex - Work"))
        p.b.acceptRequest(p.aid,p.outbox);p.b.retryNetwork(p.outbox)
        p.a.acceptNetwork(p.wire.envelopes.single())
        assertEquals("Alex - Work",p.a.contacts().single().contact.visibleName)
        assertEquals("Alex",p.a.contacts().single().contact.displayName)
        p.a.acceptNetwork(p.be.encrypt(p.aid,ConversationPayload.encodeProfile("Robert")))
        assertEquals("Alex - Work",p.a.contacts().single().contact.visibleName)
        assertEquals("Robert",p.a.contacts().single().contact.displayName)
        assertEquals(before.ghostCloakId,p.a.contacts().single().contact.ghostCloakId)
        val q=Pairing(); q.prepare(); q.b.acceptRequest(q.aid,q.outbox);q.b.retryNetwork(q.outbox)
        q.a.block(q.bid,true)
        q.a.acceptNetwork(q.wire.envelopes.single())
        assertNotEquals("Bob",LocalRepository(q.ar).contact(q.bid).displayName)
    }
    @Test fun profilePayloadIsStrictlyVersionedAndBounded() {
        val encoded=ConversationPayload.encodeProfile("Esaie")
        val parsed=ConversationPayload.decode(encoded)
        assertTrue(parsed.profileUpdate);assertEquals("Esaie",parsed.displayName)
        assertEquals(0,parsed.seconds);assertTrue(parsed.body.isEmpty())
        encoded[5]=99
        try {ConversationPayload.decode(encoded);fail("Unknown type accepted")}catch(_:AppFailure){}
        try {ConversationPayload.encodeProfile("bad\nname");fail("Control accepted")}catch(_:AppFailure){}
    }
}
