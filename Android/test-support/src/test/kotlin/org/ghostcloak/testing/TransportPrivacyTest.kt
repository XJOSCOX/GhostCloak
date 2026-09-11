package org.ghostcloak.testing

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.ghostcloak.backend.*
import org.ghostcloak.crypto.*
import org.ghostcloak.identity.RandomIdentifiers
import org.ghostcloak.messaging.*
import org.ghostcloak.protocol.*
import org.ghostcloak.transport.*
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.io.IOException
import java.io.File
import javax.net.ssl.HttpsURLConnection

class TransportPrivacyTest {
    private class Tokens : AccessTokenStore {
        private var token: String? = "synthetic-application-token"
        override fun read() = token
        override fun save(token: String?) { this.token = token }
    }
    private suspend fun rejected(code: String, block: suspend () -> Unit) {
        try { block(); fail("Expected rejection") } catch (e: ApiFailure) { assertEquals(code, e.code) }
    }

    @Test fun directHttpsPreservesWireContractAndRejectsWrongHostname() = runBlocking {
        val seen = mutableListOf<Triple<String, String?, ByteArray>>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { e ->
            assertEquals("POST", e.requestMethod)
            assertEquals(NetworkLimits.CONTENT_TYPE, e.requestHeaders.getFirst("Content-Type"))
            seen.add(Triple(e.requestURI.path, e.requestHeaders.getFirst("Authorization"), e.requestBody.readBytes()))
            val bytes = NetworkCodec.encode(ApiResponse())
            e.responseHeaders.set("Content-Type", NetworkLimits.CONTENT_TYPE)
            e.sendResponseHeaders(200, bytes.size.toLong()); e.responseBody.use { it.write(bytes) }; e.close()
        }
        server.start()
        val platformTrust = HttpsURLConnection.getDefaultSSLSocketFactory()
        try { TestTlsIngress(server.address.port).use { tls ->
            val client = HttpGhostClient(tls.origin, Tokens())
            client.call(ApiRequest.Fetch())
            client.unauthenticated(ApiRequest.Issue("synthetic-account", "synthetic-device", "login"))
            assertEquals("/v1/messages/fetch", seen[0].first)
            assertEquals("Bearer synthetic-application-token", seen[0].second)
            assertArrayEquals(NetworkCodec.encode<ApiRequest>(ApiRequest.Fetch()), seen[0].third)
            assertNull(seen[1].second)
            rejected("network_unavailable") {
                HttpGhostClient(tls.origin.replace("localhost", "127.0.0.1"), Tokens()).call(ApiRequest.Fetch())
            }
            val fixtureTrust = HttpsURLConnection.getDefaultSSLSocketFactory()
            try {
                HttpsURLConnection.setDefaultSSLSocketFactory(platformTrust)
                rejected("network_unavailable") { client.call(ApiRequest.Fetch()) }
            } finally { HttpsURLConnection.setDefaultSSLSocketFactory(fixtureTrust) }
            assertEquals(2, seen.size)
        } } finally { server.stop(0) }
    }

    @Test fun directDoesNotFollowRedirectsOrAcceptOversizedResponsesOrIdentityHeaders() = runBlocking {
        var status = 200; var oversized = false; var calls = 0
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { e ->
            calls++; e.requestBody.close()
            val body = if (oversized) ByteArray(NetworkLimits.RESPONSE + 1) else NetworkCodec.encode(ApiResponse())
            e.responseHeaders.set("Content-Type", NetworkLimits.CONTENT_TYPE)
            e.responseHeaders.set("Location", "/redirected")
            e.responseHeaders.set("Authorization", "Bearer forged")
            e.responseHeaders.set("CF-Connecting-IP", "203.0.113.42")
            e.responseHeaders.set("X-Authenticated-User", "charlie")
            e.sendResponseHeaders(status, body.size.toLong())
            try { e.responseBody.use { it.write(body) } } catch (_: IOException) { } finally { e.close() }
        }
        server.start()
        try {
            val tokens = Tokens()
            val client = HttpGhostClient("http://127.0.0.1:${server.address.port}", tokens, true)
            client.call(ApiRequest.Fetch()); assertEquals("synthetic-application-token", tokens.read())
            status = 302; rejected("invalid_response") { client.call(ApiRequest.Fetch()) }
            assertEquals(2, calls)
            status = 403; rejected("server_rejected") { client.call(ApiRequest.Fetch()) }
            status = 200; oversized = true
            rejected("response_size") { client.call(ApiRequest.Fetch()) }
        } finally { server.stop(0) }
    }

