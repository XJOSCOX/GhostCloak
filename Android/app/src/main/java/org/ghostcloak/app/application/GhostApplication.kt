package org.ghostcloak.app.application

import kotlinx.coroutines.flow.asStateFlow

import android.app.Application
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin

/** A single store/engine owner per process; activity recreation never opens a second engine. */
class GhostApplication : Application(), androidx.work.Configuration.Provider, org.ghostcloak.storage.LocalStateAccessOwner {
    val localOperationGate by lazy { org.ghostcloak.app.access.LocalOperationGate(org.ghostcloak.app.access.DurableLocalOperationJournal(this)) }
    override val localStateAccess get() = localOperationGate
    private var lockScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)
    private var lifecycleScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)
    private val recoveryScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    private val uiOwners = mutableSetOf<suspend () -> Unit>()
    internal fun registerUiOwner(stop: suspend () -> Unit): () -> Unit = localOperationGate.access {
        synchronized(uiOwners) { uiOwners.add(stop) }
        val unregister: () -> Unit = { synchronized(uiOwners) { uiOwners.remove(stop) }; Unit }
        unregister
    }
    val localOperationCoordinator by lazy { org.ghostcloak.app.access.LocalOperationCoordinator(localOperationGate,
        destruction=if(org.ghostcloak.app.BuildConfig.EMERGENCY_WIPE_DESTRUCTIVE_READY) org.ghostcloak.app.access.AndroidLocalDestruction(this) else null,
        prepareFreshOwners={prepareFreshOwners()}) {
        BackgroundSyncSchedule.cancelForLocalOperation(this)
        getSystemService(android.app.NotificationManager::class.java).cancelAll()
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
            synchronized(uiOwners) { uiOwners.toList() }.forEach { it() }
            synchronized(uiOwners) { uiOwners.clear() }
            if (lockOwner.isInitialized()) lockOwner.value.quiesce()
            if (mediaOwner.isInitialized()) mediaOwner.value.quiesce()
        }
        backgroundScope.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin()
        lifecycleScope.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin()
        localOperationGate.drain()
        if (runtimeOwner.isInitialized()) runtimeOwner.value.quiesce()
    } }
    internal fun resumeLocalOperation() { recoveryScope.launch { try { localOperationCoordinator.resume() }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { /* Fence stays closed; no reset or private diagnostics. */ } } }
    private fun newMediaOwner() = lazy { org.ghostcloak.app.attachments.AttachmentPresentation(this) }
    private var mediaOwner = newMediaOwner()
    val media get() = localOperationGate.access { mediaOwner.value }
    private fun newLockOwner(): Lazy<org.ghostcloak.app.access.AppLockController> = lazy {
        org.ghostcloak.app.access.AppLockController(object : org.ghostcloak.app.access.LockPersistence {
            override suspend fun read() = runtime.readAppLock()
            override suspend fun write(bytes: ByteArray) = runtime.writeAppLock(bytes)
        }, lockScope,
            android.os.SystemClock::elapsedRealtime,
            { android.provider.Settings.Global.getInt(contentResolver, android.provider.Settings.Global.BOOT_COUNT, 0) },
            armEmergency = { localOperationGate.armAfterCredential() })
    }
    private var lockOwner = newLockOwner()
    val appLock: org.ghostcloak.app.access.AppLockController get() = localOperationGate.access { lockOwner.value }
    private fun newRuntimeOwner(): Lazy<AppRuntime> = lazy { AppRuntime(this, backgroundEligibility = { BackgroundSyncSchedule.reconcile(this, it) },
        notifications = AndroidLocalNotifications(this), attachmentAccess = { appLock.state.value.canShowContent }) }
    private var runtimeOwner = newRuntimeOwner()
    internal val sensitiveOwnersInitialized get() = runtimeOwner.isInitialized() || lockOwner.isInitialized() || mediaOwner.isInitialized()
    val runtime: AppRuntime get() = localOperationGate.access { runtimeOwner.value }
    override val workManagerConfiguration get() = androidx.work.Configuration.Builder()
        // Silence WorkManager's own logs; only our allowlisted debug events are emitted.
        .setMinimumLoggingLevel(Int.MAX_VALUE).build()
    private var backgroundScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    private val freshRevision=kotlinx.coroutines.flow.MutableStateFlow(0)
    internal val freshGeneration=freshRevision.asStateFlow()
    private var normalGeneration=-1
    private suspend fun prepareFreshOwners() = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
        check(localOperationGate.blocked && synchronized(uiOwners) {uiOwners.isEmpty()})
        lockScope=kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()+kotlinx.coroutines.Dispatchers.Main.immediate)
        lifecycleScope=kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()+kotlinx.coroutines.Dispatchers.Main.immediate)
        backgroundScope=kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()+kotlinx.coroutines.Dispatchers.IO)
        lockOwner=newLockOwner(); mediaOwner=newMediaOwner(); runtimeOwner=newRuntimeOwner()
        freshRevision.value++ // New lazy owners; do not open them before journal reset.
    }
    override fun onCreate() {
        super.onCreate()
        recoveryScope.launch {localOperationGate.state.collect {state->
            if(state==org.ghostcloak.app.access.LocalOperationState.NONE) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {startNormalOwners()}
            else if(state!=org.ghostcloak.app.access.LocalOperationState.CORRUPT && getSystemService(android.os.UserManager::class.java).isUserUnlocked) resumeLocalOperation()
        }}
    }
    private fun startNormalOwners() {
        if(localOperationGate.blocked || normalGeneration==freshRevision.value) return
        normalGeneration=freshRevision.value
        lifecycleScope.launch {
            appLock.state.collect { if (!it.canShowContent) {
                if (!localOperationGate.blocked) runtime.revokeAttachmentAccess()
                if (!localOperationGate.blocked && mediaOwner.isInitialized()) media.locked()
            } }
        }
        // No Direct Boot access; WorkManager itself handles OS-approved persistence/reboot.
        if (getSystemService(android.os.UserManager::class.java).isUserUnlocked) {
            backgroundScope.launchInitialization()
            // One local cleanup loop, no network/alarms/wake lock. Android may suspend it.
            backgroundScope.launch {
                while (true) {
                    try { runtime.reconcileLocalExpiry()
                        if (mediaOwner.isInitialized()) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { media.reconcileStored() }
                    }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (_: Exception) { } // Storage failures never expose message metadata.
                    kotlinx.coroutines.delay(1000)
                }
            }
        }
    }
    private fun kotlinx.coroutines.CoroutineScope.launchInitialization() = launch {
        try { runtime.initializeBackground() }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { BackgroundDiagnostics.emit(BackgroundEvent.WORK_SKIP) }
    }
    fun dismissNotifications(finished: () -> Unit) {
        if (localOperationGate.blocked) { finished(); return }
        backgroundScope.launch {
            try { runtime.dismissNotifications() }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { } // No private notification diagnostics.
            finally { finished() }
        }
    }
}
