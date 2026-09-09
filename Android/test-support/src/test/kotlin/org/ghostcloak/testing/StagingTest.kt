package org.ghostcloak.testing

import kotlinx.coroutines.runBlocking
import org.ghostcloak.crypto.*
import org.ghostcloak.messaging.*
import org.ghostcloak.protocol.*
import org.ghostcloak.transport.*
import org.junit.Assert.*
import org.junit.Test
import java.net.URI
import java.util.UUID

/** Explicit opt-in against an owner-operated staging service. Never run on production accounts. */
class StagingTest {
    @Test fun httpsAliceBobCharlie()=runBlocking {
        check(System.getenv("GHOSTCLOAK_STAGING_CONFIRM")=="isolated-staging")
        val origin=System.getenv("GHOSTCLOAK_STAGING_ORIGIN") ?: error("Staging origin required")
        check(URI(origin).scheme=="https")
        class Person {
            val records=MemoryRecords();val engine=SignalProtocolEngine(records)
            val state=EndpointNetworkState(records,URI(origin).host)
            val client=HttpGhostClient(origin,state)
            lateinit var registration:Registration
            suspend fun start() {
                val username="test_"+UUID.randomUUID().toString().replace("-","").take(16)
                engine.createIdentity(username)
                registration=state.registration(username,listOf(engine.publicBundle().publicData()))
                NetworkAccount(client,state).run {register(registration);login(registration.accountId,registration.deviceId)}
            }
        }
        val a=Person();val b=Person();val c=Person()
        try {
            a.start();b.start();c.start()
            NetworkAccount(a.client,a.state).connect(b.registration.username,a.engine)
            val transport=NetworkMailboxTransport(a.client,a.state)
            val outbox=DurableOutbox(a.records,a.engine,transport)
            val id=outbox.enqueue(b.registration.deviceId,"hello bob".toByteArray())
            assertEquals(OutboxState.SERVER_ACCEPTED,outbox.process(id).state)
            val delivery=b.client.call(ApiRequest.Fetch()).deliveries.single()
            assertTrue(c.client.call(ApiRequest.Fetch()).deliveries.isEmpty())
            try {c.client.call(ApiRequest.Ack(listOf(delivery.serverMessageId)));fail()}catch(e:ApiFailure){assertEquals(403,e.status)}
            assertEquals("hello bob",b.engine.decrypt(EnvelopeCodec.decode(delivery.encryptedEnvelope)).decodeToString())
            repeat(2){b.client.call(ApiRequest.Ack(listOf(delivery.serverMessageId)))}
            assertTrue(b.client.call(ApiRequest.Fetch()).deliveries.isEmpty())
        } finally {
            for(p in listOf(a,b,c)) if(p.state.read()!=null) NetworkAccount(p.client,p.state).logout()
        }
    }
}
