package org.ghostcloak.app

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.ghostcloak.app.access.*
import org.ghostcloak.app.application.*
import org.ghostcloak.identity.RandomIdentifiers
import org.junit.Assert.*
import org.junit.Test

class AppLockStorageTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun lock(runtime: AppRuntime, scope: CoroutineScope) = AppLockController(object : LockPersistence {
        override suspend fun read() = runtime.readAppLock()
        override suspend fun write(bytes: ByteArray) = runtime.writeAppLock(bytes)
    }, scope, android.os.SystemClock::elapsedRealtime, { 1 })
    @Test fun lockedBackgroundStoresAcksAndNotifiesWithoutChangingIdentitySessionOrLogout() = runBlocking {
        val api = SyntheticNetwork(); var posts = 0
        val sink = object : LocalNotifications {
            override fun allowed() = true
            override fun active() = posts > 0
            override fun post(quiet: Boolean): Boolean { posts++; return true }
            override fun cancel() = Unit
        }
        val name = RandomIdentifiers.create()
        val a = AppRuntime(context, "https://fixture.invalid", RandomIdentifiers.create(), api)
        var b = AppRuntime(context, "https://fixture.invalid", name, api, notifications = sink)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            a.use { a.create(it, "alice") }; b.use { b.create(it, "bob") }; a.use { a.addNetwork("bob", it) }
            val aid = a.use { it.open()!!.deviceId }; val identity = b.use { it.open()!! }; val logins = api.logins
            var controller = lock(b, scope)
            withContext(Dispatchers.Main) {
                controller.start(); controller.initialize()
                assertTrue(controller.configure(LockMode.PIN, LockTiming.IMMEDIATE, "824619".toCharArray(), "824619".toCharArray()))
                controller.stop()
            }
            a.use { a.send(it, identity.deviceId, "Synthetic locked delivery") }
            val replay = api.mailbox.toMap()
            assertEquals(BackgroundResult.SUCCESS, b.backgroundSync())
            assertTrue(api.mailbox.isEmpty()); assertEquals(1, posts)
            assertFalse(controller.state.value.canShowContent)
            assertEquals("Synthetic locked delivery", b.use { it.messages(aid).single().body })
            val bytes = b.readAppLock()!!
            assertFalse(bytes.toString(Charsets.ISO_8859_1).contains("824619"))
            assertEquals(LockMode.PIN, LockConfiguration.decode(bytes).mode)
            b.close(); b = AppRuntime(context, "https://fixture.invalid", name, api, notifications = sink)
            controller = lock(b, scope)
            withContext(Dispatchers.Main) { controller.start(); controller.initialize() }
            assertFalse(controller.state.value.canShowContent)
            api.mailbox.putAll(replay); b.backgroundSync(); assertEquals(1, posts)
            withContext(Dispatchers.Main) { assertTrue(controller.verifyPin("824619".toCharArray())) }
            assertEquals(identity.deviceId, b.use { it.open()!!.deviceId })
            assertArrayEquals(identity.publicKey, b.use { it.open()!!.publicKey })
            assertEquals(logins, api.logins); assertEquals(2, api.registrations)
            assertTrue(b.canAutoSync)
            b.use { b.logoutNetwork() }
            assertEquals(LockMode.PIN, LockConfiguration.decode(b.readAppLock()!!).mode)
            withContext(Dispatchers.Main) { controller.stop(); controller.start(); assertTrue(controller.verifyPin("824619".toCharArray())) }
            assertFalse(b.canAutoSync); b.backgroundSync(); assertEquals(logins, api.logins)
        } finally { scope.cancel(); a.close(); b.close() }
    }
}
