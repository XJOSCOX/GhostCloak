package org.ghostcloak.app.application

import android.app.Application
import kotlinx.coroutines.launch

/** A single store/engine owner per process; activity recreation never opens a second engine. */
class GhostApplication : Application(), androidx.work.Configuration.Provider {
    private val mediaOwner = lazy { org.ghostcloak.app.attachments.AttachmentPresentation(this) }
    val media get() = mediaOwner.value
    val appLock: org.ghostcloak.app.access.AppLockController by lazy {
        org.ghostcloak.app.access.AppLockController(object : org.ghostcloak.app.access.LockPersistence {
            override suspend fun read() = runtime.readAppLock()
            override suspend fun write(bytes: ByteArray) = runtime.writeAppLock(bytes)
        }, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate),
            android.os.SystemClock::elapsedRealtime,
            { android.provider.Settings.Global.getInt(contentResolver, android.provider.Settings.Global.BOOT_COUNT, 0) })
    }
    val runtime: AppRuntime by lazy { AppRuntime(this, backgroundEligibility = { BackgroundSyncSchedule.reconcile(this, it) },
        notifications = AndroidLocalNotifications(this), attachmentAccess = { appLock.state.value.canShowContent }) }
    override val workManagerConfiguration get() = androidx.work.Configuration.Builder()
        // Silence WorkManager's own logs; only our allowlisted debug events are emitted.
        .setMinimumLoggingLevel(Int.MAX_VALUE).build()
    private val backgroundScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    override fun onCreate() {
        super.onCreate()
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()+kotlinx.coroutines.Dispatchers.Main.immediate).launch {
            appLock.state.collect { if (!it.canShowContent) {
                runtime.revokeAttachmentAccess()
                if (mediaOwner.isInitialized()) media.locked()
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
        backgroundScope.launch {
            try { runtime.dismissNotifications() }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { } // No private notification diagnostics.
            finally { finished() }
        }
    }
}
