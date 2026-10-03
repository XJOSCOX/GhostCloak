package org.ghostcloak.app.access

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Fixed release-safe categories only. Never pass an exception or user data to Logcat. */
internal enum class SafeExitRecoveryEvent {
    SAFE_EXIT_RECOVERY_SCREEN,
    SAFE_EXIT_RETRY_TAPPED,
    SAFE_EXIT_RETRY_COORDINATOR_CREATED,
    SAFE_EXIT_RETRY_STARTED,
    SAFE_EXIT_RETRY_FAILED_COORDINATOR,
    SAFE_EXIT_RETRY_FAILED_JOURNAL,
    SAFE_EXIT_RETRY_FAILED_QUIESCE,
    SAFE_EXIT_RETRY_FAILED_KEYSTORE,
    SAFE_EXIT_RETRY_FAILED_STORAGE,
    SAFE_EXIT_RETRY_FAILED_FINALIZATION,
    SAFE_EXIT_RETRY_COMPLETED,
}

internal object SafeExitRecoveryDiagnostics {
    private const val TAG = "GhostCloakSafeExit"
    fun emit(event: SafeExitRecoveryEvent) { Log.i(TAG, event.name) }
    fun state(state: LocalOperationState) { Log.i(TAG, "SAFE_EXIT_RECOVERY_STATE=${state.name}") }
}

internal enum class SafeExitRetryResult { COMPLETED, BLOCKED, FAILED }

/** One process owner serializes startup recovery and button retries. A retry reads the durable
 * journal before resuming; an ambiguous or diverging state remains fenced. */
internal class SafeExitRecovery(
    private val gate: LocalOperationGate,
    private val journal: LocalOperationJournal,
    private val coordinator: () -> LocalOperationCoordinator,
    private val emit: (SafeExitRecoveryEvent) -> Unit = SafeExitRecoveryDiagnostics::emit,
    private val emitState: (LocalOperationState) -> Unit = SafeExitRecoveryDiagnostics::state,
) {
    private val mutex = Mutex()

    suspend fun resume(): SafeExitRetryResult = mutex.withLock {
        val durable = gate.readBlockedForRecovery()
        emitState(durable)
        if (durable == LocalOperationState.CORRUPT || durable == LocalOperationState.NONE ||
            gate.state.value != durable) {
            emit(SafeExitRecoveryEvent.SAFE_EXIT_RETRY_FAILED_JOURNAL)
            return@withLock SafeExitRetryResult.BLOCKED
        }
        val owner = try { coordinator() }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            emit(SafeExitRecoveryEvent.SAFE_EXIT_RETRY_FAILED_COORDINATOR)
            return@withLock SafeExitRetryResult.FAILED
        }
        emit(SafeExitRecoveryEvent.SAFE_EXIT_RETRY_COORDINATOR_CREATED)
        emit(SafeExitRecoveryEvent.SAFE_EXIT_RETRY_STARTED)
        try {
            owner.resume()
            if (gate.state.value == LocalOperationState.NONE && journal.read() == LocalOperationState.NONE) {
                emit(SafeExitRecoveryEvent.SAFE_EXIT_RETRY_COMPLETED)
                SafeExitRetryResult.COMPLETED
            } else {
                emit(SafeExitRecoveryEvent.SAFE_EXIT_RETRY_FAILED_FINALIZATION)
                SafeExitRetryResult.BLOCKED
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emit(when (owner.recoveryPhase) {
                RecoveryPhase.QUIESCE -> SafeExitRecoveryEvent.SAFE_EXIT_RETRY_FAILED_QUIESCE
                RecoveryPhase.JOURNAL -> SafeExitRecoveryEvent.SAFE_EXIT_RETRY_FAILED_JOURNAL
                RecoveryPhase.KEYSTORE -> SafeExitRecoveryEvent.SAFE_EXIT_RETRY_FAILED_KEYSTORE
                RecoveryPhase.STORAGE -> SafeExitRecoveryEvent.SAFE_EXIT_RETRY_FAILED_STORAGE
                RecoveryPhase.FINALIZATION -> SafeExitRecoveryEvent.SAFE_EXIT_RETRY_FAILED_FINALIZATION
            })
            SafeExitRetryResult.FAILED
        }
    }
}
