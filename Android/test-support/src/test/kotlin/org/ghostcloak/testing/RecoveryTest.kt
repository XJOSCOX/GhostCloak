package org.ghostcloak.testing

import kotlinx.coroutines.runBlocking
import org.ghostcloak.backend.*
import org.ghostcloak.crypto.*
import org.ghostcloak.messaging.*
import org.ghostcloak.protocol.*
import org.ghostcloak.identity.RandomIdentifiers
import org.junit.Assert.*
import org.junit.Test
import java.security.*
import java.security.spec.ECGenParameterSpec
import java.time.*

internal class RecoveryCredential:DeviceAuthCredential {
    val pair=KeyPairGenerator.getInstance("EC").apply {initialize(ECGenParameterSpec("secp256r1"))}.generateKeyPair()
    override fun publicKey(alias:String,create:Boolean)=pair.public.encoded
    override fun sign(alias:String,statement:ByteArray):ByteArray=Signature.getInstance("SHA256withECDSA").run {
        initSign(pair.private);update(statement);sign()
    }
}
internal object RecoveryProbe {
    suspend fun exercise(db:BackendDatabase) {
        val service=MailboxService(db,rate=RateLimiter {_,_,_->true})
        val records=MemoryRecords();val engine=SignalProtocolEngine(records);val identity=engine.createIdentity("alice")
        val state=EndpointNetworkState(records,"ghostcloak.local",RecoveryCredential())
        val original=state.registration("alice",listOf(engine.publicBundle().publicData()))
        val challenge=service.execute(ApiRequest.Issue(original.accountId,original.deviceId,"register",DeviceAuth.digest(NetworkCodec.encode(original)))).challenge!!
        service.execute(ApiRequest.Register(original,challenge.id,state.sign(challenge)));state.markRegistered()
        val owner=NetworkCodec.encode(db.transaction {db.devices.get(original.deviceId)!!})
        records.transaction {
            records.keys("network/").filter {it.endsWith("/account") || it.endsWith("/routing")}.forEach {records.write(it,RandomIdentifiers.create().toByteArray())}
            records.keys("network/").filter {it.endsWith("/registered") || it.endsWith("/token")}.forEach(records::remove)
            records.write("app/renewal-blocked/ghostcloak.local",byteArrayOf(1))
            records.write("app/contact/synthetic",byteArrayOf(4,5))
        }
        val unchanged=records.keys("").filter {!it.startsWith("network/") && !it.startsWith("app/renewal-blocked/")}.associateWith {records.read(it)!!}
        val login=service.execute(ApiRequest.Issue(state.accountId(),original.deviceId,"login")).challenge!!
        try {service.execute(ApiRequest.Verify(login.accountId,login.deviceId,login.id,state.sign(login)));fail()}catch(e:ApiFailure){assertEquals(401,e.status)}
        val public=state.recoveryPublicKey()
        val c=service.execute(ApiRequest.RecoveryIssue(public,original.deviceId)).challenge!!
        val proof=ApiRequest.RecoveryVerify(public,original.deviceId,c.id,state.signRecovery(c,public))
        val binding=service.execute(proof).recovered!!
        val beforeCommit=records.keys("").associateWith {records.read(it)!!}
        try {state.commitRecovery(RecoveredBinding(binding.accountId,RandomIdentifiers.create(),binding.routingId,binding.session),public);fail()}
        catch(e:ApiFailure){assertEquals("recovery_failed",e.code)}
        val failing=object:EndpointRecords by records {
            override fun write(key:String,value:ByteArray) {
                if(key.endsWith("/token")) throw EndpointStorageFailure()
                records.write(key,value)
            }
        }
        // Reuse the existing key interface; injected storage failure rolls back every binding write.
        val originalCredential=object:DeviceAuthCredential {
            override fun publicKey(alias:String,create:Boolean)=public
            override fun sign(alias:String,statement:ByteArray):ByteArray=error("unused")
        }
        try {EndpointNetworkState(failing,"ghostcloak.local",originalCredential).commitRecovery(binding,public);fail()}
        catch(_:EndpointStorageFailure){}
        assertEquals(beforeCommit.keys,records.keys("").toSet())
        beforeCommit.forEach {(key,value)->assertArrayEquals(value,records.read(key))}
        state.commitRecovery(binding,public)
        assertEquals(original.accountId,state.accountId());assertTrue(state.registered());assertNotNull(state.read())
        assertEquals(original.routingId,binding.routingId)
        assertNull(records.read("app/renewal-blocked/ghostcloak.local"))
        unchanged.forEach {(key,bytes)->assertArrayEquals(bytes,records.read(key))}
        assertArrayEquals(identity.publicKey,engine.createIdentity("alice").publicKey)
        assertEquals(1,db.transaction {db.accounts.size()});assertEquals(1,db.transaction {db.devices.size()})
        assertArrayEquals(owner,NetworkCodec.encode(db.transaction {db.devices.get(original.deviceId)!!}))
        service.execute(ApiRequest.Fetch(),state.read())
        try {service.execute(proof);fail()}catch(e:ApiFailure){assertEquals(401,e.status);assertEquals("recovery_failed",e.code)}
    }
}
class RecoveryTest {
    @Test fun recoveryEndpointsRoundTripOverHttpWithoutReturningUsernameOrKeys()=runBlocking {
        val db=MemoryBackendDatabase()
        LocalServer(MailboxService(db,rate=RateLimiter {_,_,_->true})).start().use {server->
            val records=MemoryRecords();val engine=SignalProtocolEngine(records);engine.createIdentity("alice")
            val state=EndpointNetworkState(records,"ghostcloak.local",RecoveryCredential())
            val r=state.registration("alice",listOf(engine.publicBundle().publicData()))
            val client=org.ghostcloak.transport.HttpGhostClient(server.baseUrl,state,true)
            NetworkAccount(client,state).register(r)
            val c=client.unauthenticated(ApiRequest.RecoveryIssue(r.authPublicKey,r.deviceId)).challenge!!
            val response=client.unauthenticated(ApiRequest.RecoveryVerify(r.authPublicKey,r.deviceId,c.id,state.signRecovery(c,r.authPublicKey)))
            assertNull(response.directory);assertNull(response.challenge);assertTrue(response.deliveries.isEmpty())
            assertFalse(NetworkCodec.encode(response).toString(Charsets.ISO_8859_1).contains("alice"))
            state.commitRecovery(response.recovered!!,r.authPublicKey)
            client.call(ApiRequest.Fetch())
            assertEquals(r.accountId,state.accountId());assertEquals(1,db.transaction {db.accounts.size()})
        }
    }
    @Test fun authoritativeBindingRestoresOnlyNetworkMetadata()=runBlocking {RecoveryProbe.exercise(MemoryBackendDatabase())}
    @Test fun proofIsBoundToOriginalKeyDeviceAudiencePurposeAndOneTimeChallenge()=runBlocking {
        val db=MemoryBackendDatabase()
        var now=System.currentTimeMillis()
        val clock=object:Clock() {override fun getZone()=ZoneOffset.UTC;override fun withZone(zone:ZoneId)=this;override fun instant()=Instant.ofEpochMilli(now)}
        val service=MailboxService(db,clock,rate=RateLimiter {_,_,_->true})
        val records=MemoryRecords();val engine=SignalProtocolEngine(records);engine.createIdentity("alice")
        val credential=RecoveryCredential();val state=EndpointNetworkState(records,"ghostcloak.local",credential)
        val r=state.registration("alice",listOf(engine.publicBundle().publicData()))
        val rc=service.execute(ApiRequest.Issue(r.accountId,r.deviceId,"register",DeviceAuth.digest(NetworkCodec.encode(r)))).challenge!!
        service.execute(ApiRequest.Register(r,rc.id,state.sign(rc)))
        fun reject(key:ByteArray=r.authPublicKey,device:String=r.deviceId,signer:RecoveryCredential=credential,
                   transform:(Challenge)->Challenge={it},expire:Boolean=false) {
            val c=service.execute(ApiRequest.RecoveryIssue(key,device)).challenge!!
            val signature=signer.sign("fixture",DeviceAuth.statement(transform(c)))
            if(expire) now+=60001
            val request=ApiRequest.RecoveryVerify(key,device,c.id,signature)
            repeat(2) {try {service.execute(request);fail()}catch(e:ApiFailure){assertEquals(401,e.status);assertEquals("recovery_failed",e.code)}}
        }
        reject(signer=RecoveryCredential()) // copied public key, wrong/private key absent
        val unknown=RecoveryCredential();reject(key=unknown.pair.public.encoded,signer=unknown) // new credential
        reject(device=RandomIdentifiers.create()) // cannot claim a different device
        reject(transform={Challenge(it.id,it.random,it.expiresAt,"other.invalid",it.accountId,it.deviceId,it.purpose,it.registrationHash)})
        reject(transform={Challenge(it.id,it.random,it.expiresAt,it.audience,it.accountId,it.deviceId,"login",it.registrationHash)})
        reject(expire=true)
        assertEquals(1,db.transaction {db.accounts.size()});assertEquals(1,db.transaction {db.devices.size()})
        assertArrayEquals(r.authPublicKey,db.transaction {db.devices.get(r.deviceId)!!.authPublicKey})
        assertEquals(0,db.transaction {db.sessions.size()})
        // Neither a username nor Signal public material satisfies the recovery schema/credential parser.
        for(bytes in listOf("alice".toByteArray(),r.bundles.single().identity)) {
            try {service.execute(ApiRequest.RecoveryIssue(bytes,r.deviceId));fail()}catch(e:ApiFailure){assertEquals(400,e.status)}
        }
    }
    @Test fun unknownAndKnownChallengeIssuanceAndBadProofHaveUniformWireShape()=runBlocking {
        val db=MemoryBackendDatabase();val service=MailboxService(db,rate=RateLimiter {_,_,_->true})
        val records=MemoryRecords();val engine=SignalProtocolEngine(records);engine.createIdentity("alice")
        val state=EndpointNetworkState(records,"ghostcloak.local",RecoveryCredential())
        val r=state.registration("alice",listOf(engine.publicBundle().publicData()))
        val c=service.execute(ApiRequest.Issue(r.accountId,r.deviceId,"register",DeviceAuth.digest(NetworkCodec.encode(r)))).challenge!!
        service.execute(ApiRequest.Register(r,c.id,state.sign(c)))
        val sizes=mutableListOf<Int>();val failures=mutableListOf<ByteArray>()
        for(public in listOf(r.authPublicKey,RecoveryCredential().pair.public.encoded)) {
            val reply=service.execute(ApiRequest.RecoveryIssue(public,r.deviceId));sizes.add(NetworkCodec.encode(reply).size)
            assertNotEquals(r.accountId,reply.challenge!!.accountId)
            try {service.execute(ApiRequest.RecoveryVerify(public,r.deviceId,reply.challenge!!.id,ByteArray(64)));fail()}
            catch(e:ApiFailure){assertEquals(401,e.status);failures.add(NetworkCodec.encode(ApiResponse(error=e.code)))}
        }
        assertEquals(sizes[0],sizes[1]);assertArrayEquals(failures[0],failures[1])
    }
    @Test fun recoveryHasSeparateConservativeRateLimits() {
        val limiter=DevelopmentRateLimiter()
        repeat(10) {assertTrue(limiter.allow(ServerOperation.RECOVER_ISSUE,"anonymous",1000))}
        assertFalse(limiter.allow(ServerOperation.RECOVER_ISSUE,"anonymous",1000))
        assertTrue(limiter.allow(ServerOperation.FETCH,"anonymous",1000))
    }
}
