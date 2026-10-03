package org.ghostcloak.app.access

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Ordered process-local shutdown. Only the journal survives restart. No key/file destruction.
 * Any failed owner leaves the fence closed and completion false. Retry reruns idempotent owners.
 * KEY_DESTRUCTION_PENDING is a checkpoint, never authority to invoke a nonexistent wipe engine. */
class LocalOperationCoordinator internal constructor(
    private val gate: LocalOperationGate,
    private val stopOwners: suspend () -> Unit,
) {
    private val mutex = Mutex()
    @Volatile var ownersQuiesced = false
        private set
    suspend fun resume() = mutex.withLock {
        check(gate.blocked)
        if (ownersQuiesced) return@withLock
        gate.advanceQuiesce()
        stopOwners()
        gate.drain()
        gate.quiesced()
        ownersQuiesced = true
    }
}
