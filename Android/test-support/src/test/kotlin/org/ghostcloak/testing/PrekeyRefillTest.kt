package org.ghostcloak.testing

import kotlinx.coroutines.*
import org.ghostcloak.backend.*
import org.ghostcloak.crypto.*
import org.ghostcloak.messaging.*
import org.ghostcloak.protocol.*
import org.ghostcloak.transport.*
import org.junit.Assert.*
import org.junit.Test

internal class PrekeyFixture(val db: BackendDatabase = MemoryBackendDatabase()) {
    val records = MemoryRecords()
    val engine = SignalProtocolEngine(records, preKeyPolicy=PreKeyPolicy(maximumRetained=10000))
    val state = EndpointNetworkState(records,"ghostcloak.local",RecoveryCredential())
    val service = MailboxService(db, rate=RateLimiter { _,_,_->true })
    lateinit var registration: Registration
    var clock = System.currentTimeMillis()
    var publishes = 0
    var losePublish = false
    var failure: ApiFailure? = null
    var eligible = true
    suspend fun start() {
        engine.createIdentity("fixture")
        registration=state.registration("fixture",List(16) {engine.publicBundle().publicData()})
        val challenge=service.execute(ApiRequest.Issue(registration.accountId,registration.deviceId,"register",DeviceAuth.digest(NetworkCodec.encode(registration)))).challenge!!
        service.execute(ApiRequest.Register(registration,challenge.id,state.sign(challenge)))
        val login=service.execute(ApiRequest.Issue(registration.accountId,registration.deviceId,"login")).challenge!!
        state.save(service.execute(ApiRequest.Verify(registration.accountId,registration.deviceId,login.id,state.sign(login))).session!!.token)
        state.markRegistered()
    }
    fun inventory()=service.execute(ApiRequest.Prekeys(registration.deviceId,emptyList(),inspect=true),state.read()).prekeyInventory!!
    fun lookup()=service.execute(ApiRequest.Lookup("fixture"),state.read()).directory!!.bundle
    fun refill()=PrekeyRefill(records,engine,"ghostcloak.local",{registration.deviceId},{r ->
        failure?.let {throw it}
        if(!r.inspect) publishes++
        val result=service.execute(r,state.read())
        if(!r.inspect && losePublish) throw ApiFailure(503,"network_unavailable")
        result
    },{eligible},{clock})
}

internal object PrekeyProbe {
    suspend fun exercise(db: BackendDatabase) {
        val f=PrekeyFixture(db); f.start()
        val identity=f.records.keys("local/").associateWith { f.records.read(it)!! }
        val token=f.state.read()
        assertEquals(16,f.inventory().available)
        val consumed=List(16) {f.lookup().preKeyId}.toSet()
        assertEquals(16,consumed.size); assertEquals(0,f.inventory().available)
        for(name in listOf("fixture","absentfixture")) {
            try {f.service.execute(ApiRequest.Lookup(name),f.state.read());fail()}
            catch(e:ApiFailure) {assertEquals(404,e.status);assertEquals("contact_unavailable",e.code)}
        }
        val refill=f.refill()
        coroutineScope { List(4) {async {refill.maintain()} }.awaitAll() }
        assertEquals(1,f.publishes);assertEquals(16,f.inventory().available)
        val bundle=f.lookup();assertFalse(bundle.preKeyId in consumed)
        try {f.service.execute(ApiRequest.Prekeys(f.registration.deviceId,listOf(bundle)),f.state.read());fail()}
        catch(e:ApiFailure) {assertEquals(409,e.status)}
        assertEquals(15,f.inventory().available)
        assertEquals(token,f.state.read())
        identity.forEach {(k,v)->assertArrayEquals(v,f.records.read(k))}
    }
}

class PrekeyRefillTest {
    @Test fun exhaustionRefillUniqueKeysAtomicDuplicateConflictAndIdentityPreservation()=runBlocking {
        PrekeyProbe.exercise(MemoryBackendDatabase())
    }
    @Test fun lostPublishResponseAndRestartNeverResurrectConsumedKeys()=runBlocking {
        val f=PrekeyFixture();f.start();repeat(16){f.lookup()}
        f.losePublish=true;f.refill().maintain();assertEquals(1,f.publishes)
        val used=List(16){f.lookup().preKeyId}.toSet()
        f.refill().maintain();assertEquals(1,f.publishes) // persisted failure cooldown
        f.clock+=300001;f.losePublish=false;f.refill().maintain()
        assertEquals(2,f.publishes) // acknowledges lost batch, generates fresh replacement
        repeat(16){assertFalse(f.lookup().preKeyId in used)}
    }
    @Test fun thresholdCooldownAndLogoutEligibility()=runBlocking {
        val f=PrekeyFixture();f.start();repeat(12){f.lookup()}
        f.refill().maintain();assertEquals(0,f.publishes) // four is healthy
        f.lookup();f.clock+=60001
        f.failure=ApiFailure(429,"rate_limited",600000);f.refill().maintain()
        f.failure=null;f.clock+=300001;f.refill().maintain();assertEquals(0,f.publishes)
        f.clock+=300000;f.eligible=false;f.refill().maintain();assertEquals(0,f.publishes)
        f.eligible=true;f.refill().maintain();assertEquals(1,f.publishes)
    }
    @Test fun publish401UsesExistingOneRetryBoundaryAndLookupErrorsAreUniform()=runBlocking {
        var token="old";var renewals=0;var attempts=0
        val tokens=object:AccessTokenStore {override fun read()=token;override fun save(value:String?){token=value!!}}
        val client=HttpGhostClient("https://fixture.invalid",tokens,transport=GhostCloakTransport {request ->
            attempts++
            val status=if(request.applicationAuthorization=="Bearer old")401 else 200
            TransportResponse(status,NetworkLimits.CONTENT_TYPE,NetworkCodec.encode(ApiResponse()))
        },renewSession={renewals++;token="new"})
        client.call(ApiRequest.Prekeys("synthetic",emptyList()))
        assertEquals(2,attempts);assertEquals(1,renewals)
        for(status in listOf(404,409)) {
            val lookup=HttpGhostClient("https://fixture.invalid",tokens,transport=GhostCloakTransport {
                TransportResponse(status,NetworkLimits.CONTENT_TYPE,NetworkCodec.encode(ApiResponse(error="opaque")))})
            try {lookup.lookup("fixture");fail()} catch(e:ApiFailure){assertEquals("contact_unavailable",e.code)}
        }
    }
}
