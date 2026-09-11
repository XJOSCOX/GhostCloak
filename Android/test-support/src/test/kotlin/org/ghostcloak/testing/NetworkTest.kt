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
import java.time.*
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.Executors

class NetworkTest {
    private class Time : Clock() {
        var time = System.currentTimeMillis()
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = Instant.ofEpochMilli(time)
    }
    private class Person(val name: String) {
        val records = MemoryRecords(); val engine = SignalProtocolEngine(records)
        val state = EndpointNetworkState(records, "ghostcloak.local")
        lateinit var registration: Registration
        suspend fun create() {
            engine.createIdentity(name)
            registration = state.registration(name, listOf(engine.publicBundle().publicData()))
        }
    }
    private class Fixture : AutoCloseable {
        val time = Time(); val database = MemoryBackendDatabase()
        val service = MailboxService(database, time, rate = RateLimiter { _, _, _ -> true })
        val server = LocalServer(service).start()
        suspend fun person(name: String): Person = Person(name).also { p ->
            p.create(); val client = client(p); val account = NetworkAccount(client, p.state)
            account.register(p.registration); account.login(p.registration.accountId, p.registration.deviceId)
        }
        fun client(p: Person) = HttpGhostClient(server.baseUrl, p.state, allowLoopbackForTests = true)
        fun call(p: Person, request: ApiRequest) = service.execute(request, p.state.read())
        fun challenge(p: Person): Challenge = service.execute(ApiRequest.Issue(p.registration.accountId, p.registration.deviceId, "login")).challenge!!
        override fun close() = server.close()
    }
    private fun reject(status: Int? = null, block: () -> Unit) {
        try { block(); fail("Expected rejection") } catch (e: ApiFailure) { if (status != null) assertEquals(status, e.status) }
    }
    private suspend fun rejectAsync(block: suspend () -> Unit) {
        try { block(); fail("Expected rejection") } catch (_: ApiFailure) { }
    }
    @Test fun realHttpOfflineSignalDeliveryAckAndDatabaseCompromise() = runBlocking {
        Fixture().use { f ->
            val a = f.person("alice"); val b = f.person("bob"); val c = f.person("charlie")
            val aliceClient = f.client(a)
            NetworkAccount(aliceClient, a.state).connect("BoB", a.engine)
            val transport = NetworkMailboxTransport(aliceClient, a.state)
            val packet = a.engine.encrypt(b.registration.deviceId, "hello bob".toByteArray())
            val submission = RandomIdentifiers.create()
            val serverId = transport.submit(submission, b.registration.deviceId, packet)
            assertEquals(serverId, transport.submit(submission, b.registration.deviceId, packet))
            assertTrue(NetworkMailboxTransport(f.client(c), c.state).fetch().isEmpty())
            reject(403) { f.call(c, ApiRequest.Ack(listOf(serverId))) }
            val dump = f.database.dump()
            fun contains(haystack: ByteArray, needle: ByteArray): Boolean = needle.isNotEmpty() && haystack.asList().windowed(needle.size).any { it == needle.asList() }
            assertFalse(contains(dump, "hello bob".toByteArray()))
            assertFalse(contains(dump, a.state.read()!!.toByteArray()))
            for (p in listOf(a, b)) for (key in p.records.keys("").filter { it == "local/key" || it.startsWith("session/") || it.startsWith("pre/") || it.startsWith("signed/") || it.startsWith("kyber/") || it.endsWith("auth-private") }) {
                assertFalse("Endpoint secret leaked: $key", contains(dump, p.records.read(key)!!))
            }
            // Bob was offline during submission. Login again and fetch without deleting.
            val bobClient = f.client(b); NetworkAccount(bobClient, b.state).login(b.registration.accountId, b.registration.deviceId)
            val inbox = NetworkMailboxTransport(bobClient, b.state)
            val delivery = inbox.fetch().single()
            assertEquals(serverId, delivery.serverMessageId)
            assertEquals(serverId, inbox.fetch().single().serverMessageId)
            assertEquals("hello bob", b.engine.decrypt(EnvelopeCodec.decode(delivery.encryptedEnvelope)).decodeToString())
            try { b.engine.decrypt(EnvelopeCodec.decode(delivery.encryptedEnvelope)); fail() } catch (e: CryptoFailure) { assertEquals(CryptoError.Replay, e.error) }
            inbox.acknowledgeAccepted(listOf(serverId)); inbox.acknowledgeAccepted(listOf(serverId))
            assertTrue(inbox.fetch().isEmpty())
            assertEquals(serverId, transport.submit(submission, b.registration.deviceId, packet))
            assertTrue(inbox.fetch().isEmpty()) // Retry after ACK cannot recreate delivery.
        }
    }
    @Test fun authenticationReplayBindingsExpiryAndRevocation() = runBlocking {
        Fixture().use { f ->
            val a = f.person("alice"); val b = f.person("bob")
            val c = f.challenge(a); val sig = a.state.sign(c)
            val proof = ApiRequest.Verify(a.registration.accountId, a.registration.deviceId, c.id, sig)
            val session = f.service.execute(proof).session!!
            reject(401) { f.service.execute(proof) }
            f.service.execute(ApiRequest.Revoke(), session.token)
            reject(401) { f.service.execute(ApiRequest.Fetch(), session.token) }
            val expired = f.challenge(a); val expiredSig = a.state.sign(expired)
            f.time.time += 60001
            reject(401) { f.service.execute(ApiRequest.Verify(a.registration.accountId, a.registration.deviceId, expired.id, expiredSig)) }
            val wrong = f.challenge(a)
            val bChallenge = f.challenge(b)
            reject(401) { f.service.execute(ApiRequest.Verify(a.registration.accountId, a.registration.deviceId, wrong.id, b.state.sign(bChallenge))) }
            reject(401) { f.service.execute(ApiRequest.Verify(a.registration.accountId, a.registration.deviceId, wrong.id, a.state.sign(wrong))) }
            val binding = f.challenge(a)
            reject(401) { f.service.execute(ApiRequest.Verify(b.registration.accountId, a.registration.deviceId, binding.id, a.state.sign(binding))) }
            val altered = f.challenge(a)
            val alteredStatement = Challenge(altered.id, altered.random.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }, altered.expiresAt, altered.audience, altered.accountId, altered.deviceId, altered.purpose, altered.registrationHash)
            reject(401) { f.service.execute(ApiRequest.Verify(a.registration.accountId, a.registration.deviceId, altered.id, a.state.sign(alteredStatement))) }
            val audience = f.challenge(a)
            reject { a.state.sign(Challenge(audience.id, audience.random, audience.expiresAt, "evil.example", audience.accountId, audience.deviceId, audience.purpose, audience.registrationHash)) }
            val valid = f.challenge(a)
            val token = f.service.execute(ApiRequest.Verify(a.registration.accountId, a.registration.deviceId, valid.id, a.state.sign(valid))).session!!.token
            f.time.time += 300001
            reject(401) { f.service.execute(ApiRequest.Fetch(), token) }
            reject(401) { f.service.execute(ApiRequest.Fetch(), "not-a-token") }
        }
    }
    @Test fun registrationPossessionUniquenessAndUsernameRules() = runBlocking {
        Fixture().use { f ->
            val a = f.person("alice"); val b = Person("bob").also { it.create() }
            for (bad in listOf("", "..", " a ", "Admin", "Ｇhost", "a/b", "a".repeat(25))) reject { Usernames.normalize(bad) }
            assertEquals("alice", Usernames.normalize("ALICE"))
            val registration = b.registration
            val c = f.service.execute(ApiRequest.Issue(registration.accountId, registration.deviceId, "register", DeviceAuth.digest(NetworkCodec.encode(registration)))).challenge!!
            // An attacker cannot register someone else's credential with their own signature.
            reject { f.service.execute(ApiRequest.Register(registration, c.id, a.state.sign(f.challenge(a)))) }
            NetworkAccount(f.client(b), b.state).register(registration)
            rejectAsync { NetworkAccount(f.client(b), b.state).register(registration) }
            val duplicateName = Person("alice").also { it.create() }
            rejectAsync { NetworkAccount(f.client(duplicateName), duplicateName.state).register(duplicateName.registration) }
            val malformed = Registration(RandomIdentifiers.create(), RandomIdentifiers.create(), RandomIdentifiers.create(), "other", ByteArray(91), registration.bundles)
            val challenge = f.service.execute(ApiRequest.Issue(malformed.accountId, malformed.deviceId, "register", DeviceAuth.digest(NetworkCodec.encode(malformed)))).challenge!!
            reject { f.service.execute(ApiRequest.Register(malformed, challenge.id, ByteArray(72))) }
            reject(404) { f.call(a, ApiRequest.Lookup("nobody")) }
            f.call(a, ApiRequest.Rename("Alice.New"))
            assertEquals(a.registration.deviceId, f.call(a, ApiRequest.Lookup("alice.new")).directory!!.deviceId)
        }
    }
    @Test fun prekeysConsumeAtomicallyAndEnforceOwnershipAndBounds() = runBlocking {
        Fixture().use { f ->
            val a = f.person("alice"); val b = f.person("bob")
            val executor = Executors.newFixedThreadPool(2)
            try {
                val results = (1..2).map { executor.submit<Boolean> { try { f.call(a, ApiRequest.Lookup("bob")); true } catch (_: ApiFailure) { false } } }.map { it.get() }
                assertEquals(1, results.count { it })
            } finally { executor.shutdownNow() }
            val keys = b.engine.preKeys.createPublicationBundle().publicData()
            reject(403) { f.call(a, ApiRequest.Prekeys(b.registration.deviceId, listOf(keys))) }
            f.call(b, ApiRequest.Prekeys(b.registration.deviceId, listOf(keys)))
            reject(409) { f.call(b, ApiRequest.Prekeys(b.registration.deviceId, listOf(keys))) }
            reject { f.call(b, ApiRequest.Prekeys(b.registration.deviceId, List(17) { keys })) }
            val other = b.engine.preKeys.createPublicationBundle().publicData()
            reject { f.call(b, ApiRequest.Prekeys(b.registration.deviceId, listOf(other, other))) }
            val invalid = PublicBundle(other.deviceId, other.registrationId, ByteArray(33), other.preKeyId, other.preKey, other.signedId, other.signedKey, other.signature, other.kyberId, other.kyberKey, other.kyberSignature)
            reject { f.call(b, ApiRequest.Prekeys(b.registration.deviceId, listOf(invalid))) }
            assertEquals(keys.preKeyId, f.call(a, ApiRequest.Lookup("bob")).directory!!.bundle.preKeyId)
        }
    }
    @Test fun mailboxValidationIdempotencyAndExpiry() = runBlocking {
        Fixture().use { f ->
            val a = f.person("alice"); val b = f.person("bob")
            a.engine.establishSession(b.registration.bundles.single().remote())
            val wire = EnvelopeCodec.encode(a.engine.encrypt(b.registration.deviceId, "secret".toByteArray()))
            val id = RandomIdentifiers.create(); val request = ApiRequest.Send(id, b.registration.routingId, wire)
            val serverId = f.call(a, request).serverMessageId
            assertEquals(serverId, f.call(a, request).serverMessageId)
            val different = EnvelopeCodec.encode(a.engine.encrypt(b.registration.deviceId, "different".toByteArray()))
            reject(409) { f.call(a, ApiRequest.Send(id, b.registration.routingId, different)) }
            reject { f.call(a, ApiRequest.Send(RandomIdentifiers.create(), b.registration.routingId, ByteArray(20))) }
            reject { f.call(a, ApiRequest.Send(RandomIdentifiers.create(), b.registration.routingId, ByteArray(EnvelopeCodec.MAX_PACKET + 1))) }
            reject(404) { f.call(a, ApiRequest.Send(RandomIdentifiers.create(), RandomIdentifiers.create(), wire)) }
            reject { f.call(b, ApiRequest.Send(RandomIdentifiers.create(), b.registration.routingId, wire)) }
            reject(401) { f.service.execute(ApiRequest.Fetch()) }
            f.call(b, ApiRequest.Ack(listOf(serverId!!)))
            // A full batch of near-limit envelopes must fit the documented binary response cap.
            repeat(NetworkLimits.BATCH) {
                val large = EncryptedEnvelope(1, RandomIdentifiers.create(), a.registration.deviceId, b.registration.deviceId, 2, ByteArray(65300) { 127 })
                f.call(a, ApiRequest.Send(RandomIdentifiers.create(), b.registration.routingId, EnvelopeCodec.encode(large)))
            }
            assertEquals(NetworkLimits.BATCH, NetworkMailboxTransport(f.client(b), b.state).fetch().size)
            f.time.time += 86400001; f.service.cleanup()
            assertEquals(0, f.database.transaction { f.database.mailbox.size() })
            // Expired unacknowledged ciphertext cannot be inferred as delivered.
            assertEquals(1, f.database.transaction { f.database.submissions.all().count { it.acknowledged } })
            assertEquals(NetworkLimits.BATCH, f.database.transaction { f.database.submissions.all().count { !it.acknowledged } })
        }
    }
    @Test fun durableOutboxRecoversEveryCrashBoundaryWithoutReencrypting() = runBlocking {
        for (point in CrashPoint.entries) Fixture().use { f ->
            val a = f.person("alice"); val b = f.person("bob")
            val client = f.client(a); NetworkAccount(client, a.state).connect("bob", a.engine)
            val transport = NetworkMailboxTransport(client, a.state)
            val outbox = DurableOutbox(a.records, a.engine, transport, crash = { if (it == point) throw SimulatedDeath() })
            var id: String? = null
            try { id = outbox.enqueue(b.registration.deviceId, "hello bob".toByteArray()); outbox.process(id) } catch (_: SimulatedDeath) { }
            id = id ?: a.records.keys("outbox/").single().removePrefix("outbox/")
            val restart = DurableOutbox(a.records, SignalProtocolEngine(a.records), transport)
            val before = restart.get(id)
            val recovered = restart.process(id)
            if (point == CrashPoint.AFTER_ENCRYPTION) {
                assertEquals(OutboxState.FAILED, recovered.state)
                assertTrue(f.call(b, ApiRequest.Fetch()).deliveries.isEmpty())
            } else {
                assertEquals(OutboxState.SERVER_ACCEPTED, recovered.state)
                val delivery = f.call(b, ApiRequest.Fetch()).deliveries.single()
                if (before.ciphertext.isNotEmpty()) assertArrayEquals(before.ciphertext, delivery.encryptedEnvelope)
                assertEquals("hello bob", b.engine.decrypt(EnvelopeCodec.decode(delivery.encryptedEnvelope)).decodeToString())
                restart.process(id)
                assertEquals(1, f.call(b, ApiRequest.Fetch()).deliveries.size)
            }
        }
    }
    private class SimulatedDeath : RuntimeException()
    @Test fun httpRejectsOversizeSchemaVersionContentTypeAndCleartext() = runBlocking {
        Fixture().use { f ->
            val a = f.person("alice")
            fun raw(bytes: ByteArray, type: String = NetworkLimits.CONTENT_TYPE): Int {
                val c = URI(f.server.baseUrl + "/v1/auth/challenge").toURL().openConnection() as HttpURLConnection
                try { c.requestMethod = "POST"; c.doOutput = true; c.setRequestProperty("Content-Type", type); c.setFixedLengthStreamingMode(bytes.size); c.outputStream.use { it.write(bytes) }; return c.responseCode }
                finally { c.disconnect() }
            }
            // JDK HttpServer may close/reset a connection rejected before draining an oversized body.
            val oversized = try { raw(ByteArray(NetworkLimits.BODY + 1)) } catch (_: java.net.SocketException) { -1 }
            assertTrue(oversized == 413 || oversized == -1)
            assertEquals(415, raw(byteArrayOf(1), "text/plain"))
            assertEquals(400, raw(byteArrayOf(1, 2, 3)))
            rejectAsync { f.client(a).unauthenticated(ApiRequest.Issue(a.registration.accountId, a.registration.deviceId, "login", version = 2)) }
            try { HttpGhostClient("http://example.com", a.state, true); fail() } catch (_: IllegalArgumentException) { }
            try { HttpGhostClient(f.server.baseUrl, a.state); fail() } catch (_: IllegalArgumentException) { }
            reject { f.service.execute(ApiRequest.Register(a.registration, RandomIdentifiers.create(), ByteArray(NetworkLimits.BODY))) }
        }
    }
    @Test fun rateLimitAndLogsContainOnlyFixedCategories() {
        val log = mutableListOf<Pair<ServerOperation, ServerResult>>()
        val service = MailboxService(MemoryBackendDatabase(), rate = DevelopmentRateLimiter(1), logger = ServerLogger { op, result -> log.add(op to result) })
        val request = ApiRequest.Issue(RandomIdentifiers.create(), RandomIdentifiers.create(), "login")
        service.execute(request)
        reject(429) { service.execute(request) }
        assertEquals(listOf(ServerOperation.CHALLENGE to ServerResult.OK, ServerOperation.CHALLENGE to ServerResult.REJECTED), log)
    }
    @Test fun alteredRegistrationCannotReplaceCredentialAndReturnedChallengesAreCopies() = runBlocking {
        Fixture().use { f ->
            val owner = f.person("owner")
            val attacker = Person("attacker").also { it.create() }
            val original = attacker.registration
            val c = f.service.execute(ApiRequest.Issue(original.accountId, original.deviceId, "register", DeviceAuth.digest(NetworkCodec.encode(original)))).challenge!!
            val changed = Registration(original.accountId, original.deviceId, original.routingId, "altered", original.authPublicKey, original.bundles)
            reject(401) { f.service.execute(ApiRequest.Register(changed, c.id, attacker.state.sign(c))) }
            val replacement = Registration(owner.registration.accountId, original.deviceId, original.routingId, "attacker", original.authPublicKey, original.bundles)
            val claim = f.service.execute(ApiRequest.Issue(replacement.accountId, replacement.deviceId, "register", DeviceAuth.digest(NetworkCodec.encode(replacement)))).challenge!!
            // Sign with the attacker's credential while binding the victim account in the statement.
            val privateBytes = attacker.records.read(attacker.records.keys("network/").single { it.endsWith("auth-private") })!!
            val privateKey = java.security.KeyFactory.getInstance("EC").generatePrivate(java.security.spec.PKCS8EncodedKeySpec(privateBytes))
            privateBytes.fill(0)
            val signature = java.security.Signature.getInstance("SHA256withECDSA").run { initSign(privateKey); update(DeviceAuth.statement(claim)); sign() }
            reject(409) { f.service.execute(ApiRequest.Register(replacement, claim.id, signature)) }
            val copy = f.challenge(owner)
            copy.random[0] = (copy.random[0].toInt() xor 1).toByte()
            reject(401) { f.service.execute(ApiRequest.Verify(owner.registration.accountId, owner.registration.deviceId, copy.id, owner.state.sign(copy))) }
            assertEquals(owner.registration.deviceId, f.call(owner, ApiRequest.Lookup("owner")).directory!!.deviceId)
        }
    }
}
