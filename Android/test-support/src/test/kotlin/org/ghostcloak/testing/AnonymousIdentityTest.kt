package org.ghostcloak.testing

import kotlinx.coroutines.runBlocking
import org.ghostcloak.backend.*
import org.ghostcloak.crypto.*
import org.ghostcloak.identity.RandomIdentifiers
import org.ghostcloak.messaging.*
import org.ghostcloak.protocol.*
import org.junit.Assert.*
import org.junit.Test

class AnonymousIdentityTest {
    @Test fun sameDisplayNameNeverMergesContactOrBlockIdentity() {
        val repository = LocalRepository(MemoryRecords())
        val first = Contact(RandomIdentifiers.create(), RandomIdentifiers.create(), "Alex", RandomIdentifiers.create(),
            ghostCloakId = "7K4M9Q2FX8DR")
        val second = Contact(RandomIdentifiers.create(), RandomIdentifiers.create(), "Alex", RandomIdentifiers.create(),
            ghostCloakId = "Q6WVT3KC8M5P")
        repository.save(first); repository.save(second)
        repository.block(first.remoteDeviceId, true)
        assertTrue(repository.contact(first.remoteDeviceId).blocked)
        assertFalse(repository.contact(second.remoteDeviceId).blocked)
        assertEquals(2, repository.contacts().size)
    }
    @Test fun idsUseOnlyApprovedCharactersAndStrictHumanNormalization() {
        assertEquals(31, GhostCloakIds.ALPHABET.length)
        val generated = (1..1000).map { GhostCloakIds.generate() }
        assertEquals(1000, generated.toSet().size)
        generated.forEach { assertTrue(GhostCloakIds.valid(it)); assertEquals(12, it.length) }
        assertEquals("7K4M9Q2FX8DR", GhostCloakIds.normalize("7k4m-9q2f-x8dr"))
        assertEquals("7K4M-9Q2F-X8DR", GhostCloakIds.display("7K4M9Q2FX8DR"))
        for (bad in listOf("7K4M9Q2FX8D", "7K4M9Q2FX8DR ", "7K4M-9Q2FX8DR",
            "7K4M9Q2FX8D0", "7K4M9Q2FX8DO", "7K4M9Q2FX8DI", "7K4M9Q2FX8DL",
            "７K4M9Q2FX8DR", "7K4M 9Q2F X8DR")) {
            try { GhostCloakIds.normalize(bad); fail("Accepted malformed ID") }
            catch (_: ApiFailure) {}
        }
    }

    @Test fun duplicateDisplayNamesAndForcedIdCollisionNeverReachServerAsNames() = runBlocking {
        val candidates = ArrayDeque(listOf("7K4M9Q2FX8DR", "7K4M9Q2FX8DR", "Q6WVT3KC8M5P", "D9RX5H2NK7VW"))
        val db = MemoryBackendDatabase()
        val events = mutableListOf<Pair<ServerOperation, ServerResult>>()
        val service = MailboxService(db, rate = RateLimiter { _, _, _ -> true },
            logger = ServerLogger { op, result -> events.add(op to result) },
            newGhostCloakId = { candidates.removeFirst() })
        suspend fun register(name: String): Pair<EndpointNetworkState, Registration> {
            val records = MemoryRecords()
            val engine = SignalProtocolEngine(records)
            engine.createIdentity(name)
            val state = EndpointNetworkState(records, "ghostcloak.local")
            val registration = state.registration(listOf(engine.publicBundle().publicData()))
            val serialized = NetworkCodec.encode(registration)
            assertFalse(serialized.toString(Charsets.ISO_8859_1).contains(name))
            val c = service.execute(ApiRequest.Issue(registration.accountId, registration.deviceId, "register",
                DeviceAuth.digest(serialized))).challenge!!
            val assigned = service.execute(ApiRequest.Register(registration, c.id, state.sign(c))).registeredId!!
            val login = service.execute(ApiRequest.Issue(registration.accountId, registration.deviceId, "login")).challenge!!
            val grant = service.execute(ApiRequest.Verify(registration.accountId, registration.deviceId, login.id, state.sign(login))).session!!
            assertEquals(assigned, grant.ghostCloakId)
            state.acceptSession(grant)
            assertEquals(assigned, state.ghostCloakId())
            return state to registration
        }
        val a = register("Alex")
        val b = register("Alex")
        val c = register("Esaie")
        assertEquals(listOf("7K4M9Q2FX8DR", "Q6WVT3KC8M5P", "D9RX5H2NK7VW"),
            listOf(a, b, c).map { it.first.ghostCloakId() })
        val directory = service.execute(ApiRequest.Lookup(b.first.ghostCloakId()), a.first.read()).directory!!
        assertEquals(b.second.accountId, directory.accountId)
        assertEquals(b.first.ghostCloakId(), directory.ghostCloakId)
        assertEquals(3, db.transaction { db.accounts.size() })
        val dump = db.dump().toString(Charsets.ISO_8859_1)
        assertFalse(dump.contains("Alex"))
        assertFalse(dump.contains("Esaie"))
        assertEquals(0, candidates.size)
        assertTrue(events.all { it.second == ServerResult.OK })
        val publicId = b.first.ghostCloakId()
        try { service.execute(ApiRequest.Issue(publicId, b.second.deviceId, "login")); fail() }
        catch (e: ApiFailure) { assertEquals(400, e.status) }
        try { service.execute(ApiRequest.RecoveryIssue(publicId.toByteArray(), b.second.deviceId)); fail() }
        catch (e: ApiFailure) { assertEquals(400, e.status) }
    }

    @Test fun authenticatedProfileNameRemainsHiddenUntilRequestAcceptance() = runBlocking {
        val ar = MemoryRecords(); val br = MemoryRecords()
        val ae = SignalProtocolEngine(ar)
        val sender = ConversationService(ae, LocalRepository(ar))
        val recipient = ConversationService(SignalProtocolEngine(br), LocalRepository(br))
        val alice = sender.create("Alex")
        val bob = recipient.create("Esaie")
        sender.importCard(recipient.exportCard())
        val packet = ae.encrypt(bob.deviceId, ConversationPayload.encode("Hello", 0, displayName = "Alex"))
        assertFalse(EnvelopeCodec.encode(packet).toString(Charsets.ISO_8859_1).contains("Alex"))
        val id = "7K4M9Q2FX8DR"
        recipient.acceptNetwork(packet, SenderProfile(alice.userId, alice.deviceId, RandomIdentifiers.create(), id))
        assertEquals(GhostCloakIds.display(id), recipient.contacts().single().contact.visibleName)
        assertTrue(recipient.messagesForUi(alice.deviceId).isEmpty())
        recipient.acceptRequest(alice.deviceId)
        assertEquals("Alex", recipient.contacts().single().contact.visibleName)
        assertEquals("Hello", recipient.messagesForUi(alice.deviceId).single().body)
        val second = ae.encrypt(bob.deviceId, ConversationPayload.encode("Again", 0, displayName = "Alex"))
        try { recipient.acceptNetwork(second, SenderProfile(alice.userId, alice.deviceId,
            RandomIdentifiers.create(), "Q6WVT3KC8M5P")); fail("Changed public ID accepted") }
        catch (_: ApiFailure) { }
        assertEquals(1, recipient.messagesForUi(alice.deviceId).size)
    }
}
