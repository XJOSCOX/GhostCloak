package org.ghostcloak.app

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.emptyFlow
import org.ghostcloak.crypto.SignalProtocolEngine
import org.ghostcloak.identity.RandomIdentifiers
import org.ghostcloak.messaging.*
import org.ghostcloak.protocol.*
import org.ghostcloak.storage.EncryptedEndpointStore
import org.ghostcloak.transport.IdempotentMessageTransport
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class NetworkStorageTest {
    @Test fun authCredentialTokenAndOutboxSurviveEncryptedReopen() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "n-${RandomIdentifiers.create()}"
        var store = EncryptedEndpointStore.open(context, name)
        val fakeTransport = object : IdempotentMessageTransport {
            override suspend fun submit(submissionId: String, routingDestination: String, envelope: EncryptedEnvelope) = RandomIdentifiers.create()
            override suspend fun send(routingDestination: String, envelope: EncryptedEnvelope) { }
            override fun receive() = emptyFlow<EncryptedEnvelope>()
        }
        try {
            val engine = SignalProtocolEngine(store); engine.createIdentity("alice")
            val state = EndpointNetworkState(store, "ghostcloak.local")
            val registration = state.registration("alice", listOf(engine.publicBundle().publicData()))
            val token = "test-token-only-" + RandomIdentifiers.create(); state.save(token)
            val id = DurableOutbox(store, engine, fakeTransport).enqueue(RandomIdentifiers.create(), "outbox secret fixture".toByteArray())
            val challenge = Challenge(RandomIdentifiers.create(), java.security.SecureRandom().generateSeed(32), System.currentTimeMillis() + 60000,
                "ghostcloak.local", registration.accountId, registration.deviceId, "login", byteArrayOf())
            assertTrue(DeviceAuth.verify(registration.authPublicKey, challenge, state.sign(challenge)))
            store.close()
            val disk = File(context.noBackupFilesDir, "$name.db").readBytes().toString(Charsets.ISO_8859_1)
            assertFalse(disk.contains(token)); assertFalse(disk.contains("outbox secret fixture"))
            store = EncryptedEndpointStore.open(context, name)
            val reopened = EndpointNetworkState(store, "ghostcloak.local")
            assertEquals(token, reopened.read())
            assertTrue(DeviceAuth.verify(registration.authPublicKey, challenge, reopened.sign(challenge)))
            assertEquals(OutboxState.LOCAL, DurableOutbox(store, SignalProtocolEngine(store), fakeTransport).get(id).state)
            assertNull(EndpointNetworkState(store, "other.example").read())
        } finally { store.close() }
    }
}
