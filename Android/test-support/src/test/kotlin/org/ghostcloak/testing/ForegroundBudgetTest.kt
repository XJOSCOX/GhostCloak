package org.ghostcloak.testing

import org.ghostcloak.backend.DevelopmentRateLimiter
import org.ghostcloak.backend.ServerOperation
import org.ghostcloak.transport.*
import org.junit.Assert.*
import org.junit.Test

class ForegroundBudgetTest {
    @Test fun realHttp429ReadsRetryAfterWithoutDecodingErrorBodyOrRenewingSession() = kotlinx.coroutines.runBlocking {
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        var requests = 0; var renewals = 0
        val bytes = mutableListOf<ByteArray>()
        server.createContext("/") { exchange ->
            requests++; bytes.add(exchange.requestBody.readBytes())
            exchange.responseHeaders.add("Retry-After", "90")
            exchange.responseHeaders.add("Content-Type", "text/plain")
            exchange.sendResponseHeaders(429, 0); exchange.responseBody.close()
        }
        server.start()
        try {
            val store = object : AccessTokenStore {
                override fun read() = "unchanged"
                override fun save(token: String?) { fail("Session modified") }
            }
            val client = HttpGhostClient("http://127.0.0.1:${server.address.port}", store, allowLoopbackForTests = true,
                renewSession = { renewals++ })
            val request = org.ghostcloak.protocol.ApiRequest.Fetch(includeSenders = true)
            repeat(3) {
                try { client.call(request); fail("Expected rate limit") }
                catch (e: org.ghostcloak.protocol.ApiFailure) { assertEquals(429, e.status) }
            }
            assertEquals(1, requests); assertEquals(0, renewals)
            assertTrue(client.fetchRetryDelayMillis > 80000)
            assertArrayEquals(org.ghostcloak.protocol.NetworkCodec.encode<org.ghostcloak.protocol.ApiRequest>(request), bytes.single())
            assertEquals("unchanged", store.read())
        } finally { server.stop(0) }
    }

    @Test fun activeAndIdlePoliciesStayComfortablyBelowTheUnchangedServerLimit() {
        for (active in listOf(true, false)) {
            val policy = ForegroundPolling(); val rate = DevelopmentRateLimiter()
            var now = 0L; val windows = mutableMapOf<Long, Int>()
            while (now < 600000) {
                assertTrue(rate.allow(ServerOperation.FETCH, "fixture", now))
                windows[now / 60000] = (windows[now / 60000] ?: 0) + 1
                now += policy.completed(active)
            }
            assertTrue(windows.values.all { it <= 30 })
            assertEquals(if (active) 2000L else 5000L, policy.completed(active))
            policy.reset(); assertEquals(2000L, policy.completed(false))
        }
    }
    @Test fun retryAfterAndBoundedFallbackUseMonotonicCooldown() {
        var now = 100L; val cooldown = FetchCooldown { now }
        for (expected in listOf(15000L, 30000L, 60000L, 60000L)) {
            cooldown.rejected(null); assertEquals(expected, cooldown.remainingMillis)
            now += expected; assertEquals(0L, cooldown.remainingMillis)
        }
        cooldown.succeeded(); cooldown.rejected(null); assertEquals(15000L, cooldown.remainingMillis)
        now += 15000; cooldown.rejected(90000); assertEquals(90000L, cooldown.remainingMillis)
        assertEquals(42000L, parseRetryAfter("42"))
        assertEquals(60000L, parseRetryAfter("Thu, 1 Jan 1970 00:01:00 GMT", 0))
        assertNull(parseRetryAfter("invalid")); assertNull(parseRetryAfter("-1"))
    }
}
