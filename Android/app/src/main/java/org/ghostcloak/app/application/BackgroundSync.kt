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
        val gate = (context.applicationContext as? GhostApplication)?.localOperationGate
        if (gate?.blocked == true) return
        if (eligible) {
            if (gate != null) gate.access { manager.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request()) }
            else manager.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request())
        }
        else manager.cancelUniqueWork(NAME)
    }
    suspend fun cancelForLocalOperation(context: Context) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val manager=WorkManager.getInstance(context)
        manager.cancelUniqueWork(NAME).result.get()
        check(manager.getWorkInfosForUniqueWork(NAME).get().none { !it.state.isFinished })
    }

}

class BackgroundSyncWorker private constructor(context: Context, params: WorkerParameters, runtimeFactory: () -> AppRuntime) : CoroutineWorker(context, params) {
    internal val runtime by lazy(runtimeFactory)
    internal constructor(context: Context, params: WorkerParameters, runtime: AppRuntime) : this(context, params, { runtime })
    constructor(context: Context, params: WorkerParameters) : this(context, params, { (context.applicationContext as GhostApplication).runtime })
    override suspend fun doWork(): Result {
        if ((applicationContext.applicationContext as? GhostApplication)?.localOperationGate?.blocked == true || runtime.operationBlocked) return Result.success()
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
