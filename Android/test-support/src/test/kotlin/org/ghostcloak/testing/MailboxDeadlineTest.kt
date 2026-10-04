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
import java.util.concurrent.*

object MailboxDeadlineProbe {
    fun exercise(initial:BackendDatabase,reopen:()->BackendDatabase={initial})=runBlocking {
        var time=2000000000000L
        var tick=false
        val clock=object:Clock() {
            override fun getZone()=ZoneOffset.UTC
            override fun withZone(zone:ZoneId)=this
            override fun instant()=Instant.ofEpochMilli(millis())
            override fun millis():Long {val result=time;if(tick)time++;return result}
        }
        var db=initial
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
            fun call(r:ApiRequest)=server.execute(r,state.read())
        }
        suspend fun person(name:String)=Person().also {p->
            p.engine.createIdentity(name)
            p.registration=p.state.registration(listOf(p.engine.publicBundle().publicData()))
            val r=p.registration
            val c=server.execute(ApiRequest.Issue(r.accountId,r.deviceId,"register",DeviceAuth.digest(NetworkCodec.encode(r)))).challenge!!
            server.execute(ApiRequest.Register(r,c.id,p.state.sign(c)));p.login()
        }
        val a=person("alice");val b=person("bob")
        fun request()=ApiRequest.Send(RandomIdentifiers.create(),b.registration.routingId,
            EnvelopeCodec.encode(EncryptedEnvelope(1,RandomIdentifiers.create(),a.registration.deviceId,b.registration.deviceId,2,byteArrayOf(1))))
        fun enqueue(r:ApiRequest.Send):MailboxRow {
            tick=true
            val id=try {a.call(r).serverMessageId!!} finally {tick=false}
            return db.transaction {
                val row=db.mailbox.get(id)!!
                assertEquals(604800000L,row.expiresAt-row.receivedAt)
                assertEquals(row.expiresAt,db.submissions.get(a.registration.deviceId+"/"+r.submissionId)!!.mailboxExpiresAt)
                row
            }
        }
        val first=request();val row=enqueue(first)
        time=row.expiresAt-1;a.login();b.login()
        db=reopen();server=MailboxService(db,clock,rate=RateLimiter {_,_,_->true})
        assertEquals(1,b.call(ApiRequest.Fetch(retention=true)).deliveries.size)
        time=row.expiresAt
        assertTrue(b.call(ApiRequest.Fetch(retention=true)).deliveries.isEmpty())
        assertTrue(a.call(ApiRequest.Fetch(submissionIds=listOf(first.submissionId),retention=true)).statuses.single().expired)
        time++;repeat(2){server.cleanup()}
        assertTrue(b.call(ApiRequest.Fetch()).deliveries.isEmpty())
        assertEquals(row.id,a.call(first).serverMessageId)
        assertTrue(b.call(ApiRequest.Fetch()).deliveries.isEmpty())

        val pool=Executors.newFixedThreadPool(3)
        try {
            for(boundary in listOf(false,true)) {
                val r=request();val queued=enqueue(r)
                time=queued.expiresAt-if(boundary) 0 else 1
                a.login();b.login()
                val other=MailboxService(reopen(),clock,rate=RateLimiter {_,_,_->true})
                val gate=CountDownLatch(1)
                val fetch=pool.submit<ApiResponse> {gate.await();b.call(ApiRequest.Fetch(retention=true))}
                val ack=pool.submit<ApiResponse> {gate.await();other.execute(ApiRequest.Ack(listOf(queued.id)),b.state.read())}
                val cleanup=pool.submit {gate.await();server.cleanup()}
                gate.countDown()
                val fetched=fetch.get(15,TimeUnit.SECONDS);ack.get(15,TimeUnit.SECONDS);cleanup.get(15,TimeUnit.SECONDS)
                assertTrue(fetched.deliveries.size<=1)
                if(boundary) assertTrue(fetched.deliveries.isEmpty())
                val status=a.call(ApiRequest.Fetch(submissionIds=listOf(r.submissionId),retention=true)).statuses.single()
                assertEquals(!boundary,status.acknowledged);assertEquals(boundary,status.expired)
                time=queued.expiresAt+1;repeat(2){server.cleanup()}
                b.call(ApiRequest.Ack(listOf(queued.id)))
                val repeated=a.call(ApiRequest.Fetch(submissionIds=listOf(r.submissionId),retention=true)).statuses.single()
                assertEquals(status.acknowledged,repeated.acknowledged)
                assertEquals(status.expired,repeated.expired)
                a.call(r);assertTrue(b.call(ApiRequest.Fetch()).deliveries.isEmpty())
            }
        } finally {pool.shutdownNow()}

        // Fetch wins while eligible; processing without an ACK before expiry is not delivery.
        val late=request();val lateRow=enqueue(late)
        time=lateRow.expiresAt-1;a.login();b.login()
        assertEquals(1,b.call(ApiRequest.Fetch()).deliveries.size)
        time=lateRow.expiresAt;b.call(ApiRequest.Ack(listOf(lateRow.id)))
        val lateStatus=a.call(ApiRequest.Fetch(submissionIds=listOf(late.submissionId),retention=true)).statuses.single()
        assertFalse(lateStatus.acknowledged);assertTrue(lateStatus.expired)

        val earlier=request();val earlyRow=enqueue(earlier)
        time+=1000;val later=request();val laterRow=enqueue(later)
        time=earlyRow.expiresAt;a.login();b.login();server.cleanup()
        assertEquals(laterRow.id,b.call(ApiRequest.Fetch()).deliveries.single().serverMessageId)
        time=laterRow.expiresAt;repeat(2){server.cleanup()};assertTrue(b.call(ApiRequest.Fetch()).deliveries.isEmpty())

        // Overflow fails before either row is written, inside the transaction.
        time=Long.MAX_VALUE-1000000000L;a.login();b.login()
        val before=db.transaction {db.mailbox.size() to db.submissions.size()}
        assertThrows(ArithmeticException::class.java) {a.call(request())}
        assertEquals(before,db.transaction {db.mailbox.size() to db.submissions.size()})
    }
}
class MailboxDeadlineTest {
    @Test fun atomicDeadlinesExactBoundariesRestartAndConcurrentCleanup()=MailboxDeadlineProbe.exercise(MemoryBackendDatabase())
}
