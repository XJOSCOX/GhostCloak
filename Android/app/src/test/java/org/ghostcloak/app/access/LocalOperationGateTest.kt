package org.ghostcloak.app.access

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LocalOperationGateTest {
    private class Journal(var value: LocalOperationState = LocalOperationState.NONE) : LocalOperationJournal {
        var writes = 0
        override fun read() = value
        override fun write(state: LocalOperationState) { value=state; writes++ }
    }
    @Test fun absentJournalPermitsNormalStartup() = runBlocking {
        val gate=LocalOperationGate(Journal())
        assertFalse(gate.blocked); assertEquals(42,gate.access { 42 }); assertEquals(7,gate.operation { 7 })
    }
    @Test fun everyPersistedStateIncludingCompleteAndCorruptionBlocks() {
        LocalOperationState.entries.filter { it != LocalOperationState.NONE }.forEach { state ->
            val gate=LocalOperationGate(Journal(state))
            assertTrue(gate.blocked)
            try { gate.access { fail("opened") }; fail("allowed") } catch (_: LocalOperationBlocked) {}
        }
    }
    @Test fun processRecreationResumesQuiescingWithoutReopening() = runBlocking {
        val journal=Journal(); val first=LocalOperationGate(journal)
        first.armForSimulation(); first.advanceQuiesce()
        val restored=LocalOperationGate(journal); var stops=0
        LocalOperationCoordinator(restored) { stops++ }.resume()
        assertEquals(1,stops); assertEquals(LocalOperationState.KEY_DESTRUCTION_PENDING,journal.value)
        assertTrue(restored.blocked)
    }
    @Test fun concurrentArmAndQuiesceAreIdempotent() = runBlocking {
        val journal=Journal(); val gate=LocalOperationGate(journal); var stops=0
        val coordinator=LocalOperationCoordinator(gate) { stops++ }
        coroutineScope { repeat(20) { launch(Dispatchers.Default) { gate.armForSimulation(); coordinator.resume() } } }
        assertEquals(3,journal.writes); assertEquals(1,stops); assertTrue(coordinator.ownersQuiesced)
    }
    @Test fun operationCancellationAwaitsResourceClose() = runBlocking {
        val gate=LocalOperationGate(Journal()); val entered=CompletableDeferred<Unit>(); var closed=false
        val operation=launch { gate.operation { try { entered.complete(Unit); awaitCancellation() } finally { closed=true } } }
        entered.await(); gate.armForSimulation()
        val coordinator=LocalOperationCoordinator(gate) {}
        coordinator.resume(); assertTrue(closed); assertTrue(operation.isCompleted)
        assertTrue(coordinator.ownersQuiesced)
    }
    @Test fun ownerFailureNeverReportsCompletionAndRetryStaysFenced() = runBlocking {
        val gate=LocalOperationGate(Journal()); gate.armForSimulation(); var fail=true
        val coordinator=LocalOperationCoordinator(gate) { if(fail) error("fixture") }
        try { coordinator.resume(); fail("accepted") } catch (_: IllegalStateException) {}
        assertFalse(coordinator.ownersQuiesced); assertTrue(gate.blocked)
        fail=false; coordinator.resume(); assertTrue(coordinator.ownersQuiesced); assertTrue(gate.blocked)
    }
    @Test fun corruptJournalNeverAdvancesToDestructionAuthorization() = runBlocking {
        val journal=Journal(LocalOperationState.CORRUPT); val gate=LocalOperationGate(journal)
        LocalOperationCoordinator(gate) {}.resume()
        assertEquals(LocalOperationState.CORRUPT,journal.value); assertEquals(0,journal.writes)
    }
    @Test fun failedWriteFencesCurrentProcess() {
        val gate=LocalOperationGate(object : LocalOperationJournal {
            override fun read()=LocalOperationState.NONE
            override fun write(state: LocalOperationState) { error("fixture") }
        })
        try { gate.armForSimulation(); fail("write") } catch (_: IllegalStateException) {}
        assertEquals(LocalOperationState.CORRUPT,gate.state.value)
    }
    @Test fun synchronousOpenRaceCannotCrossCommittedArm() = runBlocking {
        val gate=LocalOperationGate(Journal()); val entered=CountDownLatch(1); val release=CountDownLatch(1)
        val open=async(Dispatchers.Default) { gate.access { entered.countDown(); check(release.await(5,TimeUnit.SECONDS)); 1 } }
        check(entered.await(5,TimeUnit.SECONDS))
        val arm=async(Dispatchers.Default) { gate.armForSimulation() }
        release.countDown(); open.await(); arm.await()
        try { gate.access { fail("late DB open") }; fail("accepted") } catch (_: LocalOperationBlocked) {}
    }
    @Test fun nestedOperationsDrainAllChildren() = runBlocking {
        val gate=LocalOperationGate(Journal()); val started=CompletableDeferred<Unit>(); var closed=0
        launch { gate.operation { gate.operation { try { started.complete(Unit); awaitCancellation() } finally { closed++ } } } }
        started.await(); gate.armForSimulation(); gate.drain(); assertEquals(1,closed)
    }
    @Test fun noSuspendedOperationIsAdmittedAfterDurableArm() = runBlocking {
        val gate=LocalOperationGate(Journal()); gate.armForSimulation(); var entered=false
        try { gate.operation { entered=true }; fail("admitted") } catch (_: LocalOperationBlocked) {}
        assertFalse(entered)
    }
    @Test fun selfDrainFailsClosedInsteadOfWaitingOnItself() = runBlocking {
        val gate=LocalOperationGate(Journal())
        gate.operation {
            gate.armForSimulation()
            try { gate.drain(); fail("self drain") } catch (_: IllegalStateException) {}
        }
        assertTrue(gate.blocked); gate.drain()
    }
}
