package org.ghostcloak.app.access

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SafeExitRecoveryTest {
    private class Journal(var value: LocalOperationState) : LocalOperationJournal {
        var failAfterWrite: LocalOperationState?=null
        override fun read() = value
        override fun write(state: LocalOperationState) { value = state; if(failAfterWrite==state) error("fixture") }
        override fun clearCompleted() { check(value == LocalOperationState.COMPLETE); value = LocalOperationState.NONE }
    }
    private class Engine : LocalDestruction {
        var failKey = false
        var keyCalls = 0
        var storageCalls = 0
        override suspend fun destroyKeys() { keyCalls++; if (failKey) error("fixture"); }
        override suspend fun cleanupStorage() { storageCalls++ }
        override suspend fun verifyFresh() = Unit
    }

    @Test fun persistedDestructiveStateProcessRecreationAndRetryReachFreshOnboarding() = runBlocking {
        val journal = Journal(LocalOperationState.KEY_DESTRUCTION_PENDING)
        val firstProcessGate = LocalOperationGate(journal)
        assertTrue(firstProcessGate.blocked)
        // Discard the first process entirely; the new process reconstructs gate and coordinator.
        val restartedGate = LocalOperationGate(journal)
        val engine = Engine()
        val events = mutableListOf<SafeExitRecoveryEvent>()
        var coordinatorCreations = 0
        var onboarding = false
        val recovery = SafeExitRecovery(restartedGate,journal,{
            coordinatorCreations++
            LocalOperationCoordinator(restartedGate,engine,{onboarding=true}) {}
        },events::add,{})
        assertEquals(SafeExitRetryResult.COMPLETED,recovery.resume())
        assertEquals(1,coordinatorCreations)
        assertTrue(onboarding)
        assertEquals(LocalOperationState.NONE,journal.read())
        assertFalse(restartedGate.blocked)
        assertTrue(events.contains(SafeExitRecoveryEvent.SAFE_EXIT_RETRY_COORDINATOR_CREATED))
        assertTrue(events.contains(SafeExitRecoveryEvent.SAFE_EXIT_RETRY_COMPLETED))
    }

    @Test fun keystoreFailureStaysFencedAndSecondRetryCompletes() = runBlocking {
        val journal = Journal(LocalOperationState.KEY_DESTRUCTION_PENDING)
        val gate = LocalOperationGate(journal)
        val engine = Engine().apply { failKey=true }
        var coordinatorCreations=0
        var quiesces=0
        val events = mutableListOf<SafeExitRecoveryEvent>()
        val recovery = SafeExitRecovery(gate,journal,{
            coordinatorCreations++
            LocalOperationCoordinator(gate,engine) { quiesces++ }
        },events::add,{})
        assertEquals(SafeExitRetryResult.FAILED,recovery.resume())
        assertEquals(LocalOperationState.KEY_DESTRUCTION_PENDING,journal.read())
        assertTrue(gate.blocked)
        assertTrue(events.contains(SafeExitRecoveryEvent.SAFE_EXIT_RETRY_FAILED_KEYSTORE))
        engine.failKey=false
        assertEquals(SafeExitRetryResult.COMPLETED,recovery.resume())
        assertEquals(2,coordinatorCreations)
        assertEquals(2,quiesces)
        assertEquals(2,engine.keyCalls)
        assertFalse(gate.blocked)
    }

    @Test fun ambiguousInMemoryStateNeverUsesDurableMarkerToBypassFence() = runBlocking {
        val journal = Journal(LocalOperationState.KEY_DESTRUCTION_PENDING)
        val gate = LocalOperationGate(journal)
        var calls=0
        val events = mutableListOf<SafeExitRecoveryEvent>()
        journal.value=LocalOperationState.CORRUPT
        val recovery = SafeExitRecovery(gate,journal,{
            calls++
            LocalOperationCoordinator(gate,Engine()) {}
        },events::add,{})
        assertEquals(SafeExitRetryResult.BLOCKED,recovery.resume())
        assertEquals(0,calls)
        assertTrue(gate.blocked)
        assertTrue(events.contains(SafeExitRecoveryEvent.SAFE_EXIT_RETRY_FAILED_JOURNAL))
    }

    @Test fun retryResumesVerifiedBlockedJournalAfterPostCommitWriteFailure() = runBlocking {
        val journal=Journal(LocalOperationState.KEY_DESTRUCTION_PENDING).apply {
            failAfterWrite=LocalOperationState.KEY_DESTRUCTION_COMPLETE
        }
        val gate=LocalOperationGate(journal)
        try {
            gate.advanceDestruction(LocalOperationState.KEY_DESTRUCTION_PENDING,LocalOperationState.KEY_DESTRUCTION_COMPLETE)
            fail("write failure missed")
        } catch (_: IllegalStateException) { }
        assertEquals(LocalOperationState.CORRUPT,gate.state.value)
        assertEquals(LocalOperationState.KEY_DESTRUCTION_COMPLETE,journal.read())
        journal.failAfterWrite=null
        val engine=Engine()
        val result=SafeExitRecovery(gate,journal,{LocalOperationCoordinator(gate,engine) {}},{},{}).resume()
        assertEquals(SafeExitRetryResult.COMPLETED,result)
        assertEquals(0,engine.keyCalls) // Already durably past key destruction.
        assertEquals(1,engine.storageCalls)
        assertEquals(LocalOperationState.NONE,journal.read())
        assertFalse(gate.blocked)
    }

    @Test fun retryNeverTreatsAbsentDurableJournalAsPermissionToOpenCorruptGate() = runBlocking {
        val journal=Journal(LocalOperationState.CORRUPT)
        val gate=LocalOperationGate(journal)
        journal.value=LocalOperationState.NONE
        var coordinatorCreated=false
        val result=SafeExitRecovery(gate,journal,{
            coordinatorCreated=true
            LocalOperationCoordinator(gate,Engine()) {}
        },{},{}).resume()
        assertEquals(SafeExitRetryResult.BLOCKED,result)
        assertFalse(coordinatorCreated)
        assertEquals(LocalOperationState.CORRUPT,gate.state.value)
    }
}
