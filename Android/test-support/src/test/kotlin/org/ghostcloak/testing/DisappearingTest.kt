package org.ghostcloak.testing

import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.ghostcloak.crypto.*
import org.ghostcloak.messaging.*
import org.ghostcloak.protocol.*
import org.ghostcloak.transport.IdempotentMessageTransport
import org.junit.Assert.*
import org.junit.Test

class DisappearingTest {
    private class Time {
        var wall = 1_000_000L; var elapsed = 100_000L; var boot = 1
        fun clock() = ExpiryClock({ wall }, { elapsed }, { boot })
        fun advance(ms: Long) { wall += ms; elapsed += ms }
    }
    private class Wire : IdempotentMessageTransport {
        var offline = false
        val envelopes = mutableListOf<EncryptedEnvelope>()
        override fun receive() = emptyFlow<EncryptedEnvelope>()
        override suspend fun send(routingDestination: String, envelope: EncryptedEnvelope) = Unit
        override suspend fun submit(submissionId: String, routingDestination: String, envelope: EncryptedEnvelope): String {
            if (offline) throw ApiFailure(503, "offline")
            envelopes.add(envelope); return submissionId
        }
    }
    private class Pairing {
        val time = Time(); val ar = MemoryRecords(); val br = MemoryRecords()
        val ae = SignalProtocolEngine(ar); val be = SignalProtocolEngine(br)
        val ap = LocalRepository(ar, time.clock()); val bp = LocalRepository(br, time.clock())
        val a = ConversationService(ae, ap); val b = ConversationService(be, bp)
        val aw = Wire(); val bw = Wire()
        val ao = DurableOutbox(ar, ae, aw, time = { time.wall }); val bo = DurableOutbox(br, be, bw, time = { time.wall })
        lateinit var aid: String; lateinit var bid: String
        suspend fun prepare() {
            aid = a.create("Alice").deviceId; bid = b.create("Bob").deviceId
            a.importCard(b.exportCard()); b.importCard(a.exportCard())
        }
    }

    @Test fun everyTimerOffAndLastProcessedControlsPersistWithoutUnreadOrNotifications() = runBlocking {
        val p = Pairing(); p.prepare()
        assertEquals(0, p.ap.policy(p.bid))
        for (timer in DisappearingTimer.entries) {
            p.a.setDisappearing(p.bid, timer.seconds, p.ao)
            p.b.acceptNetwork(p.aw.envelopes.last())
            assertEquals(timer.seconds, LocalRepository(p.ar).policy(p.bid))
            assertEquals(timer.seconds, p.bp.policy(p.aid))
        }
        assertEquals(0, p.b.unreadCount()); assertTrue(NotificationLedger(p.br).eligible().isEmpty())
        assertTrue(p.b.messages(p.aid).all { it.policyEvent && it.expiry == null })
        p.a.setDisappearing(p.bid, 30, p.ao); val fromA = p.aw.envelopes.last()
        p.b.setDisappearing(p.aid, 300, p.bo); val fromB = p.bw.envelopes.last()
        p.a.acceptNetwork(fromB); p.b.acceptNetwork(fromA)
        assertEquals(300, p.ap.policy(p.bid)); assertEquals(30, p.bp.policy(p.aid))
        p.a.acceptNetwork(fromB); assertEquals(300, p.ap.policy(p.bid)) // Replay is not a new event.
        p.a.clearConversation(p.bid); assertEquals(300, p.ap.policy(p.bid)); assertTrue(p.a.messages(p.bid).isEmpty())
        p.a.setDisappearing(p.bid, 0, p.ao); p.b.acceptNetwork(p.aw.envelopes.last()); assertEquals(0, p.bp.policy(p.aid))
    }

    @Test fun pendingAndQueuedDoNotExpireThenDeliveryAndIncomingUseIndependentFixedDeadlines() = runBlocking {
        val p = Pairing(); p.prepare()
        val off = p.a.sendNetwork(p.bid, "keep", p.ao)
        assertNull(off.expiry)
        p.a.setDisappearing(p.bid, 30, p.ao)
        p.aw.offline = true
        val pending = p.a.sendNetwork(p.bid, "disappear", p.ao)
        assertNull(pending.expiry); assertEquals(30, pending.disappearingSeconds)
        p.time.advance(7_200_000)
        assertTrue(p.a.messages(p.bid).any { it.localId == pending.localId })
        p.aw.offline = false; p.a.retryNetwork(p.ao)
        val accepted = p.a.messages(p.bid).single { it.localId == pending.localId }
        assertNull(accepted.expiry)
        val envelope = p.aw.envelopes.last()
        p.time.advance(7_200_000)
        assertNull(p.a.messages(p.bid).single { it.localId == pending.localId }.expiry)
        p.a.setDisappearing(p.bid, 300, p.ao) // The queued message still carries 30 seconds.
        p.time.advance(10_000); p.b.acceptNetwork(envelope)
        val received = p.b.messages(p.aid).single()
        assertEquals(p.time.wall + 30_000, received.expiry!!.wall)
        val ack = listOf(DeliveryStatus(pending.localId, true))
        p.a.deliveryStatuses(ack)
        val deadline = p.a.messages(p.bid).single { it.localId == pending.localId }.expiry!!
        assertEquals(p.time.wall + 30_000, deadline.wall)
        p.a.setDisappearing(p.bid, 0, p.ao)
        p.time.advance(20_000)
        p.a.deliveryStatuses(ack)
        assertEquals(deadline, p.a.messages(p.bid).single { it.localId == pending.localId }.expiry)
        assertTrue(p.a.messages(p.bid).any { it.localId == off.localId })
        assertEquals(1, p.b.unreadCount())
        val secure = p.br.keys("").filterNot { it.startsWith("app/message/") || it.startsWith("app/read/") || it.startsWith("app/notification/") }
            .associateWith { p.br.read(it)!! }
        p.b.markRead(p.aid); p.time.advance(10_000); p.b.reconcileExpiry()
        assertFalse(p.a.messages(p.bid).any { it.localId == pending.localId })
        assertTrue(p.b.messages(p.aid).isEmpty()); assertNull(p.br.read("app/read/${p.aid}"))
        assertTrue(NotificationLedger(p.br, p.bp.clock).eligible().isEmpty())
        secure.forEach { (key, bytes) -> assertArrayEquals(bytes, p.br.read(key)) }
        p.b.acceptNetwork(envelope); assertTrue(p.b.messages(p.aid).isEmpty())
    }

