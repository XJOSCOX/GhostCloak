package org.ghostcloak.app

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.ghostcloak.app.application.*
import org.ghostcloak.identity.RandomIdentifiers
import org.ghostcloak.messaging.*
import org.ghostcloak.protocol.ApiFailure
import org.junit.Assert.*
import org.junit.Test

class NetworkOnboardingTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun runtime(api: SyntheticNetwork, name: String = "e0-${RandomIdentifiers.create()}", origin: String = "https://fixture.invalid") =
        AppRuntime(context, origin, name, api)
    private suspend fun <T> AppRuntime.scoped(block: suspend (AppRuntime) -> T): T = try { block(this) } finally { close() }
    private suspend fun failure(block: suspend () -> Unit) {
        try { block(); fail("Expected failure") } catch (_: ApiFailure) { }
    }
    @Test fun failedFirstLaunchAndRetryAcrossReopenKeepIdentityAndAccount() = runBlocking {
        val api = SyntheticNetwork().apply { offline = true }
        val name = "e0-${RandomIdentifiers.create()}"
        var app = runtime(api, name)
        try {
            failure { app.use { app.create(it, "alice") } }
            val original = app.use { it.open()!! }
            assertEquals(NetworkStatus.OFFLINE, app.networkStatus)
            assertEquals(0, api.registrations)
            app.close(); app = runtime(api, name)
            api.offline = false
            app.use { app.create(it, "ignored_retry_name") }
            val retried = app.use { it.open()!! }
            assertEquals(original.deviceId, retried.deviceId)
            assertArrayEquals(original.publicKey, retried.publicKey)
            assertEquals("alice", retried.username)
            assertEquals(1, api.registrations)
            assertEquals(NetworkStatus.CONNECTED, app.networkStatus)
            val account = api.accounts.values.single()
            app.close(); app = runtime(api, name)
            assertEquals(NetworkStatus.NEEDS_CONNECT, app.networkStatus)
            app.use { app.syncNetwork(it) }
            assertEquals(1, api.registrations)
            assertEquals(account.accountId, api.accounts.values.single().accountId)
            assertArrayEquals(original.publicKey, app.use { it.open()!!.publicKey })
        } finally { app.close() }
    }
    @Test fun lostRegistrationResponseDoesNotRegisterReplacementOnRetry() = runBlocking {
        val api = SyntheticNetwork().apply { loseRegistrationResponse = true }
        runtime(api).scoped { app ->
            failure { app.use { app.create(it, "alice") } }
            val identity = app.use { it.open()!! }
            assertEquals(1, api.registrations)
            app.use { app.connectNetwork(it) }
            assertEquals(1, api.registrations)
            assertEquals(identity.deviceId, app.use { it.open()!!.deviceId })
            assertEquals(NetworkStatus.CONNECTED, app.networkStatus)
        }
    }
    @Test fun localBuildAndExistingLocalIdentityUseSameKeysWhenConnecting() = runBlocking {
        val api = SyntheticNetwork()
        val name = "e0-${RandomIdentifiers.create()}"
        val original = runtime(api, name, "").scoped { app ->
            app.use { app.create(it, "LocalAlice") }
            assertEquals(0, api.requests)
            assertEquals(NetworkStatus.DISABLED, app.networkStatus)
            app.use { it.open()!! }
        }
        runtime(api, name).scoped { app ->
            app.use { app.connectNetwork(it) }
            assertEquals("localalice", api.accounts.values.single().username)
            assertEquals(original.deviceId, app.use { it.open()!!.deviceId })
            assertArrayEquals(original.publicKey, app.use { it.open()!!.publicKey })
        }
    }
    @Test fun invalidNetworkUsernameDoesNotCreateIdentity() = runBlocking {
        val api = SyntheticNetwork()
        runtime(api).scoped { app ->
            try { app.use { app.create(it, "a") }; fail() } catch (_: AppFailure) { }
            assertNull(app.use { it.open() })
            assertEquals(0, api.requests)
        }
    }
    @Test fun usernameContactsSendSyncAndRestartPreserveMessagesWithoutLocalFallback() = runBlocking {
        val api = SyntheticNetwork()
        val bobName = "e0-${RandomIdentifiers.create()}"
        runtime(api).scoped { alice ->
            var bob = runtime(api, bobName)
            try {
                alice.use { alice.create(it, "alice") }; bob.use { bob.create(it, "bob") }
                alice.use { alice.addNetwork("bob", it) }; bob.use { bob.addNetwork("alice", it) }
                assertEquals(listOf("bob", "alice"), api.lookedUp)
                val aliceId = alice.use { it.open()!!.deviceId }; val bobId = bob.use { it.open()!!.deviceId }
                val bobIdentity = bob.use { it.open()!! }
                api.rejectSend = true
                val pending = alice.use { alice.send(it, bobId, "synthetic hello bob") }
                assertEquals(MessageState.PENDING, pending.state)
                assertEquals(NetworkStatus.ERROR, alice.networkStatus)
                assertTrue(api.mailbox.isEmpty())
                assertEquals(MessageState.PENDING, alice.use { it.messages(bobId).single().state })
                val firstCiphertext = api.sent.single()
                api.rejectSend = false
                // Expired sessions are renewed by explicit Sync; ciphertext is not re-encrypted.
                api.sessions.clear()
                alice.use { alice.syncNetwork(it) }
                assertArrayEquals(firstCiphertext, api.sent.last())
                assertEquals(MessageState.SERVER_ACCEPTED, alice.use { it.messages(bobId).single().state })
                bob.close(); bob = runtime(api, bobName)
                bob.use { bob.syncNetwork(it) }
                assertEquals("synthetic hello bob", bob.use { it.messages(aliceId).single().body })
                assertEquals(MessageState.RECEIVED, bob.use { it.messages(aliceId).single().state })
                assertTrue(api.mailbox.isEmpty()) // ACK only after accepted encrypted storage write.
                bob.close(); bob = runtime(api, bobName)
                bob.use { bob.send(it, aliceId, "synthetic reply alice") }
                assertEquals(NetworkStatus.CONNECTED, bob.networkStatus)
                bob.use { bob.publishNetwork() } // A successful stored-token send authenticates this process too.
                alice.use { alice.syncNetwork(it) }
                assertEquals("synthetic reply alice", alice.use { it.messages(bobId).last().body })
                bob.close(); bob = runtime(api, bobName)
                assertEquals(1, bob.use { it.contacts().size })
                assertEquals(2, bob.use { it.messages(aliceId).size })
                assertArrayEquals(bobIdentity.publicKey, bob.use { it.open()!!.publicKey })
                assertEquals(2, api.registrations)
                // A network send to a local-card-only route must stay pending, never reach the simulator.
                val charlieApi = SyntheticNetwork()
                runtime(charlieApi, origin = "").scoped { charlie ->
                    charlie.use { charlie.create(it, "charlie") }
                    val card = charlie.use { it.open(); it.exportCard(true) }
                    alice.use { it.importCard(card) }
                    val charlieId = charlie.use { it.open()!!.deviceId }
                    assertEquals(MessageState.PENDING, alice.use { alice.send(it, charlieId, "synthetic missing route") }.state)
                }
            } finally { bob.close() }
        }
    }
    /** Default run tests reopen; explicit prepare/verify invocations also support an actual emulator reboot. */
    @Test fun identityAccountAndHistorySurvivePersistenceBoundary() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val stage = args.getString("phase1eRebootStage") ?: "both"
        val name = args.getString("phase1eEndpoint") ?: "e0-${RandomIdentifiers.create()}"
        require(name.matches(Regex("e0-[a-zA-Z0-9_-]+")))
        val api = SyntheticNetwork()
        if (stage != "verify") {
            val original = runtime(api, name).scoped { alice ->
                runtime(api).scoped { bob ->
                    alice.use { alice.create(it, "alice") }; bob.use { bob.create(it, "bob") }
                    alice.use { alice.addNetwork("bob", it) }; bob.use { bob.addNetwork("alice", it) }
                    val aliceId = alice.use { it.open()!!.deviceId }
                    bob.use { bob.send(it, aliceId, "synthetic reboot message") }
                    alice.use { alice.syncNetwork(it) }
                    alice.use { it.open()!! }
                }
            }
            org.ghostcloak.storage.EncryptedEndpointStore.open(context, name).use { records ->
                records.transaction {
                    records.write("test/e0/public", original.publicKey)
                    records.write("test/e0/device", original.deviceId.toByteArray())
                    records.write("test/e0/account", api.accounts.values.single { it.username == "alice" }.accountId.toByteArray())
                }
            }
        }
        if (stage != "prepare") {
            val expected = org.ghostcloak.storage.EncryptedEndpointStore.open(context, name).use { records ->
                val network = EndpointNetworkState(records, "fixture.invalid", org.ghostcloak.storage.KeystoreDeviceAuth())
                assertTrue(network.registered()); assertNotNull(network.read())
                assertEquals(records.read("test/e0/account")!!.decodeToString(), network.accountId())
                records.read("test/e0/public")!! to records.read("test/e0/device")!!.decodeToString()
            }
            val requestsBeforeOpen = api.requests
            runtime(api, name).scoped { alice ->
                alice.use { service ->
                    val identity = service.open()!!
                    assertArrayEquals(expected.first, identity.publicKey); assertEquals(expected.second, identity.deviceId)
                    val contact = service.contacts().single().contact
                    assertEquals("synthetic reboot message", service.messages(contact.remoteDeviceId).single().body)
                }
                assertEquals(NetworkStatus.NEEDS_CONNECT, alice.networkStatus)
                assertEquals(requestsBeforeOpen, api.requests) // Opening persistent state does not create an account or log in.
            }
        }
    }}
