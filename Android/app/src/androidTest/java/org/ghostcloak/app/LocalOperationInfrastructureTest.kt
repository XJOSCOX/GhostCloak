package org.ghostcloak.app

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.*
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.*
import org.ghostcloak.app.access.*
import org.ghostcloak.app.application.*
import org.ghostcloak.identity.RandomIdentifiers
import org.ghostcloak.storage.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.KeyStore

/** Isolated synthetic endpoints only. Never arms the real Application's gate. No data/key deletion. */
class LocalOperationInfrastructureTest {
    private val target get() = InstrumentationRegistry.getInstrumentation().targetContext
    private class Fixture(context: Context) : ContextWrapper(context), LocalStateAccessOwner {
        private val directory=File(context.noBackupFilesDir, "operation-fixture-${RandomIdentifiers.create()}").apply { mkdirs() }
        override fun getNoBackupFilesDir() = directory
        override fun getApplicationContext(): Context = this
        val endpoint=RandomIdentifiers.create()
        val journal=DurableLocalOperationJournal(this)
        var gate=LocalOperationGate(journal)
        override val localStateAccess get() = gate
        fun restart() { gate=LocalOperationGate(journal) }
        fun runtime(api: SyntheticNetwork = SyntheticNetwork()) = AppRuntime(this,"https://fixture.invalid",endpoint,api,operationGate=gate)
    }
    @Test fun journalIsIndependentVersionedBoundedAndDurableAcrossRecreation() {
        val fixture=Fixture(target)
        assertEquals(LocalOperationState.NONE,fixture.journal.read())
        LocalOperationTestHooks.arm(fixture.gate)
        fixture.restart()
        assertEquals(LocalOperationState.ARMED,fixture.gate.state.value)
        assertEquals("GCLO1:ARMED\n",File(fixture.noBackupFilesDir,"local-operation.v1").readText())
        assertFalse(File(fixture.noBackupFilesDir,"${fixture.endpoint}.db").exists())
    }
    @Test fun everyRecoveryCheckpointBlocksOpeningIncludingComplete() = runBlocking {
        val fixture=Fixture(target)
        for(state in LocalOperationState.entries.filter { it !in setOf(LocalOperationState.NONE,LocalOperationState.CORRUPT) }) {
            fixture.journal.write(state); fixture.restart()
            val runtime=fixture.runtime()
            try { runtime.use { fail("identity/session loaded") }; fail("opened") } catch (_: LocalOperationBlocked) {}
            assertFalse(File(fixture.noBackupFilesDir,"${fixture.endpoint}.db").exists())
            assertFalse(File(fixture.noBackupFilesDir,"${fixture.endpoint}-attachments").exists())
            runtime.close()
        }
    }
    @Test fun truncatedUnknownVersionOversizedAndPopulatedNoneFailClosed() {
        val fixture=Fixture(target)
        val file=File(fixture.noBackupFilesDir,"local-operation.v1")
        listOf("", "GCLO1:ARM", "GCLO9:ARMED\n", "GCLO1:NONE\n", "x".repeat(1024)).forEach {
            file.writeText(it); fixture.restart()
            assertEquals(LocalOperationState.CORRUPT,fixture.gate.state.value)
            try { EncryptedEndpointStore.open(fixture,"local"); fail("opened") } catch (_: LocalOperationBlocked) {}
        }
    }
    @Test fun interruptedFirstAtomicWriteFailsClosed() {
        val fixture=Fixture(target)
        File(fixture.noBackupFilesDir,"local-operation.v1.new").writeText("GCLO1:AR")
        fixture.restart(); assertEquals(LocalOperationState.CORRUPT,fixture.gate.state.value)
    }
    @Test fun quiescingRestartResumesAndNeverClearsJournal() = runBlocking {
        val fixture=Fixture(target)
        fixture.journal.write(LocalOperationState.QUIESCING); fixture.restart()
        val coordinator=LocalOperationCoordinator(fixture.gate) {}
        coordinator.resume(); coordinator.resume(); fixture.restart()
        assertEquals(LocalOperationState.KEY_DESTRUCTION_PENDING,fixture.gate.state.value)
        assertTrue(coordinator.ownersQuiesced); assertTrue(fixture.gate.blocked)
    }
    @Test fun existingStoreIdentityAndKeysSurviveNonDeletingQuiesce() = runBlocking {
        val fixture=Fixture(target); val runtime=fixture.runtime()
        // Offline simulator identity: no server registration or backend access.
        val identity=runtime.use { it.create("Synthetic") }
        val aliases=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.aliases().toList().toSet()
        val database=File(fixture.noBackupFilesDir,"${fixture.endpoint}.db")
        val wrapped=File(fixture.noBackupFilesDir,"${fixture.endpoint}.wrapped").readBytes()
        LocalOperationTestHooks.arm(fixture.gate)
        val coordinator=LocalOperationCoordinator(fixture.gate) { runtime.quiesce() }
        coordinator.resume()
        assertTrue(database.exists()); assertArrayEquals(wrapped,File(fixture.noBackupFilesDir,"${fixture.endpoint}.wrapped").readBytes())
        assertEquals(aliases,KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.aliases().toList().toSet())
        // A separate NONE fixture gate is test-only, never a production reset/clear operation.
        val inspection=object : ContextWrapper(fixture) { override fun getApplicationContext(): Context = target }
        withContext(Dispatchers.IO) { EncryptedEndpointStore.open(inspection,fixture.endpoint).use { records ->
            assertEquals(identity.deviceId,records.transaction { records.read("local/device")!!.decodeToString() })
        } }
    }
    @Test fun lateDirectDatabaseOpenIsRejectedWithoutCreatingFiles() = runBlocking {
        val fixture=Fixture(target)
        LocalOperationTestHooks.arm(fixture.gate)
        repeat(20) {
            withContext(Dispatchers.IO) { try { EncryptedEndpointStore.open(fixture,"late"); fail("opened") } catch (_: LocalOperationBlocked) {} }
        }
        assertFalse(File(fixture.noBackupFilesDir,"late.wrapped").exists())
        assertFalse(File(fixture.noBackupFilesDir,"late.db").exists())
    }
    @Test fun alreadyOpenRecordsRejectReadsAndDeviceCredentialUseAfterArm() = runBlocking {
        val fixture=Fixture(target)
        val records=withContext(Dispatchers.IO) { EncryptedEndpointStore.open(fixture,RandomIdentifiers.create()) }
        try {
            LocalOperationTestHooks.arm(fixture.gate)
            withContext(Dispatchers.IO) {
                try { records.transaction { records.read("local/device") }; fail("read") } catch (_: LocalOperationBlocked) {}
                try { KeystoreDeviceAuth(fixture.gate).publicKey("synthetic-unused",false); fail("key loaded") }
                catch (_: org.ghostcloak.crypto.EndpointStorageFailure) {}
            }
        } finally { records.close() }
    }
    @Test fun workerRaceSkipsBeforeDatabaseIdentityNetworkAndNotification() = runBlocking {
        val fixture=Fixture(target); val api=SyntheticNetwork(); val runtime=fixture.runtime(api)
        val worker=TestListenableWorkerBuilder<BackgroundSyncWorker>(target).setWorkerFactory(object : WorkerFactory() {
            override fun createWorker(appContext:Context,workerClassName:String,workerParameters:WorkerParameters) = BackgroundSyncWorker(appContext,workerParameters,runtime)
        }).build()
        LocalOperationTestHooks.arm(fixture.gate)
        assertEquals(ListenableWorker.Result.success(),worker.doWork())
        assertEquals(0,api.requests); assertEquals(0,api.registrations)
        assertFalse(File(fixture.noBackupFilesDir,"${fixture.endpoint}.db").exists())
    }
    @Test fun centralWorkCancellationAcknowledgesTerminalState() = runBlocking {
        val manager=WorkManager.getInstance(target)
        manager.enqueueUniquePeriodicWork(BackgroundSyncSchedule.NAME,ExistingPeriodicWorkPolicy.KEEP,BackgroundSyncSchedule.request()).result.get()
        BackgroundSyncSchedule.cancelForLocalOperation(target)
        assertTrue(manager.getWorkInfosForUniqueWork(BackgroundSyncSchedule.NAME).get().all { it.state.isFinished })
    }
    @Test fun activeRuntimeOperationIsJoinedBeforeStoreCloses() = runBlocking {
        val fixture=Fixture(target); val runtime=fixture.runtime(); val entered=CompletableDeferred<Unit>(); var released=false
        val active=launch(Dispatchers.IO) { runtime.use { try { entered.complete(Unit); awaitCancellation() } finally { released=true } } }
        entered.await(); LocalOperationTestHooks.arm(fixture.gate)
        LocalOperationCoordinator(fixture.gate) { runtime.quiesce() }.resume()
        assertTrue(released); assertTrue(active.isCompleted)
        try { runtime.use { fail("reopened") }; fail("allowed") } catch (_: LocalOperationBlocked) {}
    }
    @Test fun armedAccountBlocksSendSyncRenewalAndPublicationWithoutAuthMutation() = runBlocking {
        val fixture=Fixture(target); val api=SyntheticNetwork(); val runtime=fixture.runtime(api)
        val service=runtime.use { runtime.create(it,"synthetic"); it }
        val requests=api.requests; val logins=api.logins; val registered=api.registrations
        LocalOperationTestHooks.arm(fixture.gate)
        val attempts:List<suspend () -> Unit> = listOf(
            { runtime.send(service,RandomIdentifiers.create(),"Synthetic") },
            { runtime.syncNetwork(service) }, { runtime.publishNetwork() },
            { runtime.connectNetwork(service) })
        attempts.forEach { try { it(); fail("admitted") } catch (_: LocalOperationBlocked) {} }
        assertEquals(requests,api.requests); assertEquals(logins,api.logins); assertEquals(registered,api.registrations)
        assertEquals(NetworkStatus.CONNECTED,runtime.networkStatus)
        runtime.quiesce()
    }
    @Test fun recordTransactionAndDeviceCredentialCannotInvertFenceLockOrder() = runBlocking {
        val fixture=Fixture(target)
        val records=withContext(Dispatchers.IO) { EncryptedEndpointStore.open(fixture,fixture.endpoint) }
        val attempted=java.util.concurrent.CountDownLatch(1)
        var signal=false
        val auth=KeystoreDeviceAuth(object : LocalStateAccess {
            override fun <T> access(block: () -> T): T {
                if(signal) attempted.countDown()
                return fixture.gate.access(block)
            }
        })
        val alias="ghostcloak.auth.test-${RandomIdentifiers.create()}"
        val expected=withContext(Dispatchers.IO) { auth.publicKey(alias,true) }
        signal=true
        val entered=CompletableDeferred<Unit>()
        try {
            val transaction=async(Dispatchers.IO) { records.transaction {
                entered.complete(Unit)
                check(attempted.await(5,java.util.concurrent.TimeUnit.SECONDS))
                auth.publicKey(alias,false)
            } }
            entered.await()
            val credential=async(Dispatchers.IO) { auth.publicKey(alias,false) }
            assertArrayEquals(expected,transaction.await()); assertArrayEquals(expected,credential.await())
        } finally { records.close() }
    }
}
