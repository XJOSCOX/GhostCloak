package org.ghostcloak.app

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.ghostcloak.app.application.*
import org.ghostcloak.identity.RandomIdentifiers
import org.ghostcloak.messaging.MessageState
import org.ghostcloak.protocol.*
import org.ghostcloak.transport.*
import org.junit.Assert.*
import org.junit.Test

class SessionRenewalTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun runtime(api: GhostCloakTransport, name: String = RandomIdentifiers.create()) =
        AppRuntime(context, "https://fixture.invalid", name, api)
    private suspend fun unauthorized(block: suspend () -> Unit) {
        try { block(); fail("Expected 401") } catch (e: ApiFailure) { assertEquals(401, e.status) }
    }

    @Test fun expiredSendAndConcurrentSyncRenewOnceWithoutDisconnectOrIdentityChanges() = runBlocking {
        val api = SyntheticNetwork()
        lateinit var a: AppRuntime
        val previousSink = NetworkDiagnostics.sink
        val diagnostics = mutableListOf<String>()
        NetworkDiagnostics.sink = { diagnostics.add(it); Unit }
        val observed = mutableListOf<NetworkStatus>()
        val sends = mutableListOf<ByteArray>()
        val connection = object : GhostCloakTransport {
            override suspend fun execute(request: TransportRequest): TransportResponse {
                observed.add(a.networkStatus)
                if (NetworkCodec.decode<ApiRequest>(request.body) is ApiRequest.Send) sends.add(request.body.copyOf())
                return api.execute(request)
            }
        }
        a = runtime(connection)
        val b = runtime(api)
        try {
            a.use { a.create(it, "alice") }; b.use { b.create(it, "bob") }
            a.use { a.addNetwork("bob", it) }
            val identity = a.use { it.open()!! }
            val accounts = api.accounts.values.map { NetworkCodec.encode(it) }
            val bid = b.use { it.open()!!.deviceId }
            val logins = api.logins
            api.expireSessions(); observed.clear()
            // Same process endpoint owner serializes user work and foreground work.
            val send = async { a.use { a.send(it, bid, "Expiry send") } }
            val sync = async { a.use { a.syncNetwork(it) } }
            assertEquals(MessageState.SERVER_ACCEPTED, send.await().state); sync.await()
            assertEquals(logins + 1, api.logins)
            assertFalse(observed.contains(NetworkStatus.NEEDS_CONNECT))
            assertFalse(observed.contains(NetworkStatus.CONNECTING))
            assertEquals(2, sends.size)
            assertArrayEquals(sends[0], sends[1])
            assertEquals(NetworkStatus.CONNECTED, a.networkStatus)
            assertEquals(identity.deviceId, a.use { it.open()!!.deviceId })
            assertArrayEquals(identity.publicKey, a.use { it.open()!!.publicKey })
            accounts.zip(api.accounts.values).forEach { (before, after) -> assertArrayEquals(before, NetworkCodec.encode(after)) }
            assertEquals(2, api.registrations)
            assertEquals(1, a.use { it.contacts().size })
            assertEquals(1, a.use { it.messages(bid).size })
            assertTrue(diagnostics.any { "RENEWAL_SUCCEEDED" in it })
            assertTrue(diagnostics.none { identity.deviceId in it || "Expiry send" in it || "alice" in it || "bob" in it })
        } finally { NetworkDiagnostics.sink = previousSink; a.close(); b.close() }
    }

    @Test fun repeatedUnauthorizedStopsAfterOneRenewalAndLogoutPersistsAcrossReopen() = runBlocking {
        val api = SyntheticNetwork()
        val name = RandomIdentifiers.create()
        var a = runtime(api, name)
        try {
            a.use { a.create(it, "alice") }
            val logins = api.logins
            api.rejectAuthenticated = true
            unauthorized { a.use { a.syncNetwork(it) } }
            assertEquals(logins + 1, api.logins)
            assertEquals(NetworkStatus.NEEDS_CONNECT, a.networkStatus)
            assertTrue(a.networkRequiresConnect)
            assertFalse(a.canAutoSync)
            unauthorized { a.use { a.syncNetwork(it) } }
            assertEquals(logins + 1, api.logins)
            api.rejectAuthenticated = false
            a.use { a.connectNetwork(it) }
            a.use { a.logoutNetwork() }
            val loggedOut = api.logins
            assertFalse(a.canAutoSync)
            unauthorized { a.use { a.syncNetwork(it) } }
            a.close(); a = runtime(api, name)
            unauthorized { a.use { a.syncNetwork(it) } }
            assertEquals(loggedOut, api.logins)
            a.use { a.connectNetwork(it) }
            assertTrue(a.canAutoSync)
            assertEquals(1, api.registrations)
        } finally { a.close() }
    }

    @Test fun transientOfflinePreservesSessionAndPendingSendAndRecoversAutomatically() = runBlocking {
        val api = SyntheticNetwork()
        val a = runtime(api); val b = runtime(api)
        try {
            a.use { a.create(it, "alice") }; b.use { b.create(it, "bob") }
            a.use { a.addNetwork("bob", it) }
            val bid = b.use { it.open()!!.deviceId }
            val logins = api.logins
            api.offline = true
            assertEquals(MessageState.PENDING, a.use { a.send(it, bid, "Offline send") }.state)
            assertEquals(NetworkStatus.OFFLINE, a.networkStatus)
            assertFalse(a.networkRequiresConnect)
            assertTrue(a.canAutoSync)
            api.offline = false
            a.use { a.syncNetwork(it) }
            assertEquals(logins, api.logins)
            assertEquals(MessageState.SERVER_ACCEPTED, a.use { it.messages(bid).single().state })
            assertEquals(1, a.use { it.contacts().size })
        } finally { a.close(); b.close() }
    }

    @Test fun renewalNetworkFailureRetriesLaterButRejectedCredentialsNeverRegisterAgain() = runBlocking {
        val api = SyntheticNetwork()
        var failLogin = false
        val connection = object : GhostCloakTransport {
            override suspend fun execute(request: TransportRequest): TransportResponse {
                if (failLogin && NetworkCodec.decode<ApiRequest>(request.body) is ApiRequest.Verify) {
                    failLogin = false
                    throw java.io.IOException("Synthetic login outage")
                }
                return api.execute(request)
            }
        }
        val a = runtime(connection)
        try {
            a.use { a.create(it, "alice") }
            val identity = a.use { it.open()!! }
            api.expireSessions(); failLogin = true
            try { a.use { a.syncNetwork(it) }; fail("Expected outage") }
            catch (e: ApiFailure) { assertEquals(503, e.status) }
            assertEquals(NetworkStatus.OFFLINE, a.networkStatus)
            assertFalse(a.networkRequiresConnect)
            assertTrue(a.canAutoSync)
            a.use { a.syncNetwork(it) }
            assertEquals(NetworkStatus.CONNECTED, a.networkStatus)
            api.expireSessions(); api.accounts.clear()
            unauthorized { a.use { a.syncNetwork(it) } }
            assertEquals(NetworkStatus.NEEDS_CONNECT, a.networkStatus)
            assertTrue(a.networkRequiresConnect)
            assertFalse(a.canAutoSync)
            assertEquals(1, api.registrations)
            assertEquals(identity.deviceId, a.use { it.open()!!.deviceId })
            assertArrayEquals(identity.publicKey, a.use { it.open()!!.publicKey })
        } finally { a.close() }
    }
}
