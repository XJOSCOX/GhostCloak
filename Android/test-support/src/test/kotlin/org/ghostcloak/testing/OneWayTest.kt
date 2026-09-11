package org.ghostcloak.testing

import kotlinx.coroutines.runBlocking
import org.ghostcloak.backend.*
import org.ghostcloak.crypto.*
import org.ghostcloak.identity.*
import org.ghostcloak.messaging.*
import org.ghostcloak.protocol.*
import org.ghostcloak.transport.*
import org.junit.Assert.*
import org.junit.Test

/** Shared behavioral scenario runs against memory and the migrated PostgreSQL database. */
object OneWayProbe {
    suspend fun exercise(database: BackendDatabase) {
        val backend = MailboxService(database, rate = RateLimiter { _, _, _ -> true })
        val connection = GhostCloakTransport { request ->
            val response = backend.execute(NetworkCodec.decode<ApiRequest>(request.body), request.applicationAuthorization?.removePrefix("Bearer "))
            TransportResponse(200, NetworkLimits.CONTENT_TYPE, NetworkCodec.encode(response))
        }
        class Person(val name: String) {
            val records = MemoryRecords()
            val engine = SignalProtocolEngine(records)
            val service = ConversationService(engine, LocalRepository(records))
            val state = EndpointNetworkState(records, "ghostcloak.local")
            val client = HttpGhostClient("https://ghostcloak.local", state, transport = connection)
            val account = NetworkAccount(client, state)
            val transport = NetworkMailboxTransport(client, state)
            val outbox = DurableOutbox(records, engine, transport)
            lateinit var registration: Registration
            suspend fun create() {
                service.create(name)
                registration = state.registration(name, listOf(engine.publicBundle().publicData()))
                account.register(registration); account.login(registration.accountId, registration.deviceId)
            }
        }
        val a = Person("alice"); a.create()
        val b = Person("bob"); b.create()
        val c = Person("charlie"); c.create()
        val bob = a.client.lookup("bob")
        val k = bob.bundle
        a.service.importCard(ContactCardCodec.encode(ContactCard(1, bob.accountId, bob.username, bob.deviceId,
            k.registrationId, k.identity, k.preKeyId, k.preKey, k.signedId, k.signedKey, k.signature, k.kyberId, k.kyberKey, k.kyberSignature)))
        a.state.remember(bob)
        assertTrue(b.service.contacts().isEmpty())
        a.account.logout()
        val pending = a.service.sendNetwork(bob.deviceId, "Synthetic one-way A1", a.outbox)
        assertEquals(MessageState.PENDING, pending.state)
        a.account.login(a.registration.accountId, a.registration.deviceId)
        a.service.retryNetwork(a.outbox)
        assertEquals(MessageState.SERVER_ACCEPTED, a.service.messages(bob.deviceId).single().state)
        val query = ApiRequest.Fetch(submissionIds = listOf(pending.localId))
        assertFalse(a.client.call(query).statuses.single().acknowledged)
        assertTrue(c.transport.fetch().isEmpty())
        try { c.client.call(query); fail("Foreign receipt disclosed") } catch (e: ApiFailure) { assertEquals(404, e.status) }
        val delivery = b.client.call(ApiRequest.Fetch(includeSenders = true)).deliveries.single()
        try { c.transport.acknowledgeAccepted(listOf(delivery.serverMessageId)); fail("Foreign ACK") } catch (e: ApiFailure) { assertEquals(403, e.status) }
        val envelope = EnvelopeCodec.decode(delivery.encryptedEnvelope)
        b.service.acceptNetwork(envelope, delivery.sender)
        b.state.remember(delivery.sender!!)
        b.service.acceptNetwork(envelope, delivery.sender) // Lost ACK response/restart is safe.
        val request = b.service.contacts().single()
        assertTrue(request.contact.request)
        assertEquals(IdentityTrustState.UNVERIFIED, request.identity!!.trustState)
        assertFalse(a.client.call(query).statuses.single().acknowledged) // Fetch/decrypt alone is not delivery.
        b.transport.acknowledgeAccepted(listOf(delivery.serverMessageId))
        b.transport.acknowledgeAccepted(listOf(delivery.serverMessageId))
        a.service.deliveryStatuses(a.client.call(query).statuses)
        assertEquals(MessageState.DELIVERED, a.service.messages(bob.deviceId).single().state)
        b.service.acceptRequest(a.registration.deviceId)
        assertFalse(b.service.contacts().single().contact.request)
        assertEquals(IdentityTrustState.UNVERIFIED, b.service.contacts().single().identity!!.trustState)
        b.service.sendNetwork(a.registration.deviceId, "Synthetic reply B1", b.outbox)
        val reply = a.transport.fetch().single()
        a.service.acceptNetwork(EnvelopeCodec.decode(reply.encryptedEnvelope))
        assertEquals("Synthetic reply B1", a.service.messages(bob.deviceId).last().body)
        a.service.verify(bob.deviceId, a.service.fingerprint(bob.deviceId))
        assertEquals(IdentityTrustState.VERIFIED, a.service.contacts().single().identity!!.trustState)
        b.service.block(a.registration.deviceId, true)
        repeat(NetworkLimits.BATCH) { a.service.sendNetwork(bob.deviceId, "Blocked message $it", a.outbox) }
        val blocked = b.client.call(ApiRequest.Fetch()).deliveries
        assertEquals(NetworkLimits.BATCH, blocked.size)
        try { b.service.acceptNetwork(EnvelopeCodec.decode(blocked.first().encryptedEnvelope)); fail() }
        catch (e: AppFailure) { assertEquals(AppError.BLOCKED, e.error) }
        val fresh = b.engine.publicBundle().publicData()
        b.client.publish(bob.deviceId, listOf(fresh))
        c.account.connect("bob", c.engine)
        c.transport.submit(RandomIdentifiers.create(), bob.deviceId, c.engine.encrypt(bob.deviceId, "New request".toByteArray()))
        val later = b.client.call(ApiRequest.Fetch(includeSenders = true, skipMessageIds = blocked.map { it.serverMessageId })).deliveries.single()
        b.service.acceptNetwork(EnvelopeCodec.decode(later.encryptedEnvelope), later.sender)
        assertEquals("charlie", b.service.contacts().single { it.contact.request }.contact.displayName)
        b.service.deleteRequest(c.registration.deviceId)
        assertTrue(b.service.contacts().single { it.contact.request }.contact.blocked)
        assertTrue(b.service.messages(c.registration.deviceId).isEmpty())
    }
}
class OneWayTest {
    @Test fun oneWayUnverifiedMessagesAndPrivateAckReceipts() = runBlocking { OneWayProbe.exercise(MemoryBackendDatabase()) }
    @Test fun failedAcceptanceRollsBackRatchetAndPin() = runBlocking {
        val a = SignalProtocolEngine(MemoryRecords()); val b = SignalProtocolEngine(MemoryRecords())
        val alice = a.createIdentity("alice"); val bob = b.createIdentity("bob")
        a.establishSession(b.publicBundle())
        val packet = a.encrypt(bob.deviceId, "retry safely".toByteArray())
        try { b.decryptAndCommit(packet) { throw EndpointStorageFailure() }; fail() } catch (_: CryptoFailure) { }
        assertNull(b.getRemoteIdentityStatus(alice.deviceId))
        var saved = ""
        b.decryptAndCommit(packet) { saved = it.decodeToString() }
        assertEquals("retry safely", saved)
    }
    @Test fun legacyFetchAndResponseEncodingRemainUnchanged() {
        assertFalse(NetworkCodec.encode<ApiRequest>(ApiRequest.Fetch()).decodeToString().contains("includeSenders"))
        assertFalse(NetworkCodec.encode(ApiResponse()).decodeToString().contains("statuses"))
        assertTrue(NetworkCodec.decode<ApiRequest>(NetworkCodec.encode<ApiRequest>(ApiRequest.Fetch(includeSenders = true))) is ApiRequest.Fetch)
    }
}
