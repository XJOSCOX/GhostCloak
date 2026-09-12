package org.ghostcloak.testing

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.ghostcloak.crypto.*
import org.ghostcloak.messaging.*
import org.ghostcloak.transport.LocalEncryptedRouter
import org.junit.Assert.*
import org.junit.Test

class NotificationLedgerTest {
    @Test fun acceptanceLedgerRollsBackWithDecryptCommitAndDeduplicates() = runBlocking<Unit> {
        val memory = MemoryRecords()
        var failAcceptance = false
        val records = object : EndpointRecords by memory {
            override fun write(key: String, value: ByteArray) {
                memory.write(key, value)
                // Fail after the message, dedup marker and ledger write, inside decryptAndCommit.
                if (failAcceptance && key.startsWith("app/notification/")) throw EndpointStorageFailure()
            }
        }
        val repository = LocalRepository(records)
        val ar = MemoryRecords()
        val a = ConversationService(SignalProtocolEngine(ar), LocalRepository(ar))
        val b = ConversationService(SignalProtocolEngine(records), repository)
        val ai = a.create("Alice"); val bi = b.create("Bob")
        a.importCard(b.exportCard()); b.importCard(a.exportCard())
        val router = LocalEncryptedRouter(); a.attach(router.register(ai.deviceId)); val inbox = router.register(bi.deviceId)
        try {
            a.send(bi.deviceId, "committed only"); val envelope = inbox.receive().first()
            val ledger = NotificationLedger(records)
            failAcceptance = true
            try { b.acceptNetwork(envelope); fail("Commit succeeded") } catch (_: Exception) { }
            failAcceptance = false
            assertTrue(ledger.eligible().isEmpty()); assertTrue(repository.messages(ai.deviceId).isEmpty())
            b.acceptNetwork(envelope)
            assertEquals(NotificationLedger.PENDING, ledger.eligible().single().state)
            assertEquals("committed only", repository.messages(ai.deviceId).single().body)
            ledger.posting(ledger.eligible()); ledger.announced(ledger.eligible())
            b.acceptNetwork(envelope)
            assertEquals(NotificationLedger.ANNOUNCED, ledger.eligible().single().state)
            ledger.dismissPublished(); assertTrue(ledger.eligible().isEmpty())
            assertEquals(1, repository.unreadCount(ai.deviceId))
            b.acceptNetwork(envelope); assertTrue(ledger.eligible().isEmpty())
            a.send(bi.deviceId, "tampered"); val bad = inbox.receive().first()
            bad.encryptedPayload.fill(0)
            try { b.acceptNetwork(bad); fail("Invalid decrypt accepted") } catch (_: Exception) { }
            assertTrue(ledger.eligible().isEmpty()); assertEquals(1, repository.messages(ai.deviceId).size)
        } finally { router.close() }
    }

    @Test fun readBlockedDeletedAndForegroundSuppressedEligibilityDoesNotReturn() {
        for (reason in listOf("read", "blocked", "deleted", "foreground")) {
            val records = MemoryRecords(); val repository = LocalRepository(records); val ledger = NotificationLedger(records)
            repository.save(Contact("contact", "account", "synthetic", "device"))
            val message = Message("message", "device", Direction.INCOMING, "private", 1, MessageState.RECEIVED, "envelope")
            repository.saveAccepted(message, byteArrayOf(1))
            assertEquals(1, ledger.eligible().size)
            when (reason) {
                "read" -> repository.markRead("device")
                "blocked" -> repository.save(repository.contact("device").copy(blocked = true))
                "deleted" -> repository.delete("device", "message")
                else -> ledger.suppressAll()
            }
            assertTrue(ledger.eligible().isEmpty())
            assertTrue(NotificationLedger(records).eligible().isEmpty())
            if (reason == "foreground") assertEquals(1, repository.unreadCount("device"))
        }
    }
}
