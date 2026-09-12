package org.ghostcloak.app

import android.app.NotificationManager
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.*
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.*
import org.ghostcloak.app.application.*
import org.ghostcloak.identity.RandomIdentifiers
import org.ghostcloak.messaging.MessageState
import org.ghostcloak.protocol.*
import org.ghostcloak.transport.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class BackgroundSyncTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun runtime(api: GhostCloakTransport, name: String = RandomIdentifiers.create(), eligibility: (Boolean) -> Unit = {}) =
        AppRuntime(context, "https://fixture.invalid", name, api, eligibility)
    private fun worker(runtime: AppRuntime): BackgroundSyncWorker = TestListenableWorkerBuilder<BackgroundSyncWorker>(context)
        .setWorkerFactory(object : WorkerFactory() {
            override fun createWorker(appContext: android.content.Context, name: String, params: WorkerParameters) =
                BackgroundSyncWorker(appContext, params, runtime)
        }).build()

    @Suppress("RestrictedApi")
    @Test fun policyHasOneUniquePeriodicJobAndNoPrivatePayload() = runBlocking {
        val application = context.applicationContext as GhostApplication
        application.runtime.initializeBackground()
        val manager = WorkManager.getInstance(context)
        manager.cancelUniqueWork(BackgroundSyncSchedule.NAME).result.get()
        try {
            val request = BackgroundSyncSchedule.request()
            assertEquals(TimeUnit.MINUTES.toMillis(30), request.workSpec.intervalDuration)
            assertEquals(TimeUnit.MINUTES.toMillis(15), request.workSpec.flexDuration)
            assertEquals(NetworkType.CONNECTED, request.workSpec.constraints.requiredNetworkType)
            assertFalse(request.workSpec.expedited)
            assertEquals(Data.EMPTY, request.workSpec.input)
            assertEquals(Data.EMPTY, request.workSpec.output)
            assertEquals(setOf(BackgroundSyncWorker::class.java.name), request.tags)
            assertEquals("ghostcloak-background-sync", BackgroundSyncSchedule.NAME)
            manager.enqueueUniquePeriodicWork(BackgroundSyncSchedule.NAME, ExistingPeriodicWorkPolicy.KEEP, request).result.get()
            manager.enqueueUniquePeriodicWork(BackgroundSyncSchedule.NAME, ExistingPeriodicWorkPolicy.KEEP, BackgroundSyncSchedule.request()).result.get()
            val jobs = manager.getWorkInfosForUniqueWork(BackgroundSyncSchedule.NAME).get().filter { !it.state.isFinished }
            assertEquals(1, jobs.size)
            assertEquals(request.id, jobs.single().id)
            val defaultWorker = TestListenableWorkerBuilder<BackgroundSyncWorker>(context).build()
            assertSame(application.runtime, defaultWorker.runtime)
        } finally { manager.cancelUniqueWork(BackgroundSyncSchedule.NAME).result.get() }
    }

    @Test fun absentIdentityNeverCreatesFilesKeysOrRegisters() = runBlocking {
        val api = SyntheticNetwork(); val name = RandomIdentifiers.create(); val a = runtime(api, name)
        try {
            assertEquals(ListenableWorker.Result.success(), worker(a).doWork())
            assertEquals(0, api.requests); assertEquals(0, api.registrations)
            assertFalse(java.io.File(context.noBackupFilesDir, "$name.db").exists())
            val keys = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            assertFalse(keys.containsAlias("ghost-cloak.db.$name"))
        } finally { a.close() }
    }

    @Test fun backgroundReceivesDecryptsAcksDeduplicatesAndNeverNotifies() = runBlocking {
        val api = SyntheticNetwork(); val a = runtime(api); val b = runtime(api)
        val notifications = context.getSystemService(NotificationManager::class.java)
        val before = notifications.activeNotifications.map { it.key }.toSet()
        try {
            a.use { a.create(it, "alice") }; b.use { b.create(it, "bob") }
            a.use { a.addNetwork("bob", it) }
            val aid = a.use { it.open()!!.deviceId }; val bid = b.use { it.open()!!.deviceId }
            val identity = b.use { it.open()!! }
            a.use { a.send(it, bid, "Synthetic background message") }
            val replay = api.mailbox.toMap()
            assertEquals(ListenableWorker.Result.success(), worker(b).doWork())
            assertTrue(api.mailbox.isEmpty())
            assertEquals("Synthetic background message", b.use { it.messages(aid).single().body })
            assertTrue(b.use { it.contacts().single().contact.request })
            api.mailbox.putAll(replay)
            assertEquals(ListenableWorker.Result.success(), worker(b).doWork())
            assertTrue(api.mailbox.isEmpty()); assertEquals(1, b.use { it.messages(aid).size })
            a.use { a.syncNetwork(it) }
            assertEquals(MessageState.DELIVERED, a.use { it.messages(bid).single().state })
            assertEquals(identity.deviceId, b.use { it.open()!!.deviceId })
            assertArrayEquals(identity.publicKey, b.use { it.open()!!.publicKey })
            assertEquals(2, api.registrations)
            assertEquals(before, notifications.activeNotifications.map { it.key }.toSet())
        } finally { a.close(); b.close() }
    }

    @Test fun backgroundExpiryRenewsOnceAndRecreationPreservesIdentity() = runBlocking {
        val api = SyntheticNetwork(); val name = RandomIdentifiers.create(); var a = runtime(api, name)
        try {
            a.use { a.create(it, "alice") }; val identity = a.use { it.open()!! }
            a.close(); a = runtime(api, name)
            api.expireSessions(); val logins = api.logins
            assertEquals(ListenableWorker.Result.success(), worker(a).doWork())
            assertEquals(logins + 1, api.logins); assertEquals(1, api.registrations)
            assertEquals(identity.deviceId, a.use { it.open()!!.deviceId })
            assertArrayEquals(identity.publicKey, a.use { it.open()!!.publicKey })
            api.rejectAuthenticated = true
            assertEquals(ListenableWorker.Result.success(), worker(a).doWork())
            assertEquals(logins + 2, api.logins)
            a.close(); a = runtime(api, name)
            assertEquals(ListenableWorker.Result.success(), worker(a).doWork())
            assertEquals(logins + 2, api.logins) // Repeated 401 remains paused across restart.
            assertEquals(NetworkStatus.NEEDS_CONNECT, a.networkStatus)
            assertEquals(ListenableWorker.Result.success(), worker(a).doWork())
            assertEquals(logins + 2, api.logins)
        } finally { a.close() }
    }

    @Test fun logoutCancelsEligibilityAndNeverReconnectsAfterRecreation() = runBlocking {
        val api = SyntheticNetwork(); val changes = mutableListOf<Boolean>(); val name = RandomIdentifiers.create()
        val manager = WorkManager.getInstance(context)
        var a = runtime(api, name) { changes.add(it); BackgroundSyncSchedule.reconcile(context, it) }
        try {
            a.use { a.create(it, "alice") }; assertEquals(true, changes.last())
            assertEquals(1, manager.getWorkInfosForUniqueWork(BackgroundSyncSchedule.NAME).get().count { !it.state.isFinished })
            a.use { a.logoutNetwork() }; assertEquals(false, changes.last())
            assertEquals(0, manager.getWorkInfosForUniqueWork(BackgroundSyncSchedule.NAME).get().count { !it.state.isFinished })
            val requests = api.requests; val logins = api.logins
            assertEquals(ListenableWorker.Result.success(), worker(a).doWork())
            a.close(); a = runtime(api, name)
            assertEquals(ListenableWorker.Result.success(), worker(a).doWork())
            assertEquals(requests, api.requests); assertEquals(logins, api.logins); assertEquals(1, api.registrations)
        } finally { a.close(); manager.cancelUniqueWork(BackgroundSyncSchedule.NAME).result.get() }
    }

    @Test fun foregroundAndDuplicateWorkerJoinInFlightSync() = runBlocking {
        val api = SyntheticNetwork(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var fetches = 0
        val a = runtime(GhostCloakTransport { request ->
            if (NetworkCodec.decode<ApiRequest>(request.body) is ApiRequest.Fetch) {
                fetches++; entered.complete(Unit); release.await()
            }
            api.execute(request)
        })
        try {
            a.use { a.create(it, "alice") }
            val first = async { a.backgroundSync() }; entered.await()
            val duplicate = async(start = CoroutineStart.UNDISPATCHED) { a.backgroundSync() }
            a.foregroundStarted()
            val ticket = a.syncGeneration
            val foreground = async(start = CoroutineStart.UNDISPATCHED) { a.use { a.syncNetwork(it, ticket) } }
            release.complete(Unit)
            assertEquals(BackgroundResult.SUCCESS, first.await())
            assertEquals(BackgroundResult.SKIP, duplicate.await()); foreground.await()
            assertEquals(1, fetches)
            assertEquals(BackgroundResult.SKIP, a.backgroundSync()); assertEquals(1, fetches)
        } finally { release.complete(Unit); a.foregroundStopped(); a.close() }
    }

    @Test fun cooldownPersistsAcrossRuntimeRecreationAndManualSyncHonorsIt() = runBlocking {
        val api = SyntheticNetwork(); var fetches = 0; val name = RandomIdentifiers.create()
        val transport = GhostCloakTransport { request ->
            if (NetworkCodec.decode<ApiRequest>(request.body) is ApiRequest.Fetch) {
                fetches++; TransportResponse(429, null, byteArrayOf(), 60_000)
            } else api.execute(request)
        }
        var a = runtime(transport, name)
        try {
            a.use { a.create(it, "alice") }
            assertEquals(ListenableWorker.Result.retry(), worker(a).doWork()); assertEquals(1, fetches)
            a.close(); a = runtime(transport, name)
            assertEquals(ListenableWorker.Result.retry(), worker(a).doWork()); assertEquals(1, fetches)
            try { a.use { a.syncNetwork(it) }; fail("Cooldown bypassed") } catch (e: ApiFailure) { assertEquals(429, e.status) }
            assertEquals(1, fetches); assertTrue(a.fetchRetryDelayMillis > 0)
        } finally { a.close() }
    }

    @Test fun diagnosticsOnlyExposeAllowlistedEventsAndOfflineRetries() = runBlocking {
        val api = SyntheticNetwork(); val a = runtime(api); val events = mutableListOf<String>()
        val previous = BackgroundDiagnostics.sink
        BackgroundDiagnostics.sink = { events.add(it); Unit }
        try {
            a.use { a.create(it, "alice") }; api.offline = true
            assertEquals(ListenableWorker.Result.retry(), worker(a).doWork())
            assertEquals(listOf("WORK_START", "WORK_RETRY"), events)
            assertTrue(a.canAutoSync)
            api.offline = false; events.clear()
            assertEquals(ListenableWorker.Result.success(), worker(a).doWork())
            assertEquals(listOf("WORK_START", "WORK_SUCCESS"), events)
            assertEquals(1, api.registrations)
        } finally { BackgroundDiagnostics.sink = previous; a.close() }
    }

    @Test fun cancellationStopsWorkerAndReleasesOwnerWithoutChangingIdentity() = runBlocking {
        val api = SyntheticNetwork(); val entered = CompletableDeferred<Unit>(); var block = true
        val a = runtime(GhostCloakTransport { request ->
            if (block && NetworkCodec.decode<ApiRequest>(request.body) is ApiRequest.Fetch) {
                entered.complete(Unit); awaitCancellation()
            }
            api.execute(request)
        })
        val events = mutableListOf<String>(); val previous = BackgroundDiagnostics.sink
        BackgroundDiagnostics.sink = { events.add(it); Unit }
        try {
            a.use { a.create(it, "alice") }; val identity = a.use { it.open()!! }
            val job = launch { worker(a).doWork() }; entered.await(); job.cancelAndJoin()
            assertEquals(listOf("WORK_START", "WORK_STOP"), events)
            block = false
            assertEquals(ListenableWorker.Result.success(), worker(a).doWork())
            assertEquals(identity.deviceId, a.use { it.open()!!.deviceId })
            assertEquals(1, api.registrations)
        } finally { BackgroundDiagnostics.sink = previous; a.close() }
    }

    @Test fun backgroundBudgetDefersBacklogWithoutChangingAcceptanceOrAck() = runBlocking {
        val api = SyntheticNetwork(); var fetches = 0
        val a = runtime(api)
        val b = runtime(GhostCloakTransport { request ->
            if (NetworkCodec.decode<ApiRequest>(request.body) is ApiRequest.Fetch) fetches++
            api.execute(request)
        })
        try {
            a.use { a.create(it, "alice") }; b.use { b.create(it, "bob") }; a.use { a.addNetwork("bob", it) }
            val aid = a.use { it.open()!!.deviceId }; val bid = b.use { it.open()!!.deviceId }
            a.use { a.send(it, bid, "Synthetic replay backlog") }
            val delivery = api.mailbox.values.single().second
            repeat(NetworkLimits.BATCH * 5) {
                val id = RandomIdentifiers.create()
                api.mailbox[id] = bid to Delivery(id, delivery.encryptedEnvelope, delivery.receivedAt, delivery.expiresAt)
            }
            assertEquals(ListenableWorker.Result.retry(), worker(b).doWork())
            assertEquals(4, fetches)
            assertEquals(1, b.use { it.messages(aid).size })
            assertTrue(api.mailbox.isNotEmpty())
            b.use { b.syncNetwork(it) }
            assertTrue(api.mailbox.isEmpty())
            assertEquals(1, b.use { it.messages(aid).size })
        } finally { a.close(); b.close() }
    }

    @Test fun manifestHasOptionalNotificationsButNoForegroundServiceOrDirectBootWorker() {
        val info = context.packageManager.getPackageInfo(context.packageName,
            android.content.pm.PackageManager.GET_PERMISSIONS or android.content.pm.PackageManager.GET_SERVICES)
        val permissions = info.requestedPermissions.orEmpty().toSet()
        assertTrue("android.permission.POST_NOTIFICATIONS" in permissions)
        assertFalse(permissions.any { "FOREGROUND_SERVICE" in it || "EXACT_ALARM" in it })
        val services = info.services.orEmpty()
        assertTrue(services.any { it.name == "androidx.work.impl.background.systemjob.SystemJobService" && !it.directBootAware })
        assertFalse(services.any { "SystemForegroundService" in it.name || "SystemAlarmService" in it.name })
    }
}
