package org.ghostcloak.app.access

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.ghostcloak.storage.LocalStateAccess

enum class LocalOperationState {
    NONE, ARMED, QUIESCING, KEY_DESTRUCTION_PENDING, KEY_DESTRUCTION_COMPLETE,
    STORAGE_CLEANUP_PENDING, FINALIZING, COMPLETE, CORRUPT
}

internal interface LocalOperationJournal {
    fun read(): LocalOperationState
    fun write(state: LocalOperationState)
}

/** A persistent fence, not a wipe engine. There is intentionally no completion/reset/clear API.
 * Synchronous opens/transactions and durable transitions share the same monitor.
 * Suspended operations are leased until their entire coroutine scope has drained. */
class LocalOperationGate internal constructor(private val journal: LocalOperationJournal) : LocalStateAccess {
    private val monitor = Any()
    private val mutable = MutableStateFlow(journal.read())
    val state = mutable.asStateFlow()
    val blocked get() = mutable.value != LocalOperationState.NONE
    private val jobs = mutableSetOf<Job>()

    override fun <T> access(block: () -> T): T = synchronized(monitor) {
        if (blocked) throw LocalOperationBlocked()
        block()
    }

    fun requireNormal() = access { Unit }

    suspend fun <T> operation(block: suspend () -> T): T = coroutineScope {
        val job = currentCoroutineContext()[Job]!!
        access { jobs.add(job) }
        job.invokeOnCompletion { synchronized(monitor) { jobs.remove(job) } }
        ensureActive(); block()
    }

    // Only a debug-source hook calls arm in this phase. No production caller or UI exists.
    internal fun armForSimulation() = synchronized(monitor) {
        if (mutable.value == LocalOperationState.NONE) persist(LocalOperationState.ARMED)
    }

    private fun persist(next: LocalOperationState) {
        // Fence even if a durable write fails. Restart reads unreadable/ambiguous state fail-closed.
        mutable.value = LocalOperationState.CORRUPT
        journal.write(next)
        mutable.value = next
    }

    internal fun advanceQuiesce() = synchronized(monitor) {
        when (mutable.value) {
            LocalOperationState.ARMED -> persist(LocalOperationState.QUIESCING)
            LocalOperationState.NONE -> error("local_operation_not_armed")
            else -> Unit // Corrupt/unknown state remains blocked; never invent wipe authorization.
        }
    }

    internal suspend fun drain() {
        check(blocked)
        val active = synchronized(monitor) { jobs.toList() }
        check(currentCoroutineContext()[Job] !in active) { "coordinator_must_run_outside_state_lease" }
        active.forEach { it.cancel(LocalOperationBlocked()) }
        active.joinAll()
        check(synchronized(monitor) { jobs.isEmpty() })
    }

    internal fun quiesced() = synchronized(monitor) {
        if (mutable.value == LocalOperationState.QUIESCING) persist(LocalOperationState.KEY_DESTRUCTION_PENDING)
    }
}

class LocalOperationBlocked : CancellationException("local_operation_blocked")
