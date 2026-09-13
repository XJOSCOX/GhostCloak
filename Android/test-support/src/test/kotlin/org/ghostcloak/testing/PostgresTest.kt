package org.ghostcloak.testing

import kotlinx.coroutines.runBlocking
import org.ghostcloak.backend.*
import org.ghostcloak.crypto.*
import org.ghostcloak.identity.RandomIdentifiers
import org.ghostcloak.messaging.*
import org.ghostcloak.protocol.*
import org.ghostcloak.transport.*
import org.postgresql.ds.PGSimpleDataSource
import org.junit.Assert.*
import org.junit.Test
import java.sql.DriverManager
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.time.*

class PostgresTest {
    @Test fun exhaustedPrekeyPoolRefillsWithoutChangingIdentity()=runBlocking {
        Fixture().use { f -> PrekeyProbe.exercise(PostgresDatabase(f.source)) }
    }
    @Test fun recoveryMigrationAndOriginalBindingProofAreDurable()=runBlocking {
        Fixture().use { f ->
            f.source.connection.use {c->c.createStatement().use {
                it.execute("ALTER TABLE auth_challenges DROP CONSTRAINT auth_challenges_purpose_check")
                it.execute("ALTER TABLE auth_challenges ADD CONSTRAINT auth_challenges_purpose_check CHECK(purpose IN ('register','login'))")
                it.execute("DELETE FROM schema_history WHERE version=4")
            }}
            try {PostgresDatabase(f.source);fail()}catch(_:IllegalStateException){}
            val upgraded=PostgresDatabase(f.source,true);PostgresDatabase(f.source,true)
            RecoveryProbe.exercise(upgraded)
            val now=System.currentTimeMillis()
            repeat(10) {assertTrue(PostgresRateLimiter(upgraded).allow(ServerOperation.RECOVER_VERIFY,"anonymous",now))}
            assertFalse(PostgresRateLimiter(PostgresDatabase(f.source)).allow(ServerOperation.RECOVER_VERIFY,"anonymous",now))
        }
    }
    @Test fun blobMigrationOwnershipCapabilityQuotaAndRetention()=runBlocking {
        Fixture().use { f ->
            val service=f.service(); val a=register(service,"alice"); val b=register(service,"bob")
            f.source.connection.use { c -> c.createStatement().use {
                it.execute("DROP TABLE attachment_budgets"); it.execute("DROP TABLE attachment_blobs")
                it.execute("DELETE FROM schema_history WHERE version=3")
            } }
            try {PostgresDatabase(f.source);fail()}catch(_:IllegalStateException){}
            val upgraded=PostgresDatabase(f.source,true)
            assertNotNull(upgraded.transaction { upgraded.accounts.get(a.registration.accountId) })
            PostgresDatabase(f.source,true) // idempotent numbered migration/checksum
            val directory=java.nio.file.Files.createTempDirectory("blob-pg").toFile()
            try {
                BlobService(upgraded,service,directory,minimumFreeBytes=0).use { blobs ->
                    val data=byteArrayOf(1,2,3); val capability=ByteArray(32){7}
                    val id="a".repeat(64)
                    val reservation=BlobReservation(id=id,length=3,digest=DeviceAuth.digest(data),capabilityHash=DeviceAuth.digest(capability))
                    assertFalse(blobs.reserve(a.state.read()!!,reservation).complete)
                    assertThrows(ApiFailure::class.java) {blobs.reserve(b.state.read()!!,reservation)}
                    assertThrows(ApiFailure::class.java) {blobs.upload(a.state.read()!!,id,byteArrayOf(1).inputStream())}
                    assertTrue(blobs.upload(a.state.read()!!,id,data.inputStream()).complete)
                    assertThrows(ApiFailure::class.java) {blobs.download(b.state.read()!!,id,"0".repeat(64))}
                    blobs.download(b.state.read()!!,id,capability.joinToString(""){"%02x".format(it)}).use {assertArrayEquals(data,it.readBytes())}
                    val row=upgraded.transaction {upgraded.blobs.get(id)!!}
                    assertFalse(capability.contentEquals(row.capabilityHash))
                    upgraded.transaction { upgraded.blobs.put(id,BlobRow(row.id,row.owner,row.device,row.length,row.digest,row.capabilityHash,row.created,0,true)) }
                    blobs.cleanup(); assertNull(upgraded.transaction {upgraded.blobs.get(id)})
                    repeat(4) { index -> try {blobs.reserve(a.state.read()!!,BlobReservation(id=(index+1).toString().repeat(64),length=BlobPolicy.MAX_BYTES,digest=ByteArray(32),capabilityHash=ByteArray(32)))}catch(_:ApiFailure){} }
                    assertTrue(upgraded.transaction {upgraded.blobs.all().sumOf {it.length}}<=BlobPolicy.DAILY_UPLOAD)
                }
                val columns=f.source.connection.use {c->c.createStatement().use {s->s.executeQuery("SELECT column_name FROM information_schema.columns WHERE table_schema=current_schema() AND table_name='attachment_blobs'").use {r->buildList {while(r.next())add(r.getString(1))}}}}
                assertFalse(columns.any {it in setOf("key","filename","media_type","duration","contact","message_id")})
            } finally {directory.deleteRecursively()}
        }
    }
    @Test fun additiveReceiptMigrationPreservesExistingAccount() = runBlocking {
        Fixture().use { f ->
            val original = register(f.service(), "alice").registration
            // Recreate the prior schema only inside this disposable test schema.
            f.source.connection.use { connection -> connection.createStatement().use {
                it.execute("ALTER TABLE message_deduplication DROP COLUMN acknowledged")
                it.execute("DELETE FROM schema_history WHERE version=2")
            } }
            try { PostgresDatabase(f.source); fail("Missing migration accepted") } catch (_: IllegalStateException) { }
            val upgraded = PostgresDatabase(f.source, migrate = true)
            assertEquals(original.deviceId, upgraded.transaction { upgraded.accounts.get(original.accountId)!!.deviceId })
            assertTrue(PostgresDatabase(f.source).healthy())
        }
    }
    @Test fun oneWayMessagingAndPrivateReceiptsPersist() = runBlocking {
        Fixture().use { OneWayProbe.exercise(it.db) }
    }
    @Test fun visitorHeadersNeverReachPostgresRows()=runBlocking {
        Fixture().use {f->
            OriginPrivacyProbe.exercise(f.db)
            f.source.connection.use {connection->
                val tables=connection.createStatement().use {s->s.executeQuery("SELECT tablename FROM pg_tables WHERE schemaname='${f.schema}'").use {r->buildList {while(r.next())add(r.getString(1))}}}
                val dump=tables.joinToString("\n") {t->connection.createStatement().use {s->s.executeQuery("SELECT row_to_json(t)::text FROM $t t").use {r->buildList {while(r.next())add(r.getString(1))}.joinToString("\n")}}}
                for(ip in OriginPrivacyProbe.addresses) {
                    assertFalse(dump.contains(ip));assertFalse(dump.contains(ip.toByteArray().joinToString("") {"%02x".format(it)}))
                }
            }
        }
    }
    @Test fun persistentLimitsAndScheduledCleanupSurviveServiceReplacement()=runBlocking {
        Fixture().use {f->
            val now=System.currentTimeMillis()
            assertTrue(PostgresRateLimiter(f.db,1).allow(ServerOperation.LOOKUP,"fixture-device",now))
            assertFalse(PostgresRateLimiter(PostgresDatabase(f.source),1).allow(ServerOperation.LOOKUP,"fixture-device",now))
            assertTrue(PostgresRateLimiter(f.db,1).allow(ServerOperation.LOOKUP,"fixture-device",now+60000))
            val service=f.service();register(service,"alice")
            val future=MailboxService(f.db,Clock.fixed(Instant.ofEpochMilli(now+700000000),ZoneOffset.UTC))
            RetentionWorker(future,f.db).use {worker->
                val deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
                while(f.db.transaction {f.db.sessions.size()}>0 && System.nanoTime()<deadline) Thread.sleep(20)
                assertEquals(0,f.db.transaction {f.db.sessions.size()});assertTrue(worker.healthy)
            }
        }
    }
    @Test fun applicationNetworkHistoryAndReceiptsSurviveRetry()=runBlocking {
        Fixture().use {f->
            val backend=f.service();val a=register(backend,"alice");val b=register(backend,"bob")
            val port=ServerSocket(0).use {it.localPort}
            ProductionHttpServer(backend,{true},false,port).start().use {
                val sender=ConversationService(a.engine,LocalRepository(a.records));sender.open()
                val receiver=ConversationService(b.engine,LocalRepository(b.records));receiver.open()
                fun card(p:Person):String {val r=p.registration;val k=r.bundles.single();return ContactCardCodec.encode(ContactCard(1,r.accountId,r.username,k.deviceId,k.registrationId,k.identity,k.preKeyId,k.preKey,k.signedId,k.signedKey,k.signature,k.kyberId,k.kyberKey,k.kyberSignature))}
                sender.importCard(card(b));receiver.importCard(card(a))
                val client=HttpGhostClient("http://127.0.0.1:$port",a.state,true)
                a.state.remember(client.lookup("bob"))
                val outbox=DurableOutbox(a.records,a.engine,NetworkMailboxTransport(client,a.state))
                // Revoked token queues locally; fresh login and sync deliver the exact saved entry.
                call(backend,a,ApiRequest.Revoke())
                assertEquals(MessageState.PENDING,sender.sendNetwork(b.registration.deviceId,"hello bob",outbox).state)
                login(backend,a);sender.retryNetwork(outbox)
                assertEquals(MessageState.SERVER_ACCEPTED,sender.messages(b.registration.deviceId).single().state)
                val delivery=call(backend,b,ApiRequest.Fetch()).deliveries.single()
                receiver.acceptNetwork(EnvelopeCodec.decode(delivery.encryptedEnvelope))
                val reopened=ConversationService(SignalProtocolEngine(b.records),LocalRepository(b.records));reopened.open()
                reopened.acceptNetwork(EnvelopeCodec.decode(delivery.encryptedEnvelope))
                assertEquals("hello bob",reopened.messages(a.registration.deviceId).single().body)
                call(backend,b,ApiRequest.Ack(listOf(delivery.serverMessageId)))
                assertTrue(call(backend,b,ApiRequest.Fetch()).deliveries.isEmpty())
            }
        }
    }
    private class Death:RuntimeException()
    @Test fun allOutboxCrashBoundariesOverPostgresHttp()=runBlocking {
        for(point in CrashPoint.entries) Fixture().use {f->
            val service=f.service();val a=register(service,"alice");val b=register(service,"bob")
            val port=ServerSocket(0).use {it.localPort}
            ProductionHttpServer(service,{true},false,port).start().use {
                val client=HttpGhostClient("http://127.0.0.1:$port",a.state,true)
                NetworkAccount(client,a.state).connect("bob",a.engine)
                val transport=NetworkMailboxTransport(client,a.state)
                val outbox=DurableOutbox(a.records,a.engine,transport,{if(it==point)throw Death()})
                try {outbox.process(outbox.enqueue(b.registration.deviceId,"hello bob".toByteArray()))} catch(_:Death){}
                val id=a.records.keys("outbox/").single().removePrefix("outbox/")
                val restart=DurableOutbox(a.records,SignalProtocolEngine(a.records),transport)
                val before=restart.get(id);val result=restart.process(id)
                if(point==CrashPoint.AFTER_ENCRYPTION) {
                    assertEquals(OutboxState.FAILED,result.state);assertTrue(call(service,b,ApiRequest.Fetch()).deliveries.isEmpty())
                } else {
                    assertEquals(OutboxState.SERVER_ACCEPTED,result.state)
                    val delivery=call(service,b,ApiRequest.Fetch()).deliveries.single()
                    if(before.ciphertext.isNotEmpty()) assertArrayEquals(before.ciphertext,delivery.encryptedEnvelope)
                    assertEquals("hello bob",b.engine.decrypt(EnvelopeCodec.decode(delivery.encryptedEnvelope)).decodeToString())
                    restart.process(id);assertEquals(1,call(service,b,ApiRequest.Fetch()).deliveries.size)
                }
            }
        }
    }
    private class Fixture:AutoCloseable {
        val url=System.getenv("GHOSTCLOAK_TEST_DATABASE_URL")!!
        val user=System.getenv("GHOSTCLOAK_TEST_DATABASE_USER")!!
        val password=System.getenv("GHOSTCLOAK_TEST_DATABASE_PASSWORD")!!
        val schema="c2_"+RandomIdentifiers.create().replace("-","")
        val source=PGSimpleDataSource().apply {setURL(this@Fixture.url); this.user=this@Fixture.user; this.password=this@Fixture.password; currentSchema=schema}
        val db:PostgresDatabase
        init {
            require(url.endsWith("/ghostcloak_test"))
            DriverManager.getConnection(url,user,password).use {it.createStatement().use {s->s.execute("CREATE SCHEMA $schema")}}
            db=PostgresDatabase(source,true); db.checkServiceRole()
        }
        fun service(database:PostgresDatabase=db)=MailboxService(database,rate=RateLimiter{_,_,_->true})
        override fun close(){DriverManager.getConnection(url,user,password).use {it.createStatement().use {s->s.execute("DROP SCHEMA $schema CASCADE")}}}
    }
    private class Person(val name:String) {
        val records=MemoryRecords(); val engine=SignalProtocolEngine(records); val state=EndpointNetworkState(records,"ghostcloak.local")
        lateinit var registration:Registration
        suspend fun create(){engine.createIdentity(name); registration=state.registration(name,listOf(engine.publicBundle().publicData()))}
    }
    private suspend fun register(service:MailboxService,name:String):Person=Person(name).also {p->
        p.create(); val r=p.registration
        val c=service.execute(ApiRequest.Issue(r.accountId,r.deviceId,"register",DeviceAuth.digest(NetworkCodec.encode(r)))).challenge!!
        service.execute(ApiRequest.Register(r,c.id,p.state.sign(c)))
        login(service,p)
    }
    private fun login(service:MailboxService,p:Person) {
        val c=service.execute(ApiRequest.Issue(p.registration.accountId,p.registration.deviceId,"login")).challenge!!
        p.state.save(service.execute(ApiRequest.Verify(c.accountId,c.deviceId,c.id,p.state.sign(c))).session!!.token)
    }
    private fun call(s:MailboxService,p:Person,r:ApiRequest)=s.execute(r,p.state.read())
    @Test fun postgresHttpDeliveryRestartDedupeAckAndDump()=runBlocking {
        Fixture().use {f->
            var service=f.service()
            val port=ServerSocket(0).use {it.localPort}
            var server=ProductionHttpServer(service,{f.db.healthy()},production=true,port=port).start()
            val ingress=TestTlsIngress(port)
            try {
                suspend fun online(name:String)=Person(name).also {p->p.create();val account=NetworkAccount(HttpGhostClient(ingress.origin,p.state),p.state);account.register(p.registration);account.login(p.registration.accountId,p.registration.deviceId)}
                val a=online("alice"); val b=online("bob"); val c=online("charlie")
                val client=HttpGhostClient(ingress.origin,a.state)
                NetworkAccount(client,a.state).connect("bob",a.engine)
                val transport=NetworkMailboxTransport(client,a.state)
                var crashed=false
                val outbox=DurableOutbox(a.records,a.engine,transport,{if(it==CrashPoint.AFTER_SERVER_ACCEPTANCE){crashed=true; throw IllegalStateException("fixture")}})
                val id=outbox.enqueue(b.registration.deviceId,"hello bob".toByteArray())
                try {outbox.process(id)} catch(_:IllegalStateException) { }
                assertTrue(crashed)
                val before=outbox.get(id).ciphertext
                server.close()
                // Fresh service/database connections, same durable database after response loss.
                service=f.service(PostgresDatabase(f.source))
                server=ProductionHttpServer(service,{f.db.healthy()},production=true,port=port).start()
                val recovered=DurableOutbox(a.records,SignalProtocolEngine(a.records),transport).process(id)
                assertEquals(OutboxState.SERVER_ACCEPTED,recovered.state)
                val messages=call(service,b,ApiRequest.Fetch()).deliveries
                assertEquals(1,messages.size); assertArrayEquals(before,messages.single().encryptedEnvelope)
                assertTrue(call(service,c,ApiRequest.Fetch()).deliveries.isEmpty())
                try {call(service,c,ApiRequest.Ack(listOf(messages.single().serverMessageId))); fail()} catch(e:ApiFailure) {assertEquals(403,e.status)}
                assertEquals("hello bob",b.engine.decrypt(EnvelopeCodec.decode(before)).decodeToString())
                // Dump every application table using PostgreSQL's row serialization.
                f.source.connection.use {connection->
                    val tables=connection.createStatement().use {s->s.executeQuery("SELECT tablename FROM pg_tables WHERE schemaname='${f.schema}'").use {r->buildList {while(r.next()) add(r.getString(1))}}}
                    val dump=tables.joinToString("\n") {t->connection.createStatement().use {s->s.executeQuery("SELECT row_to_json(t)::text FROM $t t").use {r->buildList {while(r.next()) add(r.getString(1))}.joinToString("\n")}}}
                    assertFalse(dump.contains("hello bob")); assertFalse(dump.contains(a.state.read()!!))
                    assertFalse(dump.contains("hello bob".toByteArray().joinToString("") {"%02x".format(it)}))
                    assertFalse(dump.contains(a.state.read()!!.toByteArray().joinToString("") {"%02x".format(it)}))
                    for(key in a.records.keys("").filter {it=="local/key" || it.endsWith("auth-private") || it.startsWith("session/")}) {
                        val hex=a.records.read(key)!!.joinToString(""){"%02x".format(it)}; assertFalse(dump.contains(hex))
                    }
                }
                repeat(2){call(service,b,ApiRequest.Ack(listOf(messages.single().serverMessageId)))}
                assertTrue(call(service,b,ApiRequest.Fetch()).deliveries.isEmpty())
                assertEquals(recovered.serverId,transport.submit(id,b.registration.deviceId,EnvelopeCodec.decode(before)))
                assertTrue(call(service,b,ApiRequest.Fetch()).deliveries.isEmpty())
                // Gateway timeout before forwarding, and after upstream commit with lost response.
                for(fault in listOf("before","after")) {
                    ingress.failNextSubmission=fault
                    val pending=DurableOutbox(a.records,a.engine,transport)
                    val next=pending.enqueue(b.registration.deviceId,"retry fixture".toByteArray())
                    try {pending.process(next);fail("Gateway failure expected")} catch(_:ApiFailure){}
                    val exact=pending.get(next).ciphertext
                    assertEquals(if(fault=="before") 0 else 1,call(service,b,ApiRequest.Fetch()).deliveries.size)
                    DurableOutbox(a.records,SignalProtocolEngine(a.records),transport).process(next)
                    val only=call(service,b,ApiRequest.Fetch()).deliveries.single()
                    assertArrayEquals(exact,only.encryptedEnvelope)
                    assertEquals("retry fixture",b.engine.decrypt(EnvelopeCodec.decode(exact)).decodeToString())
                    call(service,b,ApiRequest.Ack(listOf(only.serverMessageId)))
                }
            } finally {ingress.close();server.close()}
        }
    }
    @Test fun concurrentChallengesPrekeysAndSubmissionsAreSingleUse()=runBlocking {
        Fixture().use {f->
            val s1=f.service(); val s2=f.service(PostgresDatabase(f.source)); val a=register(s1,"alice"); val b=register(s1,"bob")
            val c=s1.execute(ApiRequest.Issue(a.registration.accountId,a.registration.deviceId,"login")).challenge!!
            val proof=ApiRequest.Verify(c.accountId,c.deviceId,c.id,a.state.sign(c))
            val pool=Executors.newFixedThreadPool(2)
            try {
                val results=listOf(s1,s2).map {s->pool.submit<Boolean>{try {a.state.save(s.execute(proof).session!!.token);true} catch(_:ApiFailure){false}}}.map {it.get()}
                assertEquals(1,results.count {it})
                val bundles=listOf(s1,s2).map {s->pool.submit<Boolean>{try {call(s,a,ApiRequest.Lookup("bob"));true} catch(_:ApiFailure){false}}}.map {it.get()}
                assertEquals(1,bundles.count {it})
                a.engine.establishSession(b.registration.bundles.single().remote())
                val wire=EnvelopeCodec.encode(a.engine.encrypt(b.registration.deviceId,"hello bob".toByteArray()))
                val request=ApiRequest.Send(RandomIdentifiers.create(),b.registration.routingId,wire)
                val ids=listOf(s1,s2).map {s->pool.submit<String>{call(s,a,request).serverMessageId!!}}.map {it.get()}
                assertEquals(ids[0],ids[1]); assertEquals(1,call(s1,b,ApiRequest.Fetch()).deliveries.size)
                val changed=ApiRequest.Send(request.submissionId,b.registration.routingId,EnvelopeCodec.encode(a.engine.encrypt(b.registration.deviceId,"different".toByteArray())))
                try {call(s2,a,changed);fail()} catch(e:ApiFailure){assertEquals(409,e.status)}
            } finally {pool.shutdownNow()}
        }
    }
    @Test fun cleanupAndSchemaValidationFailClosed()=runBlocking {
        Fixture().use {f->
            val service=f.service(); val a=register(service,"alice")
            val c=service.execute(ApiRequest.Issue(a.registration.accountId,a.registration.deviceId,"login")).challenge!!
            val later=Clock.fixed(Instant.ofEpochMilli(System.currentTimeMillis()+700000000),ZoneOffset.UTC)
            MailboxService(f.db,later).cleanup()
            assertEquals(0,f.db.transaction {f.db.challenges.size()}); assertEquals(0,f.db.transaction {f.db.sessions.size()})
            assertTrue(PostgresDatabase(f.source).healthy())
            f.source.connection.use {it.createStatement().use {s->s.execute("UPDATE schema_history SET checksum=repeat('0',64)")}}
            try {PostgresDatabase(f.source);fail()} catch(_:IllegalStateException){}
            assertTrue(c.random.isNotEmpty())
        }
    }
    @Test fun productionRejectsMissingUnsafeConfiguration() {
        for(env in listOf(emptyMap(),mapOf("GHOSTCLOAK_MODE" to "production"))) {try {ProductionConfig.environment(env);fail()} catch(_:IllegalStateException){}}
        try {ProductionConfig("production","jdbc:postgresql://0.0.0.0:5432/db","postgres","x".repeat(32),"http://example.com");fail()} catch(_:IllegalArgumentException){}
    }
}
