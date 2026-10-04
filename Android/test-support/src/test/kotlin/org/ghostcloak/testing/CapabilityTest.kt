package org.ghostcloak.testing

import kotlinx.coroutines.runBlocking
import org.ghostcloak.attachments.*
import org.ghostcloak.backend.*
import org.ghostcloak.crypto.*
import org.ghostcloak.capabilities.CapabilitySignatures
import org.ghostcloak.messaging.*
import org.ghostcloak.protocol.*
import org.ghostcloak.transport.*
import org.ghostcloak.identity.RandomIdentifiers
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

internal object CapabilityProbe {
    suspend fun exercise(db:BackendDatabase) {
        val f=PrekeyFixture(db);f.start()
        val r=f.registration;val identity=f.records.read("local/key")!!
        val response=f.service.execute(ApiRequest.Capabilities(),f.state.read())
        assertEquals(16,response.capabilityInventory.size)
        val time=response.serverTime!!
        val signed=response.capabilityInventory.map {it.withCapability(f.engine.signAttachmentCapability("ghostcloak.local",r.accountId,r.routingId,it,time))}
        f.service.execute(ApiRequest.Capabilities(signed),f.state.read())
        assertEquals(16,f.inventory().available)
        // Same bundle pool, identity, account and prekey history; no duplicate upload or new keys.
        assertArrayEquals(identity,f.records.read("local/key"))
        val persisted=db.transaction {db.prekeys.get(r.deviceId)!!}
        assertEquals(signed.map {it.preKeyId},persisted.pool.map {it.preKeyId})
        persisted.pool.forEach {assertTrue(CapabilitySignatures.verify("ghostcloak.local",r.accountId,r.routingId,it,time))}
        val refresh=f.service.execute(ApiRequest.CapabilityLookup(r.deviceId),f.state.read())
        assertNotNull(refresh.directory!!.bundle.capability)
        assertEquals(16,f.inventory().available)
        val legacy=f.service.execute(ApiRequest.Lookup(f.state.ghostCloakId()),f.state.read())
        assertNull(legacy.directory!!.bundle.capability);assertNull(legacy.serverTime)
        val modern=f.service.execute(ApiRequest.Lookup(f.state.ghostCloakId(),capabilities=true),f.state.read())
        assertNotNull(modern.directory!!.bundle.capability)
        assertEquals(14,f.inventory().available)
        assertEquals(14,db.transaction {db.prekeys.get(r.deviceId)!!.pool.count {it.capability!=null}})
        try {f.service.execute(ApiRequest.Capabilities(listOf(signed.first())),f.state.read());fail()}
        catch(e:ApiFailure){assertEquals(409,e.status)} // consumed keys cannot be resurrected
        val refill=f.engine.publicBundle().publicData()
        f.service.execute(ApiRequest.Prekeys(r.deviceId,listOf(refill)),f.state.read())
        assertEquals(14,db.transaction {db.prekeys.get(r.deviceId)!!.pool.count {it.capability!=null}})
        assertArrayEquals(identity,f.records.read("local/key"))
    }
}

