package org.ghostcloak.app

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.ghostcloak.app.application.AppRuntime
import org.ghostcloak.identity.RandomIdentifiers
import org.ghostcloak.messaging.MessageState
import org.junit.Assert.*
import org.junit.Test

class UnreadMessagesTest {
    @Test fun unreadIsLocalDurableAndIndependentOfDeliveryAck() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val api = SyntheticNetwork(); val name = RandomIdentifiers.create()
        val a = AppRuntime(context, "https://fixture.invalid", RandomIdentifiers.create(), api)
        var b = AppRuntime(context, "https://fixture.invalid", name, api)
        try {
            a.use { a.create(it, "alice") }; b.use { b.create(it, "bob") }; a.use { a.addNetwork("bob", it) }
            val aid = a.use { it.open()!!.deviceId }; val bid = b.use { it.open()!!.deviceId }
            repeat(2) { a.use { a.send(it, bid, "Unread fixture") } }
            b.use { b.syncNetwork(it); assertEquals(2, it.unreadCount()) }
            a.use { a.syncNetwork(it); assertTrue(it.messages(bid).all { m -> m.state == MessageState.DELIVERED }) }
            val requests = api.requests
            b.use { it.markRead(aid); assertEquals(0, it.unreadCount()) }
            assertEquals(requests, api.requests)
            b.close(); b = AppRuntime(context, "https://fixture.invalid", name, api)
            b.use { assertEquals(0, it.unreadCount()) }
            a.use { a.send(it, bid, "New since reading") }
            b.use { b.syncNetwork(it); assertEquals(1, it.unreadCount()); b.syncNetwork(it); assertEquals(1, it.unreadCount())
                it.delete(aid, it.messages(aid).last().localId); assertEquals(0, it.unreadCount()) }
        } finally { a.close(); b.close() }
    }
}
