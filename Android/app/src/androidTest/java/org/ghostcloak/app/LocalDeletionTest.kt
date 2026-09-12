package org.ghostcloak.app

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.ghostcloak.app.application.*
import org.ghostcloak.identity.RandomIdentifiers
import org.ghostcloak.messaging.*
import org.ghostcloak.protocol.*
import org.ghostcloak.transport.GhostCloakTransport
import org.ghostcloak.crypto.EndpointRecords
import org.ghostcloak.crypto.EndpointStorageFailure
import org.ghostcloak.storage.EncryptedEndpointStore
import org.junit.Assert.*
import org.junit.Test

class LocalDeletionTest {
    @Test fun encryptedClearRollsBackAndCommittedDeletionSurvivesReopen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = RandomIdentifiers.create()
        EncryptedEndpointStore.open(context, name).use { store ->
            var injectFailure = false
            val records = object : EndpointRecords by store {
                override fun remove(key: String) {
                    store.remove(key)
                    if (injectFailure && key == "app/notification/a/second") throw EndpointStorageFailure()
                }
            }
            val repo = LocalRepository(records)
            repo.save(Contact("contact", "account", "Alice", "a", request = true))
            for (id in listOf("first", "second")) repo.saveAccepted(
                Message(id, "a", Direction.INCOMING, "private", 1, MessageState.RECEIVED, id), byteArrayOf(1))
            repo.markRead("a")
            val before = store.transaction { store.keys("").associateWith { store.read(it)!! } }
            injectFailure = true
            try { repo.clear("a"); fail("Expected failed transaction") } catch (_: EndpointStorageFailure) { }
            store.transaction {
                assertEquals(before.keys, store.keys("").toSet())
                before.forEach { (key, bytes) -> assertArrayEquals(bytes, store.read(key)) }
            }
            injectFailure = false
            repo.clear("a")
        }
        EncryptedEndpointStore.open(context, name).use { store ->
            val repo = LocalRepository(store)
            assertTrue(repo.messages("a").isEmpty()); assertEquals(0, repo.unreadCount("a"))
            assertTrue(repo.contact("a").request)
            store.transaction {
                assertTrue(store.keys("app/notification/").isEmpty()); assertNull(store.read("app/read/a"))
                assertEquals(2, store.keys("app/accepted/").size)
            }
        }
    }

    private class Publisher : LocalNotifications {
        var shown = false
        var posts = 0
        override fun allowed() = true
        override fun active() = shown
        override fun cancel() { shown = false }
        override fun post(quiet: Boolean): Boolean { posts++; shown = true; return true }
    }

    @Test fun ackDeleteReopenAndDuplicateCannotRestoreMessageOrAggregateNotification() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val api = SyntheticNetwork(); val publisher = Publisher(); val name = RandomIdentifiers.create()
        val a = AppRuntime(context, "https://fixture.invalid", RandomIdentifiers.create(), api)
        var b = AppRuntime(context, "https://fixture.invalid", name, api, notifications = publisher)
        try {
            a.use { a.create(it, "alice") }; b.use { b.create(it, "bob") }; a.use { a.addNetwork("bob", it) }
            val aid = a.use { it.open()!!.deviceId }; val bi = b.use { it.open()!! }
            a.use { a.send(it, bi.deviceId, "local deletion") }; val replay = api.mailbox.toMap()
            assertEquals(BackgroundResult.SUCCESS, b.backgroundSync())
            assertTrue(api.mailbox.isEmpty()); assertTrue(publisher.shown)
            val before = api.requests
            b.use { it.delete(aid, it.messages(aid).single().localId); assertEquals(0, it.unreadCount()) }
            assertEquals(before, api.requests); assertFalse(publisher.shown)
            b.close(); b = AppRuntime(context, "https://fixture.invalid", name, api, notifications = publisher)
            api.mailbox.putAll(replay); b.backgroundSync()
            b.use { assertTrue(it.messages(aid).isEmpty()); assertEquals(bi.deviceId, it.open()!!.deviceId)
                assertArrayEquals(bi.publicKey, it.open()!!.publicKey); assertTrue(it.contacts().single().contact.request) }
            assertTrue(api.mailbox.isEmpty()); assertFalse(publisher.shown); assertEquals(1, publisher.posts)
            a.use { a.send(it, bi.deviceId, "new arrival") }; b.backgroundSync()
            assertTrue(publisher.shown)
            b.use { it.clearConversation(aid); assertEquals(0, it.unreadCount()); assertEquals(1, it.contacts().size) }
            assertFalse(publisher.shown)
        } finally { a.close(); b.close() }
    }

    @Test fun deletingPendingAcceptedAndDeliveredPreservesDeliveryAndUnrelatedReceipts() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val api = SyntheticNetwork(); val receipts = mutableListOf<List<String>>()
        val transport = GhostCloakTransport { request ->
            (NetworkCodec.decode<ApiRequest>(request.body) as? ApiRequest.Fetch)?.let { receipts.add(it.submissionIds) }
            api.execute(request)
        }
        val a = AppRuntime(context, "https://fixture.invalid", RandomIdentifiers.create(), transport)
        val b = AppRuntime(context, "https://fixture.invalid", RandomIdentifiers.create(), api)
        try {
            a.use { a.create(it, "alice") }; b.use { b.create(it, "bob") }; a.use { a.addNetwork("bob", it) }
            val aid = a.use { it.open()!!.deviceId }; val bid = b.use { it.open()!!.deviceId }
            val identity = a.use { it.open()!! }; val fingerprint = a.use { it.fingerprint(bid) }
            api.rejectSend = true
            a.use { a.send(it, bid, "pending") }
            val pending = a.use { it.messages(bid).single().also { m -> assertEquals(MessageState.PENDING, m.state) } }
            val before = api.requests
            a.use { it.delete(bid, pending.localId) }; assertEquals(before, api.requests)
            api.rejectSend = false
            a.use { a.syncNetwork(it); assertTrue(it.messages(bid).isEmpty()) }
            b.use { b.syncNetwork(it); assertEquals("pending", it.messages(aid).single().body) }
            a.use { a.send(it, bid, "accepted delete"); a.send(it, bid, "accepted keep") }
            val deleted = a.use { it.messages(bid).first { m -> m.body == "accepted delete" } }
            assertEquals(MessageState.SERVER_ACCEPTED, deleted.state)
            val keep = a.use { it.messages(bid).first { m -> m.body == "accepted keep" }.localId }
            a.use { it.delete(bid, deleted.localId) }
            b.use { b.syncNetwork(it); assertEquals(3, it.messages(aid).size) }
            a.use { a.syncNetwork(it)
                assertEquals(MessageState.DELIVERED, it.messages(bid).single().state)
                assertEquals(keep, it.messages(bid).single().localId)
                it.delete(bid, keep); assertTrue(it.messages(bid).isEmpty())
                assertEquals(fingerprint, it.fingerprint(bid)); assertArrayEquals(identity.publicKey, it.open()!!.publicKey)
            }
            assertTrue(receipts.flatten().contains(keep)); assertFalse(receipts.flatten().contains(deleted.localId))
            assertFalse(receipts.flatten().contains(pending.localId))
            a.use { a.syncNetwork(it); assertTrue(it.messages(bid).isEmpty()) }
            b.use { assertEquals(3, it.messages(aid).size) }
        } finally { a.close(); b.close() }
    }
}