    @Test fun clockRollbackForwardRestartAndRebootHaveBoundedDocumentedBehavior() {
        val time = Time(); val clock = time.clock(); val deadline = ExpiryDeadline.start(30, clock.now())
        time.wall -= 90_000; time.elapsed += 30_000
        assertTrue(deadline.reached(clock.now()))
        assertTrue(deadline.reached(time.clock().now())) // Same boot process recreation uses persisted elapsed deadline.
        val other = ExpiryDeadline.start(30, clock.now())
        time.wall += 1_000_000; assertTrue(other.reached(clock.now()))
        time.wall -= 1_000_000; assertTrue(other.reached(clock.now())) // Observed forward jump cannot be undone.
        time.boot++; time.elapsed = 1
        assertFalse(other.reached(time.clock().now())) // Reboot + changed wall time cannot be made tamper-proof.
        time.wall = other.wall; assertTrue(other.reached(time.clock().now()))
    }

    @Test fun strictFramingAndUnsupportedControlsRollbackWithoutDamagingSessions() = runBlocking {
        val p = Pairing(); p.prepare()
        val text = ConversationPayload.encode("short", 0)
        val control = ConversationPayload.encode("", 30, true)
        assertEquals(text.size, control.size)
        assertEquals("legacy", ConversationPayload.decode("legacy".encodeToByteArray()).body)
        val max = ConversationPayload.encode("a".repeat(ConversationPayload.MAX_TEXT), 604800)
        assertEquals(16384, max.size); assertEquals(604800, ConversationPayload.decode(max).seconds)
        for (bad in listOf(control.copyOf().also { it[5] = 99 }, control.copyOf().also { it[4] = 2 },
            control.copyOf().also { it[11] = 31 }, control.copyOf(255), text.copyOf().also { it[16] = -1 })) {
            val envelope = p.ae.encrypt(p.bid, bad)
            val before = p.br.keys("").associateWith { p.br.read(it)!! }
            try { p.b.acceptNetwork(envelope); fail("Unsupported payload accepted") } catch (_: Exception) { }
            assertEquals(before.keys, p.br.keys("").toSet())
            before.forEach { (key, bytes) -> assertArrayEquals(bytes, p.br.read(key)) }
        }
        p.a.sendNetwork(p.bid, "after invalid", p.ao); p.b.acceptNetwork(p.aw.envelopes.last())
        assertEquals("after invalid", p.b.messages(p.aid).single().body)
    }

    @Test fun policyIsAtomicWithOutboxAndUnacceptedControlsCannotBypassRequestGate() = runBlocking {
        val p = Pairing(); p.prepare(); p.aw.offline = true
        val pending = p.a.setDisappearing(p.bid, 30, p.ao)
        assertEquals(MessageState.PENDING, pending.state); assertEquals(30, p.ap.policy(p.bid))
        assertEquals(1, p.ao.pendingIds().size)
        p.aw.offline = false; p.a.retryNetwork(p.ao)
        p.bp.save(p.bp.contact(p.aid).copy(request = true))
        val envelope = p.aw.envelopes.last()
        try { p.b.acceptNetwork(envelope); fail("Request gate bypassed") } catch (_: Exception) { }
        assertTrue(p.bp.contact(p.aid).request); assertEquals(0, p.bp.policy(p.aid)); assertTrue(p.b.messages(p.aid).isEmpty())
        p.b.acceptRequest(p.aid); p.b.acceptNetwork(envelope); assertEquals(30, p.bp.policy(p.aid))
        val before = p.ap.policy(p.bid)
        // Throw after the outbox record write: the same transaction must roll everything back.
        try { p.ao.enqueue(p.bid, ConversationPayload.encode("", 300, true)) {
            p.ap.policy(p.bid, 300); throw EndpointStorageFailure()
        }; fail("Expected rollback") } catch (_: EndpointStorageFailure) { }
        assertEquals(before, p.ap.policy(p.bid)); assertTrue(p.ao.pendingIds().isEmpty())
    }
}
