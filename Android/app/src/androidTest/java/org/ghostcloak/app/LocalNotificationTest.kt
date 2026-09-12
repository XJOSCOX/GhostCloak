package org.ghostcloak.app

import android.app.Notification
import android.app.NotificationManager
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.ghostcloak.app.application.*
import org.ghostcloak.identity.RandomIdentifiers
import org.junit.Assert.*
import org.junit.Test

class LocalNotificationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private class Death : Error()
    private class Publisher : LocalNotifications {
        var enabled = true
        var shown = false
        var dieBefore = false
        var dieAfter = false
        val posts = mutableListOf<Boolean>()
        var beforePost: () -> Unit = {}
        override fun allowed(): Boolean { if (dieBefore) throw Death(); return enabled }
        override fun active() = shown
        override fun cancel() { shown = false }
        override fun post(quiet: Boolean): Boolean {
            beforePost(); posts.add(quiet); shown = true
            if (dieAfter) throw Death()
            return true
        }
    }
    private fun runtime(api: SyntheticNetwork, publisher: LocalNotifications = NoLocalNotifications, name: String = RandomIdentifiers.create()) =
        AppRuntime(context, "https://fixture.invalid", name, api, notifications = publisher)
    private suspend fun prepare(a: AppRuntime, b: AppRuntime): Pair<String, String> {
        a.use { a.create(it, "alice") }; b.use { b.create(it, "bob") }
        a.use { a.addNetwork("bob", it) }
        return a.use { it.open()!!.deviceId } to b.use { it.open()!!.deviceId }
    }

    @Test fun committedAndAckedMessagePublishesOnceAndDismissalDoesNotRead() = runBlocking {
        val api = SyntheticNetwork(); val p = Publisher(); val a = runtime(api); val b = runtime(api, p)
        try {
            val (aid, bid) = prepare(a, b)
            a.use { a.send(it, bid, "private synthetic body") }; val replay = api.mailbox.toMap()
            p.beforePost = { assertTrue("ACK precedes publication", api.mailbox.isEmpty()) }
            b.backgroundSync(); assertEquals(listOf(false), p.posts)
            assertEquals("private synthetic body", b.use { it.messages(aid).single().body })
            api.mailbox.putAll(replay); b.backgroundSync(); assertEquals(1, p.posts.size)
            b.dismissNotifications(); assertFalse(p.shown)
            assertEquals(1, b.use { it.unreadCount() })
            b.backgroundSync(); assertEquals(1, p.posts.size)
        } finally { a.close(); b.close() }
    }

    @Test fun deniedPermissionStillStoresAcksAndLaterRecoversEligibility() = runBlocking {
        val api = SyntheticNetwork(); val p = Publisher().apply { enabled = false }
        val a = runtime(api); val name = RandomIdentifiers.create(); var b = runtime(api, p, name)
        try {
            val (aid, bid) = prepare(a, b); val identity = b.use { it.open()!! }
            a.use { a.send(it, bid, "denied") }; b.backgroundSync()
            assertTrue(api.mailbox.isEmpty()); assertTrue(p.posts.isEmpty())
            assertEquals("denied", b.use { it.messages(aid).single().body })
            b.close(); p.enabled = true; b = runtime(api, p, name); b.initializeBackground()
            assertEquals(listOf(false), p.posts)
            assertArrayEquals(identity.publicKey, b.use { it.open()!!.publicKey })
            assertEquals(2, api.registrations)
        } finally { a.close(); b.close() }
    }

    @Test fun deathAfterAckRetainsEligibilityAndDeathAfterPostRecoversSilently() = runBlocking {
        for (afterPost in listOf(false, true)) {
            val api = SyntheticNetwork(); val p = Publisher(); val a = runtime(api)
            val name = RandomIdentifiers.create(); var b = runtime(api, p, name)
            try {
                val (_, bid) = prepare(a, b); a.use { a.send(it, bid, "recovery") }
                p.dieBefore = !afterPost; p.dieAfter = afterPost
                try { b.backgroundSync(); fail("Expected simulated death") } catch (_: Death) { }
                assertTrue(api.mailbox.isEmpty()); b.close()
                p.dieBefore = false; p.dieAfter = false
                b = runtime(api, p, name); b.initializeBackground()
                assertEquals(if (afterPost) listOf(false, true) else listOf(false), p.posts)
                b.backgroundSync(); assertEquals(if (afterPost) 2 else 1, p.posts.size)
            } finally { a.close(); b.close() }
        }
    }

    @Test fun foregroundAndReadBeforePublicationSuppressWithoutLosingMessages() = runBlocking {
        val api = SyntheticNetwork(); val p = Publisher(); val a = runtime(api); val b = runtime(api, p)
        try {
            val (aid, bid) = prepare(a, b)
            b.notificationActivityVisible(true)
            a.use { a.send(it, bid, "visible") }; b.use { b.syncNetwork(it) }
            assertTrue(p.posts.isEmpty()); assertEquals(1, b.use { it.messages(aid).size })
            b.notificationActivityVisible(false); b.backgroundSync(); assertTrue(p.posts.isEmpty())
            a.use { a.send(it, bid, "read first") }
            b.use { b.syncNetwork(it); it.markRead(aid) }
            assertTrue(p.posts.isEmpty()); assertEquals(2, b.use { it.messages(aid).size })
        } finally { a.close(); b.close() }
    }

    @Test fun blockedAndMalformedDeliveryNeverPublish() = runBlocking {
        val api = SyntheticNetwork(); val p = Publisher(); val a = runtime(api); val b = runtime(api, p)
        try {
            val (aid, bid) = prepare(a, b); b.use { b.addNetwork("alice", it); it.block(aid, true) }
            a.use { a.send(it, bid, "blocked") }; b.backgroundSync()
            assertTrue(p.posts.isEmpty()); assertTrue(b.use { it.messages(aid).isEmpty() })
            b.use { it.block(aid, false) }
            api.mailbox.replaceAll { _, pair -> pair.first to org.ghostcloak.protocol.Delivery(pair.second.serverMessageId, byteArrayOf(0), pair.second.receivedAt, pair.second.expiresAt, pair.second.sender) }
            try { b.backgroundSync() } catch (_: Exception) { }
            assertTrue(p.posts.isEmpty()); assertTrue(b.use { it.messages(aid).isEmpty() })
        } finally { a.close(); b.close() }
    }

    @Test fun logoutClearsPendingNotificationWithoutReconnecting() = runBlocking {
        val api = SyntheticNetwork(); val p = Publisher().apply { enabled = false }
        val a = runtime(api); val b = runtime(api, p)
        try {
            val (_, bid) = prepare(a, b); a.use { a.send(it, bid, "logout") }; b.backgroundSync()
            b.use { b.logoutNetwork() }; val logins = api.logins
            p.enabled = true; b.backgroundSync(); assertTrue(p.posts.isEmpty()); assertEquals(logins, api.logins)
        } finally { a.close(); b.close() }
    }

    @Test fun platformPayloadIsGenericSecretAndImmutableWithRootOnlyIntent() {
        val publisher = AndroidLocalNotifications(context); publisher.createChannel()
        val channel = context.getSystemService(NotificationManager::class.java).getNotificationChannel(AndroidLocalNotifications.CHANNEL)
        assertFalse(channel.canShowBadge())
        // Android may retain its user-controlled channel visibility as VISIBILITY_NO_OVERRIDE.
        // The per-notification SECRET value below is the application's privacy boundary.
        assertTrue(channel.lockscreenVisibility in setOf(Notification.VISIBILITY_SECRET, -1000))
        for (quiet in listOf(false, true)) {
            val n = publisher.build(quiet)
            assertEquals("Ghost Cloak", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            assertEquals("New message", n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
            assertEquals(Notification.VISIBILITY_SECRET, n.visibility)
            assertNull(n.publicVersion); assertNull(n.largeIcon); assertNull(n.shortcutId)
            assertNull(n.extras.get("android.people.list")); assertEquals(0, n.number)
            assertTrue(n.contentIntent.isImmutable); assertTrue(n.deleteIntent.isImmutable)
            assertEquals(0, n.actions?.size ?: 0)
        }
        val intent = AndroidLocalNotifications.rootIntent(context)
        assertEquals(MainActivity::class.java.name, intent.component!!.className)
        assertEquals(AndroidLocalNotifications.OPEN_CHATS, intent.action)
        assertNull(intent.extras); assertNull(intent.data); assertNull(intent.clipData)
    }
}
