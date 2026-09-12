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

class CombinedSyncTest {
    private class Wire : GhostCloakTransport {
        val api = SyntheticNetwork()
        val fetches = mutableListOf<ApiRequest.Fetch>()
        var reject = 0
        var invalidStatuses = false
        var expireReceipts = false
        override suspend fun execute(request: TransportRequest): TransportResponse {
            val r = NetworkCodec.decode<ApiRequest>(request.body)
            if (r is ApiRequest.Fetch) {
                fetches.add(r)
                if (reject > 0) { reject--; return TransportResponse(429, "text/plain", byteArrayOf(), 2000) }
                if (expireReceipts && r.submissionIds.isNotEmpty()) return TransportResponse(404, NetworkLimits.CONTENT_TYPE, NetworkCodec.encode(ApiResponse()))
            }
            val result = api.execute(request)
            if (r is ApiRequest.Fetch && invalidStatuses) {
                val response = NetworkCodec.decode<ApiResponse>(result.body)
                return TransportResponse(200, NetworkLimits.CONTENT_TYPE, NetworkCodec.encode(ApiResponse(deliveries = response.deliveries, statuses = listOf(DeliveryStatus(RandomIdentifiers.create(), true)))))
            }
            return result
        }
    }
    private fun runtime(wire: Wire) = AppRuntime(InstrumentationRegistry.getInstrumentation().targetContext,
        "https://fixture.invalid", RandomIdentifiers.create(), wire)

    @Test fun combinedReplyAndReceiptUsesOneFetchAndPreservesAckSemantics() = runBlocking {
        val w = Wire(); val a = runtime(w); val b = runtime(w)
        try {
            a.use { a.create(it, "alice") }; b.use { b.create(it, "bob") }
            a.use { a.addNetwork("bob", it) }
            val aid = a.use { it.open()!!.deviceId }; val bid = b.use { it.open()!!.deviceId }
            a.use { a.send(it, bid, "First contact") }
            w.fetches.clear(); a.use { a.syncNetwork(it) }
            assertEquals(1, w.fetches.size)
            assertEquals(1, w.fetches.single().submissionIds.size)
            assertTrue(w.fetches.single().includeSenders)
            assertEquals(MessageState.SERVER_ACCEPTED, a.use { it.messages(bid).single().state })
            b.use { b.syncNetwork(it); it.acceptRequest(aid); b.send(it, aid, "Reply") }
            w.fetches.clear(); a.use { a.syncNetwork(it) }
            assertEquals(1, w.fetches.size)
            assertEquals(2, a.use { it.messages(bid).size })
            assertEquals(MessageState.DELIVERED, a.use { it.messages(bid).first().state })
            b.use { b.syncNetwork(it) }
            assertEquals(MessageState.DELIVERED, b.use { it.messages(aid).last().state })
            assertTrue(w.api.mailbox.isEmpty())
        } finally { a.close(); b.close() }
    }

    @Test fun paginationQueriesReceiptsOnlyOnFirstPageAndRotatesWithoutStarvation() = runBlocking {
        val w = Wire(); val a = runtime(w); val b = runtime(w)
        try {
            a.use { a.create(it, "alice") }; b.use { b.create(it, "bob") }; a.use { a.addNetwork("bob", it) }
            val aid = a.use { it.open()!!.deviceId }; val bid = b.use { it.open()!!.deviceId }
            a.use { a.send(it, bid, "Introduction") }; b.use { b.syncNetwork(it); it.acceptRequest(aid) }; a.use { a.syncNetwork(it) }
            repeat(NetworkLimits.BATCH + 1) { n ->
                a.use { a.send(it, bid, "Outgoing $n") }; b.use { b.send(it, aid, "Incoming $n") }
            }
            val queued = a.use { it.queuedSubmissions().toSet() }
            w.fetches.clear(); a.use { a.syncNetwork(it) }
            assertEquals(2, w.fetches.size)
            assertEquals(NetworkLimits.BATCH, w.fetches.first().submissionIds.size)
            assertTrue(w.fetches.last().submissionIds.isEmpty())
            assertEquals(NetworkLimits.BATCH, w.fetches.last().skipMessageIds.size)
            assertEquals(NetworkLimits.BATCH * 2 + 3, a.use { it.messages(bid).size })
            a.use { a.syncNetwork(it) }
            assertEquals(queued, w.fetches.flatMap { it.submissionIds }.toSet())
            assertTrue(w.fetches.all { it.submissionIds.size <= NetworkLimits.BATCH })
        } finally { a.close(); b.close() }
    }

    @Test fun invalidReceiptDoesNotUndoInboxCommitAndExpiredReceiptStillAllowsInbox() = runBlocking {
        val w = Wire(); val a = runtime(w); val b = runtime(w)
        try {
            a.use { a.create(it, "alice") }; b.use { b.create(it, "bob") }; a.use { a.addNetwork("bob", it) }
            val aid = a.use { it.open()!!.deviceId }; val bid = b.use { it.open()!!.deviceId }
            a.use { a.send(it, bid, "Introduction") }; b.use { b.syncNetwork(it); it.acceptRequest(aid); b.send(it, aid, "Reply") }
            w.invalidStatuses = true
            try { a.use { a.syncNetwork(it) }; fail("Invalid status accepted") } catch (e: ApiFailure) { assertEquals(502, e.status) }
            assertEquals(2, a.use { it.messages(bid).size })
            assertEquals(MessageState.SERVER_ACCEPTED, a.use { it.messages(bid).first().state })
            w.invalidStatuses = false; w.expireReceipts = true
            b.use { b.send(it, aid, "After expiry") }
            w.fetches.clear(); a.use { a.syncNetwork(it) }
            assertEquals(2, w.fetches.size)
            assertTrue(w.fetches.last().submissionIds.isEmpty())
            assertEquals(3, a.use { it.messages(bid).size })
            assertEquals(NetworkStatus.CONNECTED, a.networkStatus)
        } finally { a.close(); b.close() }
    }

    @Test fun rateLimitRetainsSessionAndBlocksManualFetchUntilRetryAfterThenRecovers() = runBlocking {
        val w = Wire(); val a = runtime(w)
        try {
            a.use { a.create(it, "alice") }
            val identity = a.use { it.open()!! }; val logins = w.api.logins
            w.reject = 1
            repeat(3) {
                try { a.use { a.syncNetwork(it) }; fail("Expected cooldown") } catch (e: ApiFailure) { assertEquals(429, e.status) }
            }
            assertEquals(1, w.fetches.size)
            assertEquals(NetworkStatus.RATE_LIMITED, a.networkStatus)
            assertFalse(a.networkRequiresConnect)
            assertTrue(a.canAutoSync)
            assertEquals(logins, w.api.logins)
            assertArrayEquals(identity.publicKey, a.use { it.open()!!.publicKey })
            delay(2100)
            a.use { a.syncNetwork(it) }
            assertEquals(2, w.fetches.size)
            assertEquals(NetworkStatus.CONNECTED, a.networkStatus)
            assertEquals(logins, w.api.logins)
        } finally { a.close() }
    }
}
