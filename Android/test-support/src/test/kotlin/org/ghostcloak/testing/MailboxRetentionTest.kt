package org.ghostcloak.testing

import kotlinx.coroutines.runBlocking
import org.ghostcloak.backend.*
import org.ghostcloak.crypto.*
import org.ghostcloak.identity.RandomIdentifiers
import org.ghostcloak.messaging.*
import org.ghostcloak.protocol.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*

object RetentionProbe {
    fun exercise(db:BackendDatabase)=runBlocking {
        var time=System.currentTimeMillis()
        val clock=object:Clock() {
            override fun getZone()=ZoneOffset.UTC
            override fun withZone(zone:ZoneId)=this
            override fun instant()=Instant.ofEpochMilli(time)
        }
        var server=MailboxService(db,clock,rate=RateLimiter {_,_,_->true})
        class Person {
            val records=MemoryRecords();val engine=SignalProtocolEngine(records)
            val state=EndpointNetworkState(records,"ghostcloak.local")
            lateinit var registration:Registration
            fun login() {
                val r=registration
                val c=server.execute(ApiRequest.Issue(r.accountId,r.deviceId,"login")).challenge!!
                state.save(server.execute(ApiRequest.Verify(r.accountId,r.deviceId,c.id,state.sign(c))).session!!.token)
            }
            fun call(request:ApiRequest)=server.execute(request,state.read())
        }
        suspend fun person(name:String)=Person().also {p->
            p.engine.createIdentity(name)
            p.registration=p.state.registration(name,listOf(p.engine.publicBundle().publicData()))
            val r=p.registration
            val c=server.execute(ApiRequest.Issue(r.accountId,r.deviceId,"register",DeviceAuth.digest(NetworkCodec.encode(r)))).challenge!!
            server.execute(ApiRequest.Register(r,c.id,p.state.sign(c)));p.login()
        }
        val a=person("alice");val b=person("bob")
        fun request()=ApiRequest.Send(RandomIdentifiers.create(),b.registration.routingId,
            EnvelopeCodec.encode(EncryptedEnvelope(1,RandomIdentifiers.create(),a.registration.deviceId,b.registration.deviceId,2,byteArrayOf(1,2,3))))
        val first=request();val second=request()
        val firstId=a.call(first).serverMessageId!!
        val secondId=a.call(second).serverMessageId!!
        val start=time
        time=start+6*86400000L;a.login();b.login()
        assertEquals(2,b.call(ApiRequest.Fetch(retention=true)).deliveries.size)
        b.call(ApiRequest.Ack(listOf(firstId)))
        time=start+604800000;a.login();b.login()
        // Cleanup and ACK are serialized; a boundary ACK cannot turn expired payload into Delivered.
        b.call(ApiRequest.Ack(listOf(secondId)))
        repeat(2) {server.cleanup()}
        server=MailboxService(db,clock,rate=RateLimiter {_,_,_->true})
        assertTrue(b.call(ApiRequest.Fetch()).deliveries.isEmpty())
        val result=a.call(ApiRequest.Fetch(submissionIds=listOf(first.submissionId,second.submissionId),retention=true))
        assertTrue(result.statuses[0].acknowledged);assertFalse(result.statuses[0].expired)
        assertFalse(result.statuses[1].acknowledged);assertTrue(result.statuses[1].expired)
        assertEquals(secondId,a.call(second).serverMessageId)
        assertTrue(b.call(ApiRequest.Fetch()).deliveries.isEmpty())
        val legacy=a.call(ApiRequest.Fetch(submissionIds=listOf(second.submissionId)))
        assertFalse(legacy.statuses.single().expired);assertNull(legacy.serverTime)
        val fresh=request();a.call(fresh);assertEquals(1,b.call(ApiRequest.Fetch()).deliveries.size)
        db.transaction {repeat(129) {
            val id=RandomIdentifiers.create()
            db.mailbox.put(id,MailboxRow(id,b.registration.routingId,byteArrayOf(1),time-604800000,time-1))
        }}
        server.cleanup();assertEquals(2,db.transaction {db.mailbox.size()})
        server.cleanup();assertEquals(1,db.transaction {db.mailbox.size()})
        val repo=LocalRepository(a.records)
        val peer=b.registration.deviceId
        repo.save(Contact(peer,b.registration.accountId,"bob",peer))
        repo.save(Message(second.submissionId,peer,Direction.OUTGOING,"history survives",1,MessageState.SERVER_ACCEPTED,disappearingSeconds=30))
        val service=ConversationService(a.engine,repo);service.open();service.deliveryStatuses(result.statuses)
        assertEquals(MessageState.EXPIRED_UNDELIVERED,repo.messages(peer).single().state)
        assertEquals("history survives",repo.messages(peer).single().body)
        assertTrue(service.queuedSubmissions().isEmpty())
        service.deliveryStatuses(result.statuses);assertNull(repo.messages(peer).single().activeExpiry)
    }
}
class MailboxRetentionTest {
    @Test fun queuedPayloadExpiryAckRaceRestartAndSenderStatus()=RetentionProbe.exercise(MemoryBackendDatabase())
}
