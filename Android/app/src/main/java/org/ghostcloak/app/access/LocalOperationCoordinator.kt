package org.ghostcloak.app.access

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** No engine by default: release and ordinary debug remain non-destructive until review.
 * Each new process re-quiesces before resuming the independently journaled destructive state. */
internal interface LocalDestruction {
    suspend fun destroyKeys()
    suspend fun cleanupStorage()
    suspend fun verifyFresh()
}
internal enum class RecoveryPhase { QUIESCE, JOURNAL, KEYSTORE, STORAGE, FINALIZATION }
class LocalOperationCoordinator internal constructor(
    private val gate: LocalOperationGate,
    private val destruction: LocalDestruction? = null,
    private val prepareFreshOwners: suspend () -> Unit = {},
    private val stopOwners: suspend () -> Unit,
) {
    private val mutex = Mutex()
    @Volatile internal var recoveryPhase = RecoveryPhase.JOURNAL
        private set
    @Volatile var ownersQuiesced = false
        private set
    suspend fun resume() = mutex.withLock {
        if (!gate.blocked) return@withLock
        if (!ownersQuiesced) {
            recoveryPhase = RecoveryPhase.JOURNAL
            gate.advanceQuiesce()
            recoveryPhase = RecoveryPhase.QUIESCE
            stopOwners()
            gate.drain()
            recoveryPhase = RecoveryPhase.JOURNAL
            gate.quiesced()
            ownersQuiesced = true
        }
        val engine=destruction ?: return@withLock
        if(gate.state.value==LocalOperationState.CORRUPT) return@withLock
        if(gate.state.value==LocalOperationState.KEY_DESTRUCTION_PENDING) {
            recoveryPhase = RecoveryPhase.KEYSTORE
            engine.destroyKeys()
            recoveryPhase = RecoveryPhase.JOURNAL
            gate.advanceDestruction(LocalOperationState.KEY_DESTRUCTION_PENDING,LocalOperationState.KEY_DESTRUCTION_COMPLETE)
        }
        if(gate.state.value==LocalOperationState.KEY_DESTRUCTION_COMPLETE) {
            recoveryPhase = RecoveryPhase.JOURNAL
            gate.advanceDestruction(LocalOperationState.KEY_DESTRUCTION_COMPLETE,LocalOperationState.STORAGE_CLEANUP_PENDING)
        }
        if(gate.state.value==LocalOperationState.STORAGE_CLEANUP_PENDING) {
            recoveryPhase = RecoveryPhase.STORAGE
            engine.cleanupStorage()
            recoveryPhase = RecoveryPhase.JOURNAL
            gate.advanceDestruction(LocalOperationState.STORAGE_CLEANUP_PENDING,LocalOperationState.FINALIZING)
        }
        if(gate.state.value==LocalOperationState.FINALIZING) {
            recoveryPhase = RecoveryPhase.FINALIZATION
            engine.verifyFresh()
            recoveryPhase = RecoveryPhase.JOURNAL
            gate.advanceDestruction(LocalOperationState.FINALIZING,LocalOperationState.COMPLETE)
        }
        if(gate.state.value==LocalOperationState.COMPLETE) {
            recoveryPhase = RecoveryPhase.FINALIZATION
            engine.verifyFresh() // Includes restart after COMPLETE, never trusts the marker alone.
            prepareFreshOwners()
            recoveryPhase = RecoveryPhase.JOURNAL
            gate.reopenVerifiedFresh()
            ownersQuiesced=false // A later Safe Exit must quiesce the newly created owners again.
        }
    }
}
