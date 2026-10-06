package org.ghostcloak.testing

import kotlinx.coroutines.runBlocking
import org.ghostcloak.backend.*
import org.ghostcloak.crypto.*
import org.ghostcloak.identity.RandomIdentifiers
import org.ghostcloak.messaging.*
import org.ghostcloak.protocol.*
import org.ghostcloak.transport.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class SubmissionProtocolTest {
    @Test fun trustedReferenceIgnoresWallChangesAndRejectsRebootOrStaleSample() {
        var wall=2_000_000_000_000L;var elapsed=100_000L;var boot=1
        val records=MemoryRecords()
        fun repo()=LocalRepository(records,ExpiryClock({wall},{elapsed},{boot}))
        repo().serverReference(wall)
        assertEquals(wall,repo().freshServerNow())
        wall+=20L*24*60*60*1000
        elapsed+=1000
        assertEquals(2_000_000_001_000L,repo().freshServerNow())
        wall-=40L*24*60*60*1000
        elapsed+=1000
        assertEquals(2_000_000_002_000L,repo().freshServerNow())
        elapsed+=10*60*1000+1
        assertNull(repo().freshServerNow())
        boot++
        elapsed=100
        assertNull(repo().freshServerNow())
    }

    @Test fun acceptedStatusStopsAtExpiryWithoutClaimingDelivery() = runBlocking {
        var elapsed=100L
        val server=2_000_000_000_000L
        val records=MemoryRecords()
        val clock=ExpiryClock({server},{elapsed},{1})
        val repository=LocalRepository(records,clock)
        val peer=RandomIdentifiers.create()
        repository.save(Contact(RandomIdentifiers.create(),RandomIdentifiers.create(),"Bob",peer))
        repository.serverReference(server)
        val networkId=SubmissionIds.create(server+5000)
        val localId=RandomIdentifiers.create()
        repository.save(Message(localId,peer,Direction.OUTGOING,"hi",server,MessageState.SERVER_ACCEPTED,
            transportSubmissionId=networkId))
        fun service()=ConversationService(SignalProtocolEngine(records),LocalRepository(records,clock))
        assertEquals(listOf(networkId),service().queuedSubmissions())
        // Lost status response and repeated 404s inside the window leave an honest pending status.
        repeat(2) { assertEquals(listOf(networkId),service().queuedSubmissions()) }
        elapsed+=5001
        assertTrue(service().queuedSubmissions().isEmpty())
        assertEquals(MessageState.STATUS_UNAVAILABLE,repository.messages(peer).single().state)
        assertTrue(service().queuedSubmissions().isEmpty())
        // Legacy accepted receipts are intentionally not inferred expired from a local clock.
        val legacy=RandomIdentifiers.create()
        repository.save(Message(legacy,peer,Direction.OUTGOING,"old",server,MessageState.SERVER_ACCEPTED))
        assertEquals(listOf(legacy),service().queuedSubmissions())
        val deliveredId=SubmissionIds.create(server+10_000)
        val deliveredLocal=RandomIdentifiers.create()
        repository.save(Message(deliveredLocal,peer,Direction.OUTGOING,"known",server,MessageState.SERVER_ACCEPTED,
            transportSubmissionId=deliveredId))
        service().deliveryStatuses(listOf(DeliveryStatus(deliveredId,true)))
        assertEquals(MessageState.DELIVERED,repository.messages(peer).single {it.localId==deliveredLocal}.state)
        val undeliveredId=SubmissionIds.create(server+10_000)
        val undeliveredLocal=RandomIdentifiers.create()
        repository.save(Message(undeliveredLocal,peer,Direction.OUTGOING,"expired mailbox",server,MessageState.SERVER_ACCEPTED,
            transportSubmissionId=undeliveredId))
        service().deliveryStatuses(listOf(DeliveryStatus(undeliveredId,false,expired=true)))
        assertEquals(MessageState.EXPIRED_UNDELIVERED,repository.messages(peer).single {it.localId==undeliveredLocal}.state)
    }

    @Test fun canonicalFormatAndEntropy() {
        val expiry=2_000_000_000_000L
        val ids=(1..100).map { SubmissionIds.create(expiry) }
        assertEquals(100,ids.toSet().size)
        ids.forEach { id ->
            assertEquals(33,id.length)
            assertEquals(SubmissionIds.Parsed.ExpiringV3(expiry),SubmissionIds.parse(id))
            assertNull(SubmissionIds.parse(id+"="))
            assertNull(SubmissionIds.parse(id.lowercase()))
            assertNull(SubmissionIds.parse("s4"+id.drop(2)))
        }
        assertEquals(SubmissionIds.Parsed.LegacyV2,SubmissionIds.parse(RandomIdentifiers.create()))
        assertNull(SubmissionIds.parse("s3"))
    }

    @Test fun expiredIdentifierCannotResurrectMailboxAfterReceiptDeletion() = runBlocking {
        var now=2_000_000_000_000L
        val clock=object:Clock() {
            override fun getZone():ZoneId=ZoneOffset.UTC
            override fun withZone(zone:ZoneId):Clock=this
            override fun instant():Instant=Instant.ofEpochMilli(now)
            override fun millis():Long=now
        }
        val db=MemoryBackendDatabase()
        val server=MailboxService(db,clock,rate=RateLimiter {_,_,_->true})
        class Person(private val name:String) {
            val records=MemoryRecords()
            val engine=SignalProtocolEngine(records)
            val state=EndpointNetworkState(records,"ghostcloak.local")
            lateinit var registration:Registration
            suspend fun register() {
                engine.createIdentity(name)
                registration=state.registration(listOf(engine.publicBundle().publicData()))
                val r=registration
                val c=server.execute(ApiRequest.Issue(r.accountId,r.deviceId,"register",DeviceAuth.digest(NetworkCodec.encode(r)))).challenge!!
                server.execute(ApiRequest.Register(r,c.id,state.sign(c)))
                val login=server.execute(ApiRequest.Issue(r.accountId,r.deviceId,"login")).challenge!!
                state.save(server.execute(ApiRequest.Verify(r.accountId,r.deviceId,login.id,state.sign(login))).session!!.token)
            }
            fun call(request:ApiRequest)=server.execute(request,state.read())
        }
        val a=Person("alice").also {it.register()};val b=Person("bob").also {it.register()}
        fun request(id:String, envelopeId:String=RandomIdentifiers.create())=ApiRequest.Send(id,b.registration.routingId,
            EnvelopeCodec.encode(EncryptedEnvelope(1,envelopeId,a.registration.deviceId,b.registration.deviceId,2,byteArrayOf(1))))
        val expiry=now+60_000
        val id=SubmissionIds.create(expiry)
        val sent=request(id)
        val serverId=a.call(sent).serverMessageId!!
        assertEquals(serverId,a.call(sent).serverMessageId)
        val changed=request(id)
        assertEquals(409,assertThrows(ApiFailure::class.java) {a.call(changed)}.status)
        b.call(ApiRequest.Ack(listOf(serverId)))
        assertTrue(a.call(ApiRequest.Fetch(submissionIds=listOf(id),retention=true)).statuses.single().acknowledged)
        // A different fresh ID for identical ciphertext is a new transport submission;
        // recipient envelope replay, not server ID retention, prevents second app delivery.
        val freshId=SubmissionIds.create(expiry)
        val freshServerId=a.call(ApiRequest.Send(freshId,b.registration.routingId,sent.encryptedEnvelope)).serverMessageId!!
        assertNotEquals(serverId,freshServerId)
        b.call(ApiRequest.Ack(listOf(freshServerId)))
        now=expiry-1
        assertEquals(serverId,a.call(sent).serverMessageId)
        now=expiry
        assertEquals(serverId,a.call(sent).serverMessageId)
        assertEquals(0,db.transaction { db.retireSubmissions(now) })
        now=expiry+1
        assertEquals(2,db.transaction { db.retireSubmissions(now) })
        db.transaction { db.submissions.remove(a.registration.deviceId+"/"+id) }
        val before=db.transaction { db.mailbox.size() to db.submissions.size() }
        assertEquals(410,assertThrows(ApiFailure::class.java) {a.call(sent)}.status)
        assertEquals(before,db.transaction { db.mailbox.size() to db.submissions.size() })
        assertTrue(a.call(ApiRequest.Fetch(submissionIds=listOf(id),retention=true)).statuses.single().unavailable)
        val neverSeen=request(SubmissionIds.create(now-1))
        assertEquals(410,assertThrows(ApiFailure::class.java) {a.call(neverSeen)}.status)
        assertEquals(before,db.transaction { db.mailbox.size() to db.submissions.size() })
        val tooFar=request(SubmissionIds.create(now+SubmissionIds.MAX_LIFETIME_MILLIS+1))
        assertEquals(400,assertThrows(ApiFailure::class.java) {a.call(tooFar)}.status)
        assertEquals(before,db.transaction { db.mailbox.size() to db.submissions.size() })
        // Legacy v2 remains accepted and its row is retained conservatively.
        val legacy=request(RandomIdentifiers.create())
        assertNotNull(a.call(legacy).serverMessageId)
    }

    @Test fun offlineOutboxAssignsOnceAndRestartReusesTransportId() = runBlocking {
        val a=MemoryRecords();val b=MemoryRecords()
        val ae=SignalProtocolEngine(a);val be=SignalProtocolEngine(b)
        ae.createIdentity("alice");be.createIdentity("bob")
        ae.establishSession(be.publicBundle())
        val peer=be.createIdentity("bob").deviceId
        var time:Long?=null
        val submitted=mutableListOf<String>()
        val wire=object:IdempotentMessageTransport {
            override suspend fun submit(submissionId:String,routingDestination:String,envelope:EncryptedEnvelope):String {
                submitted+=submissionId
                throw ApiFailure(503,"network_unavailable")
            }
            override suspend fun send(routingDestination:String,envelope:EncryptedEnvelope)=Unit
            override fun receive()=kotlinx.coroutines.flow.emptyFlow<EncryptedEnvelope>()
        }
        fun outbox()=DurableOutbox(a,SignalProtocolEngine(a),wire,expiringIds=true,trustedTime={time})
        val first=outbox()
        val local=first.enqueue(peer,"hello".toByteArray())
        assertEquals(OutboxState.LOCAL,first.get(local).state)
        assertEquals(503,assertThrows(ApiFailure::class.java) { runBlocking {first.process(local)} }.status)
        assertNull(first.get(local).transportSubmissionId)
        time=2_000_000_000_000L
        assertEquals(503,assertThrows(ApiFailure::class.java) { runBlocking {first.process(local)} }.status)
        val networkId=first.get(local).transportSubmissionId!!
        assertTrue(SubmissionIds.parse(networkId) is SubmissionIds.Parsed.ExpiringV3)
        time=time!!+10_000
        assertEquals(503,assertThrows(ApiFailure::class.java) { runBlocking {outbox().process(local)} }.status)
        assertEquals(listOf(networkId,networkId),submitted)
        time=(SubmissionIds.parse(networkId) as SubmissionIds.Parsed.ExpiringV3).expiresAt+1
        assertEquals(OutboxState.SUBMISSION_EXPIRED,outbox().process(local).state)
        assertEquals(2,submitted.size)
    }
}