class CapabilityTest {
    @Test fun publicationRefreshConsumptionAndRefillPreserveExistingPoolAndIdentity()=runBlocking {
        CapabilityProbe.exercise(MemoryBackendDatabase())
    }
    @Test fun proofsRejectForgeryTransplantExpiryAndWrongDomain()=runBlocking {
        val f=PrekeyFixture();f.start();val r=f.registration;val b=r.bundles.first()
        val now=System.currentTimeMillis()
        val proof=f.engine.signAttachmentCapability("ghostcloak.local",r.accountId,r.routingId,b,now)
        val signed=b.withCapability(proof)
        assertTrue(CapabilitySignatures.verify("ghostcloak.local",r.accountId,r.routingId,signed,now))
        fun valid(bundle:PublicBundle=signed,audience:String="ghostcloak.local",account:String=r.accountId,route:String=r.routingId,time:Long=now)=
            CapabilitySignatures.verify(audience,account,route,bundle,time)
        assertFalse(valid(audience="elsewhere.invalid"));assertFalse(valid(account=RandomIdentifiers.create()))
        assertFalse(valid(route=RandomIdentifiers.create()));assertFalse(valid(time=proof.expiresAt))
        assertFalse(valid(time=now-1))
        assertFalse(valid(r.bundles[1].withCapability(proof)))
        val other=SignalProtocolEngine(MemoryRecords());other.createIdentity("other")
        val otherBundle=other.publicBundle().publicData()
        assertFalse(valid(otherBundle.withCapability(proof)))
        for(bad in listOf(
            AttachmentCapability(2,1,proof.issuedAt,proof.expiresAt,proof.bundleDigest,proof.signature),
            AttachmentCapability(1,2,proof.issuedAt,proof.expiresAt,proof.bundleDigest,proof.signature),
            AttachmentCapability(1,1,proof.issuedAt,proof.expiresAt,ByteArray(32),proof.signature),
            AttachmentCapability(1,1,proof.issuedAt,proof.expiresAt,proof.bundleDigest,proof.signature.copyOf().also {it[3]=(it[3].toInt() xor 1).toByte()}),
            AttachmentCapability(1,1,proof.issuedAt,proof.expiresAt+1,proof.bundleDigest,proof.signature))) {
            assertFalse(valid(b.withCapability(bad)))
            try {f.service.execute(ApiRequest.Capabilities(listOf(b.withCapability(bad))),f.state.read());fail()}
            catch(_:ApiFailure){}
        }
        val newer=b.withCapability(f.engine.signAttachmentCapability("ghostcloak.local",r.accountId,r.routingId,b,now+1))
        // Local anti-rollback floor survives recreation and marker absence is independent evidence.
        var elapsed=100L;var wall=now;var boot=1
        val repo=LocalRepository(f.records,ExpiryClock({wall},{elapsed},{boot}))
        repo.directoryCapability(b.deviceId,now+1,1000)
        repo.attachmentPeer(b.deviceId,false);assertTrue(repo.attachmentPeer(b.deviceId))
        try {repo.directoryCapability(b.deviceId,now,1000);fail()}catch(_:IllegalArgumentException){}
        val reopened=LocalRepository(f.records,ExpiryClock({wall},{elapsed},{boot}))
        assertTrue(reopened.attachmentPeer(b.deviceId))
        wall-=60000;elapsed+=600
        reopened.clearDirectoryCapability(b.deviceId)
        reopened.directoryCapability(b.deviceId,now+1,1000) // replay must not reset the monotonic deadline
        elapsed+=401;assertFalse(reopened.attachmentPeer(b.deviceId))
        repo.directoryCapability(b.deviceId,now+2,1000);boot++;assertFalse(repo.attachmentPeer(b.deviceId))
        repo.attachmentPeer(b.deviceId,true);repo.resetCapabilities(b.deviceId);assertFalse(repo.attachmentPeer(b.deviceId))
        assertFalse(valid(newer)) // publication cannot advertise future time
    }
    @Test fun firstPhotoAndDocumentAreHiddenRequestsWithoutReplyOrPrematureDownload()=runBlocking {
        for(kind in listOf(AttachmentKind.IMAGE,AttachmentKind.DOCUMENT)) AttachmentTest.Fixture().use {f ->
            val a=f.person("alice");val b=f.person("bob")
            val publisher=CapabilityDiscovery(b.engine,b.state,"ghostcloak.local",b.client)
            publisher.publish()
            val discovery=CapabilityDiscovery(a.engine,a.state,"ghostcloak.local",a.client)
            val (entry,time)=discovery.lookup(b.state.ghostCloakId())
            a.engine.establishSession(entry.bundle.remote())
            val ar=LocalRepository(a.records);ar.save(Contact("fixture",entry.accountId,"bob",entry.deviceId))
            val sender=ConversationService(a.engine,ar);sender.open()
            discovery.accept(entry,time,sender)
            assertTrue(sender.attachmentPeer(entry.deviceId))
            assertTrue(sender.messages(entry.deviceId).isEmpty())
            val bytes=byteArrayOf(1,2,3)
            val d=a.store.prepare(bytes.inputStream(),3,kind,30,if(kind==AttachmentKind.DOCUMENT)"private.pdf" else null){true}
            a.store.upload(d.id,a.bulk){true}
            val outbox=DurableOutbox(a.records,a.engine,NetworkMailboxTransport(a.client,a.state))
            sender.sendAttachment(entry.deviceId,d,outbox,sender.attachmentPeer(entry.deviceId))
            val receiver=ConversationService(b.engine,LocalRepository(b.records));receiver.open()
            val inbox=b.client.call(ApiRequest.Fetch(includeSenders=true,retention=true))
            receiver.serverReference(inbox.serverTime!!)
            val delivery=inbox.deliveries.single()
            receiver.acceptNetwork(EnvelopeCodec.decode(delivery.encryptedEnvelope),delivery.sender,delivery.receivedAt)
            val id=a.registration.deviceId;val message=receiver.messages(id).single()
            assertTrue(receiver.messagesForUi(id).isEmpty())
            try {receiver.attachment(id,message.localId);fail()}catch(_:AppFailure){}
            assertTrue(java.io.File(b.directory,"download").listFiles().orEmpty().isEmpty())
            b.client.call(ApiRequest.Ack(listOf(delivery.serverMessageId)))
            receiver.acceptRequest(id)
            val summary=receiver.messagesForUi(id).single().attachment!!
            assertEquals(kind==AttachmentKind.IMAGE,summary.photo)
            assertEquals(if(kind==AttachmentKind.IMAGE)"Photo" else "private.pdf",summary.filename)
            // Download still requires the accepted service gate; documents remain explicit user action.
            val descriptor=receiver.attachment(id,message.localId)!!
            b.store.download(descriptor,message.localId,b.bulk){true}.use {stream ->
                val output=ByteArrayOutputStream();stream.copyTo(output);assertArrayEquals(bytes,output.toByteArray())
            }
        }
    }
    @Test fun unsignedOldPeerStillAllowsTextAndRefreshWorksAfterUpgradeWithoutConsumingKeys()=runBlocking {
        AttachmentTest.Fixture().use {f ->
            val a=f.person("alice");val b=f.person("bob")
            // Keep a second unused bundle available for refresh after ordinary lookup.
            b.client.publish(b.registration.deviceId,listOf(b.engine.publicBundle().publicData()))
            val discovery=CapabilityDiscovery(a.engine,a.state,"ghostcloak.local",a.client)
            val (entry,time)=discovery.lookup(b.state.ghostCloakId());assertNull(entry.bundle.capability)
            a.engine.establishSession(entry.bundle.remote())
            val repo=LocalRepository(a.records);repo.save(Contact("fixture",entry.accountId,"bob",entry.deviceId))
            val service=ConversationService(a.engine,repo);service.open();discovery.accept(entry,time,service)
            assertFalse(discovery.refresh(entry.deviceId,service))
            val outbox=DurableOutbox(a.records,a.engine,NetworkMailboxTransport(a.client,a.state))
            assertEquals(MessageState.SERVER_ACCEPTED,service.sendNetwork(entry.deviceId,"hi",outbox).state)
            CapabilityDiscovery(b.engine,b.state,"ghostcloak.local",b.client).publish()
            assertTrue(discovery.refresh(entry.deviceId,service))
            assertEquals(1,f.db.transaction {f.db.prekeys.get(entry.deviceId)!!.pool.size})
            val reopened=ConversationService(a.engine,LocalRepository(a.records));reopened.open()
            assertTrue(reopened.attachmentPeer(entry.deviceId))
        }
    }
    @Test fun canonicalFieldsRemainOmittedAndOldProtocolCannotSilentlyFallback()=runBlocking {
        val f=PrekeyFixture();f.start();val b=f.registration.bundles.first()
        assertArrayEquals(NetworkCodec.encode(b),NetworkCodec.encode(b.withCapability(null)))
        assertEquals(0xab,NetworkCodec.encode(b).first().toInt() and 255) // exactly the original eleven fields
        val id=f.state.ghostCloakId()
        val lookupWire=NetworkCodec.encode<ApiRequest>(ApiRequest.Lookup(id))
        assertFalse(lookupWire.toString(Charsets.ISO_8859_1).contains("username"))
        assertTrue(lookupWire.toString(Charsets.ISO_8859_1).contains("ghostCloakId"))
        assertFalse(lookupWire.toString(Charsets.ISO_8859_1).contains("capabilities"))
        assertFalse(NetworkCodec.encode(b).toString(Charsets.ISO_8859_1).contains("capability"))
        var calls=0
        val client=HttpGhostClient("https://ghostcloak.local",f.state,transport=GhostCloakTransport {request ->
            calls++
            TransportResponse(200,NetworkLimits.CONTENT_TYPE,NetworkCodec.encode(ApiResponse(version=1,
                directory=DirectoryEntry(f.registration.accountId,b.deviceId,f.registration.routingId,id,b))))
        })
        val discovery=CapabilityDiscovery(f.engine,f.state,"ghostcloak.local",client)
        try { discovery.lookup(id); fail("Accepted old protocol") }
        catch(e:ApiFailure) { assertEquals("invalid_response",e.code) }
        assertEquals(1,calls)
        assertFalse(LocalRepository(f.records).attachmentPeer(b.deviceId))
    }
}
