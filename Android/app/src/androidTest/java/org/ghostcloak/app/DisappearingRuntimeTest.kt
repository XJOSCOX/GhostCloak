package org.ghostcloak.app

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.ghostcloak.app.application.*
import org.ghostcloak.identity.RandomIdentifiers
import org.ghostcloak.messaging.*
import org.junit.Assert.*
import org.junit.Test

class DisappearingRuntimeTest {
    private class Publisher : LocalNotifications {
        var shown = false; var posts = 0; var enabled = true
        override fun allowed() = enabled
        override fun active() = shown
        override fun cancel() { shown = false }
        override fun post(quiet: Boolean): Boolean { shown = true; posts++; return true }
    }
    @Test fun encryptedPolicyAndExpirySurviveReopenAndBackgroundCleansWithoutNetworkOrNewAlerts() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var wall = 1_000_000L; var elapsed = 100_000L
        fun clock() = ExpiryClock({ wall }, { elapsed }, { 7 })
        val api = SyntheticNetwork(); val p = Publisher(); val name = RandomIdentifiers.create()
        val a = AppRuntime(context, "https://fixture.invalid", RandomIdentifiers.create(), api, expiryClock = clock())
        var b = AppRuntime(context, "https://fixture.invalid", name, api, notifications = p, expiryClock = clock())
        try {
            a.use { a.create(it, "alice") }; b.use { b.create(it, "bob") }; a.use { a.addNetwork("bob", it) }
            val aid = a.use { it.open()!!.deviceId }; val bi = b.use { it.open()!! }
            a.use { a.send(it, bi.deviceId, "request") }; b.backgroundSync()
            b.use { assertTrue(it.contacts().single().contact.request); it.acceptRequest(aid); it.clearConversation(aid) }
            val previousPosts = p.posts
            a.use { a.setDisappearing(it, bi.deviceId, 30) }; val controlReplay = api.mailbox.toMap()
            b.backgroundSync()
            b.use { assertEquals(30, it.policies()[aid]); assertEquals(0, it.unreadCount()); assertTrue(it.messages(aid).single().policyEvent) }
            assertEquals(previousPosts, p.posts); assertFalse(p.shown)
            a.use { a.send(it, bi.deviceId, "expires") }; val replay = api.mailbox.toMap()
            b.backgroundSync(); assertTrue(p.shown); assertTrue(api.mailbox.isEmpty())
            val fingerprint = b.use { it.fingerprint(aid) }
            b.close(); b = AppRuntime(context, "https://fixture.invalid", name, api, notifications = p, expiryClock = clock())
            wall -= 100_000; elapsed += 30_000 // Process restart and backwards clock in the same boot.
            val requests = api.requests
            b.reconcileLocalExpiry(); assertEquals(requests, api.requests); assertFalse(p.shown)
            b.use { assertEquals(30, it.policies()[aid]); assertEquals(0, it.unreadCount())
                assertTrue(it.messages(aid).all { m -> m.policyEvent }); assertEquals(bi.deviceId, it.open()!!.deviceId)
                assertArrayEquals(bi.publicKey, it.open()!!.publicKey); assertEquals(fingerprint, it.fingerprint(aid)) }
            val posts = p.posts
            api.mailbox.putAll(replay); b.backgroundSync(); assertTrue(api.mailbox.isEmpty())
            assertEquals(posts, p.posts); assertFalse(p.shown)
            b.use { b.setDisappearing(it, aid, 0) }
            api.mailbox.putAll(controlReplay); b.backgroundSync()
            b.use { assertEquals(0, it.policies()[aid]); it.clearConversation(aid); assertEquals(0, it.policies()[aid]) }
            a.use { a.syncNetwork(it); a.send(it, bi.deviceId, "off now") }
            b.backgroundSync(); wall += 604_800_000; elapsed += 604_800_000
            b.reconcileLocalExpiry(); b.use { assertEquals("off now", it.messages(aid).single().body); assertNull(it.messages(aid).single().expiry) }
            b.use { it.clearConversation(aid) }
            p.enabled = false; val beforePending = p.posts
            a.use { a.setDisappearing(it, bi.deviceId, 30); a.send(it, bi.deviceId, "expire before publication") }
            b.backgroundSync(); assertEquals(beforePending, p.posts)
            wall += 30_000; elapsed += 30_000; p.enabled = true
            b.reconcileLocalExpiry(); assertEquals(beforePending, p.posts); assertFalse(p.shown)
            a.use { a.send(it, bi.deviceId, "expire while logged out") }; b.backgroundSync()
            b.use { b.logoutNetwork() }
            val afterLogout = api.requests; val logins = api.logins
            wall += 30_000; elapsed += 30_000; b.reconcileLocalExpiry()
            b.use { assertTrue(it.messages(aid).all { m -> m.policyEvent }) }
            assertEquals(afterLogout, api.requests); assertEquals(logins, api.logins); assertFalse(b.canAutoSync)
        } finally { a.close(); b.close() }
    }

    @Test fun firstControlCannotCreateOrAcceptRequestButNormalTimedMessageCan() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val api = SyntheticNetwork(); val p = Publisher()
        val a = AppRuntime(context, "https://fixture.invalid", RandomIdentifiers.create(), api)
        val b = AppRuntime(context, "https://fixture.invalid", RandomIdentifiers.create(), api, notifications = p)
        try {
            a.use { a.create(it, "alice") }; b.use { b.create(it, "bob") }; a.use { a.addNetwork("bob", it) }
            val aid = a.use { it.open()!!.deviceId }; val bid = b.use { it.open()!!.deviceId }
            a.use { a.setDisappearing(it, bid, 30) }; b.backgroundSync()
            b.use { assertTrue(it.contacts().isEmpty()); assertEquals(0, it.unreadCount()) }
            assertFalse(p.shown)
            a.use { a.send(it, bid, "request with own duration") }; b.backgroundSync()
            b.use { assertTrue(it.contacts().single().contact.request)
                assertEquals(30, it.messages(aid).single().disappearingSeconds)
                assertEquals(0, it.policies()[aid]); it.acceptRequest(aid) }
            b.backgroundSync(); b.use { assertEquals(30, it.policies()[aid]) }
        } finally { a.close(); b.close() }
    }
}
