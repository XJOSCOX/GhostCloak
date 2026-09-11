package org.ghostcloak.app

import kotlinx.coroutines.runBlocking
import org.ghostcloak.protocol.*
import org.ghostcloak.transport.*
import org.junit.Assert.*
import org.junit.Test

class NetworkDiagnosticsTest {
    @Test fun idleSyncMakesExactlyOneFetchWithoutLoginSendAckOrReceiptCalls() = runBlocking {
        val api = SyntheticNetwork()
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val runtime = org.ghostcloak.app.application.AppRuntime(context, "https://fixture.invalid",
            org.ghostcloak.identity.RandomIdentifiers.create(), api)
        val previous = org.ghostcloak.app.application.NetworkDiagnostics.sink
        val lines = mutableListOf<String>()
        try {
            runtime.use { runtime.create(it, "alice") }
            val requests = api.requests
            val logins = api.logins
            org.ghostcloak.app.application.NetworkDiagnostics.sink = { lines.add(it); Unit }
            repeat(3) { runtime.use { runtime.syncNetwork(it) } }
            assertEquals(requests + 3, api.requests)
            assertEquals(logins, api.logins)
            assertEquals(3, lines.count { it.startsWith("FETCH START ") })
            assertEquals(3, lines.count { " START " in it })
        } finally { org.ghostcloak.app.application.NetworkDiagnostics.sink = previous; runtime.close() }
    }

    @Test fun observersCannotChangeRequestBytesSessionOrRetryCount() = runBlocking {
        val lines = mutableListOf<String>()
        val request: ApiRequest = ApiRequest.Lookup("private_test_name")
        val bytes = NetworkCodec.encode(request)
        for (observer in listOf<((NetworkDiagnostic) -> Unit)?>(null, { lines.add(it.line()); Unit }, { throw IllegalStateException("sink failure") })) {
            var token = "original-session"
            var renewals = 0
            val sent = mutableListOf<TransportRequest>()
            val store = object : AccessTokenStore {
                override fun read() = token
                override fun save(value: String?) { token = value!! }
            }
            val transport = GhostCloakTransport {
                sent.add(it)
                val status = if (sent.size == 1) 401 else 200
                it.diagnosticHttpStatus?.invoke(status)
                TransportResponse(status, NetworkLimits.CONTENT_TYPE, NetworkCodec.encode(ApiResponse()))
            }
            val client = HttpGhostClient("https://fixture.invalid", store, transport = transport,
                renewSession = { rejected -> assertEquals("original-session", rejected); renewals++; store.save("renewed-session") },
                diagnostics = observer)
            client.call(request)
            assertEquals(1, renewals)
            assertEquals(2, sent.size)
            sent.forEach { assertArrayEquals(bytes, it.body) }
            assertEquals("Bearer original-session", sent[0].applicationAuthorization)
            assertEquals("Bearer renewed-session", sent[1].applicationAuthorization)
            assertEquals("renewed-session", store.read())
        }
        assertTrue(lines.any { "RENEWAL_SUCCEEDED" in it })
        assertEquals(1, lines.count { " RETRY " in it })
        assertTrue(lines.none { "private_test_name" in it || "session" in it || "fixture.invalid" in it })
    }

    @Test fun diagnosticsRetainHttpStatusAndExceptionClassWithoutMessagesOrBodies() = runBlocking {
        val lines = mutableListOf<String>()
        val store = object : AccessTokenStore {
            override fun read() = "private-token"
            override fun save(token: String?) = Unit
        }
        val observer: (NetworkDiagnostic) -> Unit = { lines.add(it.line()) }
        val broken = HttpGhostClient("https://fixture.invalid", store, transport = GhostCloakTransport {
            it.diagnosticHttpStatus?.invoke(503)
            throw java.net.SocketTimeoutException("private payload and full URL")
        }, diagnostics = observer)
        try { broken.call(ApiRequest.Fetch()); fail("Expected timeout") }
        catch (e: ApiFailure) { assertEquals(503, e.status) }
        assertTrue(lines.any { "http=503" in it && "java.net.SocketTimeoutException" in it })
        assertTrue(lines.none { "private" in it || "fixture.invalid" in it })
        assertEquals("FETCH API_FAILURE elapsed=0ms apiCode=REDACTED exception=java.lang.IllegalStateException",
            NetworkDiagnostic(NetworkOperation.FETCH, NetworkEvent.API_FAILURE, apiCode = "sensitive-value", exceptionClass = IllegalStateException::class.java).line())
        assertEquals(NetworkOperation.RECEIPT_STATUS, networkOperation(ApiRequest.Fetch(submissionIds = listOf("synthetic"))))
    }
}
