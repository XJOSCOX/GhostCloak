package org.ghostcloak.app

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.ghostcloak.app.application.AppRuntime
import org.ghostcloak.identity.RandomIdentifiers
import org.ghostcloak.messaging.*
import org.ghostcloak.protocol.DeliveryStatus
import org.ghostcloak.storage.EncryptedEndpointStore
import org.junit.Assert.*
import org.junit.Test

class DeliveryExpiryTest {
    @Test fun offlineRecipientQueuedRestartAckAndDeliveredRestartPreserveCorrectDeadline() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var wall = System.currentTimeMillis(); var elapsed = 100_000L
        fun clock() = ExpiryClock({ wall }, { elapsed }, { 7 })
        val api = SyntheticNetwork(); val name = RandomIdentifiers.create()
        fun sender() = AppRuntime(context, "https://fixture.invalid", name, api, expiryClock = clock())
        var a = sender()
        val b = AppRuntime(context, "https://fixture.invalid", RandomIdentifiers.create(), api, expiryClock = clock())
        try {
            a.use { a.create(it, "alice") }; b.use { b.create(it, "bob") }; a.use { a.addNetwork("bob", it) }
            val aid = a.use { it.open()!!.deviceId }; val bid = b.use { it.open()!!.deviceId }
            a.use { a.send(it, bid, "intro") }; b.backgroundSync()
            b.use { it.acceptRequest(aid); it.clearConversation(aid) }; a.use { a.syncNetwork(it); it.clearConversation(bid) }
            a.use { a.setDisappearing(it, bid, 3600); a.send(it, bid, "wait for delivery") }
            val id = a.use { it.messages(bid).single { m -> !m.policyEvent }.localId }
            wall += 7_200_000; elapsed += 7_200_000
            a.close(); a = sender(); a.reconcileLocalExpiry()
            a.use { val queued = it.messages(bid).single { m -> m.localId == id }
                assertEquals(MessageState.SERVER_ACCEPTED, queued.state); assertNull(queued.expiry)
                a.setDisappearing(it, bid, 30) }
            b.backgroundSync()
            b.use { val incoming = it.messages(aid).single { m -> !m.policyEvent }
                assertEquals(3600, incoming.disappearingSeconds); assertEquals(wall + 3_600_000, incoming.expiry!!.wall) }
            a.use { assertNull(it.messages(bid).single { m -> m.localId == id }.expiry) }
            wall += 5000; elapsed += 5000
            a.use { a.syncNetwork(it) }
            val deadline = a.use { val delivered = it.messages(bid).single { m -> m.localId == id }
                assertEquals(MessageState.DELIVERED, delivered.state); delivered.expiry!! }
            assertEquals(wall + 3_600_000, deadline.wall)
            a.close(); a = sender(); wall += 5000; elapsed += 5000
            a.use { a.syncNetwork(it); it.deliveryStatuses(listOf(DeliveryStatus(id, true)))
                assertEquals(deadline, it.messages(bid).single { m -> m.localId == id }.expiry) }
            wall = deadline.wall; elapsed = deadline.elapsed
            a.reconcileLocalExpiry(); b.reconcileLocalExpiry()
            a.use { assertTrue(it.messages(bid).all { m -> m.policyEvent }) }
            b.use { assertTrue(it.messages(aid).all { m -> m.policyEvent }) }
        } finally { a.close(); b.close() }
    }

    @Test fun upgradeDisarmsLegacyQueuedExpiryBeforeDeletionButPreservesDeliveredDeadline() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = RandomIdentifiers.create()
        val old = ExpiryDeadline(1000, 1000, 1)
        val delivered = ExpiryDeadline(100_000, 100_000, 1)
        val clock = ExpiryClock({ 2000 }, { 2000 }, { 1 })
        EncryptedEndpointStore.open(context, name).use { store ->
            val repo = LocalRepository(store, clock)
            repo.save(Message("queued", "peer", Direction.OUTGOING, "still queued", 0, MessageState.SERVER_ACCEPTED,
                disappearingSeconds = 30, expiry = old))
            repo.save(Message("delivered", "peer", Direction.OUTGOING, "delivered", 0, MessageState.DELIVERED,
                disappearingSeconds = 30, expiry = delivered))
        }
        EncryptedEndpointStore.open(context, name).use { store ->
            val repo = LocalRepository(store, clock); repo.expire()
            assertEquals(2, repo.messages("peer").size)
            assertNull(repo.messages("peer").single { it.localId == "queued" }.expiry)
            repo.deliveredOutgoing("peer", "delivered")
            assertEquals(delivered, repo.messages("peer").single { it.localId == "delivered" }.expiry)
        }
        EncryptedEndpointStore.open(context, name).use { store ->
            val repo = LocalRepository(store, ExpiryClock({ 10_000_000 }, { 10_000_000 }, { 1 }))
            assertEquals("queued", repo.messages("peer").single().localId)
            assertNull(repo.messages("peer").single().expiry) // No ACK: no countdown, even much later.
        }
    }
}
