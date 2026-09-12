package org.ghostcloak.app.application

import android.app.Application
import kotlinx.coroutines.launch

/** A single store/engine owner per process; activity recreation never opens a second engine. */
class GhostApplication : Application(), androidx.work.Configuration.Provider {
    val appLock by lazy {
        org.ghostcloak.app.access.AppLockController(object : org.ghostcloak.app.access.LockPersistence {
            override suspend fun read() = runtime.readAppLock()
            override suspend fun write(bytes: ByteArray) = runtime.writeAppLock(bytes)
        }, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate),
            android.os.SystemClock::elapsedRealtime,
            { android.provider.Settings.Global.getInt(contentResolver, android.provider.Settings.Global.BOOT_COUNT, 0) })
    }
    val runtime by lazy { AppRuntime(this, backgroundEligibility = { BackgroundSyncSchedule.reconcile(this, it) },
        notifications = AndroidLocalNotifications(this)) }
    override val workManagerConfiguration get() = androidx.work.Configuration.Builder()
        // Silence WorkManager's own logs; only our allowlisted debug events are emitted.
        .setMinimumLoggingLevel(Int.MAX_VALUE).build()
    private val backgroundScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    override fun onCreate() {
        super.onCreate()
        // No Direct Boot access; WorkManager itself handles OS-approved persistence/reboot.
        if (getSystemService(android.os.UserManager::class.java).isUserUnlocked) {
            backgroundScope.launchInitialization()
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