    @Test fun syntheticRelayRunsAccountAndMailboxFlowWithoutChangingCiphertext() = runBlocking {
        val db = MemoryBackendDatabase()
        val service = MailboxService(db, rate = RateLimiter { _, _, _ -> true })
        val submitted = mutableListOf<ByteArray>()
        // In-process synthetic relay only: no sockets, deployment, provider, IP or identity headers.
        val relay = GhostCloakTransport { request ->
            assertEquals("api.synthetic.invalid", request.endpoint.host)
            val decoded = NetworkCodec.decode<ApiRequest>(request.body)
            assertEquals(ApiRoutes.path(decoded), request.endpoint.path)
            if (decoded is ApiRequest.Send) submitted.add(decoded.encryptedEnvelope.copyOf())
            val response = service.execute(decoded, request.applicationAuthorization?.removePrefix("Bearer "))
            TransportResponse(200, NetworkLimits.CONTENT_TYPE, NetworkCodec.encode(response))
        }
        val selected = TransportPolicy.select(TransportMode.PRIVATE_RELAY, true, relay)
        class Person(val name: String) {
            val records = MemoryRecords(); val engine = SignalProtocolEngine(records)
            val state = EndpointNetworkState(records, "ghostcloak.local")
            val client = HttpGhostClient("https://api.synthetic.invalid", state, transport = selected)
            val account = NetworkAccount(client, state)
            lateinit var registration: Registration
            suspend fun register() {
                engine.createIdentity(name)
                registration = state.registration(name, listOf(engine.publicBundle().publicData()))
                account.register(registration); account.login(registration.accountId, registration.deviceId)
            }
        }
        val alice = Person("alice").also { it.register() }
        val bob = Person("bob").also { it.register() }
        val charlie = Person("charlie").also { it.register() }
        alice.account.connect("bob", alice.engine)
        val packet = alice.engine.encrypt(bob.registration.deviceId, "synthetic relay message".toByteArray())
        val original = EnvelopeCodec.encode(packet)
        val sender = NetworkMailboxTransport(alice.client, alice.state)
        val id = sender.submit(packet.envelopeId, bob.registration.deviceId, packet)
        assertEquals(id, sender.submit(packet.envelopeId, bob.registration.deviceId, packet))
        submitted.forEach { assertArrayEquals(original, it) }
        assertEquals(2, submitted.size)
        val outsider = NetworkMailboxTransport(charlie.client, charlie.state)
        assertTrue(outsider.fetch().isEmpty())
        rejected("forbidden") { outsider.acknowledgeAccepted(listOf(id)) }
        val inbox = NetworkMailboxTransport(bob.client, bob.state)
        val delivery = inbox.fetch().single()
        assertArrayEquals(original, delivery.encryptedEnvelope)
        assertEquals("synthetic relay message", bob.engine.decrypt(EnvelopeCodec.decode(delivery.encryptedEnvelope)).decodeToString())
        inbox.acknowledgeAccepted(listOf(id)); assertTrue(inbox.fetch().isEmpty())
    }

    @Test fun relayGateAndOutageFailClosedAndResponseValidationCannotBeBypassed() = runBlocking {
        val unreachable = GhostCloakTransport { throw IOException("synthetic relay outage") }
        assertTrue(TransportPolicy.select() is DirectHttpsTransport)
        rejected("relay_unavailable") { TransportPolicy.select(TransportMode.PRIVATE_RELAY, false, unreachable) }
        rejected("relay_unavailable") { TransportPolicy.select(TransportMode.PRIVATE_RELAY, true) }
        val selected = TransportPolicy.select(TransportMode.PRIVATE_RELAY, true, unreachable)
        assertSame(unreachable, selected)
        rejected("network_unavailable") {
            HttpGhostClient("https://api.synthetic.invalid", Tokens(), transport = selected).call(ApiRequest.Fetch())
        }
        for (result in listOf(
            TransportResponse(200, "text/html", byteArrayOf()),
            TransportResponse(200, NetworkLimits.CONTENT_TYPE, NetworkCodec.encode(ApiResponse(version = 2))),
            TransportResponse(200, NetworkLimits.CONTENT_TYPE, NetworkCodec.encode(ApiResponse(error = "forged"))),
        )) rejected("invalid_response") {
            HttpGhostClient("https://api.synthetic.invalid", Tokens(), transport = GhostCloakTransport { result }).call(ApiRequest.Fetch())
        }
        rejected("unauthorized") {
            HttpGhostClient("https://api.synthetic.invalid", Tokens().apply { save(null) }, transport = unreachable).call(ApiRequest.Fetch())
        }
    }

    @Test fun authenticationAndMessagingHaveNoPeerAddressDependencies() {
        val root = File(System.getProperty("ghostcloak.root"))
        val files = listOf("Android/messaging/src/main", "Android/transport/src/main", "Android/app/src/main/java/org/ghostcloak/app/application")
            .flatMap { File(root, it).walkTopDown().filter { f -> f.isFile && f.extension == "kt" }.toList() }
        val forbidden = Regex("remoteAddress|remoteHost|remotePort|getPeerHost|getInetAddress|CF-Connecting-IP|X-Forwarded-For|X-Authenticated-User")
        assertTrue(files.none { forbidden.containsMatchIn(it.readText()) })
        for (origin in listOf("http://api.synthetic.invalid", "https://user@api.synthetic.invalid", "https://api.synthetic.invalid?other=1")) {
            try { HttpGhostClient(origin, Tokens()); fail("Unsafe origin accepted") } catch (_: IllegalArgumentException) { }
        }
    }
}
