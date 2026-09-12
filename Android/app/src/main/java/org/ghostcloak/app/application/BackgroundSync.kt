package org.ghostcloak.app.application

import android.content.Context
import android.os.UserManager
import androidx.work.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import org.ghostcloak.crypto.CryptoFailure
import org.ghostcloak.crypto.EndpointStorageFailure
import org.ghostcloak.protocol.ApiFailure
import java.util.concurrent.TimeUnit

internal class BackgroundDeferred : Exception()
enum class BackgroundResult { SKIP, SUCCESS, RETRY }
enum class BackgroundEvent { WORK_START, WORK_SKIP, WORK_SUCCESS, WORK_RETRY, WORK_STOP }

object BackgroundSyncSchedule {
    const val NAME = "ghostcloak-background-sync"
    fun request() = PeriodicWorkRequestBuilder<BackgroundSyncWorker>(30, TimeUnit.MINUTES, 15, TimeUnit.MINUTES)
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .setInitialDelay(30, TimeUnit.MINUTES)
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
        .build()

    fun reconcile(context: Context, eligible: Boolean) {
        val manager = WorkManager.getInstance(context)
        if (eligible) manager.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request())
        else manager.cancelUniqueWork(NAME)
    }
}

class BackgroundSyncWorker internal constructor(context: Context, params: WorkerParameters, internal val runtime: AppRuntime) : CoroutineWorker(context, params) {
    constructor(context: Context, params: WorkerParameters) : this(context, params, (context.applicationContext as GhostApplication).runtime)
    override suspend fun doWork(): Result {
        BackgroundDiagnostics.emit(BackgroundEvent.WORK_START)
        if (!applicationContext.getSystemService(UserManager::class.java).isUserUnlocked) {
            BackgroundDiagnostics.emit(BackgroundEvent.WORK_SKIP)
            return Result.success()
        }
        // The application owns the sole engine/store/network client. Never create one here.
        return try {
            when (runtime.backgroundSync()) {
                BackgroundResult.SKIP -> { BackgroundDiagnostics.emit(BackgroundEvent.WORK_SKIP); Result.success() }
                BackgroundResult.SUCCESS -> { BackgroundDiagnostics.emit(BackgroundEvent.WORK_SUCCESS); Result.success() }
                BackgroundResult.RETRY -> retry()
            }
        } catch (_: TimeoutCancellationException) { retry() }
        catch (e: CancellationException) { BackgroundDiagnostics.emit(BackgroundEvent.WORK_STOP); throw e }
        catch (_: BackgroundDeferred) { retry() }
        catch (e: ApiFailure) {
            if (e.status == 429 || e.status == 503 || e.status >= 500) retry()
            else { BackgroundDiagnostics.emit(BackgroundEvent.WORK_SKIP); Result.success() }
        } catch (_: EndpointStorageFailure) {
            BackgroundDiagnostics.emit(BackgroundEvent.WORK_SKIP); Result.success()
        } catch (_: CryptoFailure) {
            BackgroundDiagnostics.emit(BackgroundEvent.WORK_SKIP); Result.success()
        } catch (_: Exception) {
            // No throwable text/class, URL, work UUID or application data reaches diagnostics.
            BackgroundDiagnostics.emit(BackgroundEvent.WORK_SKIP); Result.success()
        }
    }
    private fun retry(): Result {
        BackgroundDiagnostics.emit(BackgroundEvent.WORK_RETRY)
        return Result.retry()
    }
}
