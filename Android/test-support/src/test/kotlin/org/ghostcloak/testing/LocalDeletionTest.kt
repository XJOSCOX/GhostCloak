package org.ghostcloak.testing

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.ghostcloak.crypto.*
import org.ghostcloak.messaging.*
import org.ghostcloak.transport.LocalEncryptedRouter
import org.junit.Assert.*
import org.junit.Test

class LocalDeletionTest {
    @Test fun deleteAndClearAtomicallyRemoveReadAndAllNotificationStatesOnlyForSelectedContent() {
        for (state in listOf(NotificationLedger.PENDING, NotificationLedger.POSTING, NotificationLedger.ANNOUNCED)) {
            val records = MemoryRecords(); val repo = LocalRepository(records); val ledger = NotificationLedger(records)
            repo.save(Contact("a", "account", "Alice", "a", request = true))
            repo.save(Contact("b", "other", "Bob", "b"))
            fun incoming(id: String, peer: String) = Message(id, peer, Direction.INCOMING, "local", 1, MessageState.RECEIVED, id)
            repo.saveAccepted(incoming("read", "a"), byteArrayOf(1)); repo.markRead("a")
            repo.saveAccepted(incoming("unread", "a"), byteArrayOf(2))
            repo.saveAccepted(incoming("other", "b"), byteArrayOf(3))
            if (state >= NotificationLedger.POSTING) ledger.posting(ledger.eligible())
            if (state == NotificationLedger.ANNOUNCED) ledger.announced(ledger.eligible())
            val contact = repo.contact("a")
            repo.delete("a", "unread")
            assertEquals(0, repo.unreadCount("a")); assertEquals(1, repo.unreadCount("b"))
            assertEquals(listOf("read"), repo.messages("a").map { it.localId })
            assertNull(records.read("app/notification/a/unread"))
            repo.clear("a")
            assertNull(records.read("app/read/a")); assertTrue(repo.messages("a").isEmpty())
            assertEquals(contact, repo.contact("a")); assertEquals(1, ledger.eligible().size)
            assertTrue(repo.accepted("a", "unread", byteArrayOf(2)))
            assertEquals(1, repo.messages("b").size)
        }
    }

    @Test fun clearRollsBackAllMessagesMarkersAndEligibilityOnStorageFailure() {
        val memory = MemoryRecords(); var injectFailure = false
        val records = object : EndpointRecords by memory {
            override fun remove(key: String) {
                memory.remove(key)
                if (injectFailure && key == "app/notification/a/second") throw EndpointStorageFailure()
            }
        }
        val repo = LocalRepository(records)
        for (id in listOf("first", "second")) repo.saveAccepted(
            Message(id, "a", Direction.INCOMING, "private", 1, MessageState.RECEIVED, id), byteArrayOf(1))
        repo.markRead("a")
        val before = memory.keys("").associateWith { memory.read(it)!! }
        injectFailure = true
        try { repo.clear("a"); fail("Expected rollback") } catch (_: EndpointStorageFailure) { }
        assertEquals(before.keys, memory.keys("").toSet())
        before.forEach { (key, bytes) -> assertArrayEquals(bytes, memory.read(key)) }
    }

    @Test fun deletionPreservesReplayEvidenceIdentityVerificationRelationshipAndSession() = runBlocking<Unit> {
        val ar = MemoryRecords(); val br = MemoryRecords(); val repo = LocalRepository(br)
        val a = ConversationService(SignalProtocolEngine(ar), LocalRepository(ar))
        val b = ConversationService(SignalProtocolEngine(br), repo)
        val ai = a.create("Alice"); val bi = b.create("Bob")
        a.importCard(b.exportCard()); b.importCard(a.exportCard())
        b.verify(ai.deviceId, b.fingerprint(ai.deviceId)!!)
        val router = LocalEncryptedRouter(); a.attach(router.register(ai.deviceId)); val inbox = router.register(bi.deviceId)
        try {
            a.send(bi.deviceId, "delete me"); val envelope = inbox.receive().first(); b.acceptNetwork(envelope)
            val secure = br.keys("").filterNot { it.startsWith("app/message/") || it.startsWith("app/read/") || it.startsWith("app/notification/") }
                .associateWith { br.read(it)!! }
            b.delete(ai.deviceId, envelope.envelopeId)
            b.acceptNetwork(envelope)
            assertTrue(b.messages(ai.deviceId).isEmpty()); assertEquals(0, b.unreadCount())
            secure.forEach { (key, bytes) -> assertArrayEquals(bytes, br.read(key)) }
            assertTrue(NotificationLedger(br).eligible().isEmpty())
            for (request in listOf(false, true)) for (blocked in listOf(false, true)) {
                repo.save(repo.contact(ai.deviceId).copy(request = request, blocked = blocked))
                val status = b.contacts().single(); b.clearConversation(ai.deviceId)
                val after = b.contacts().single()
                assertEquals(status.contact, after.contact)
                assertEquals(status.identity?.trustState, after.identity?.trustState)
                assertEquals(status.session, after.session)
            }
            repo.save(repo.contact(ai.deviceId).copy(request = false, blocked = false))
            a.send(bi.deviceId, "still works"); b.acceptNetwork(inbox.receive().first())
            assertEquals("still works", b.messages(ai.deviceId).single().body)
        } finally { router.close() }
    }
}
