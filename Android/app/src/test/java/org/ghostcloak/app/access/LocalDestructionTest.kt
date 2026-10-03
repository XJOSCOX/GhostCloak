package org.ghostcloak.app.access

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LocalDestructionTest {
    private class Crash : RuntimeException()
    private class Journal(var value: LocalOperationState=LocalOperationState.NONE) : LocalOperationJournal {
        var crashAfter: LocalOperationState?=null; var clearFails=false
        val transitions=mutableListOf<LocalOperationState>()
        override fun read()=value
        override fun write(state: LocalOperationState) {value=state; transitions+=state; if(crashAfter==state) throw Crash()}
        override fun clearCompleted() {check(value==LocalOperationState.COMPLETE); if(clearFails) throw Crash(); value=LocalOperationState.NONE}
    }
    private class Engine : LocalDestruction {
        var key=false; var files=false; var failKeys=false; var failFiles=false; var deletes=0
        override suspend fun destroyKeys() {deletes++; if(failKeys) throw Crash(); key=true}
        override suspend fun cleanupStorage() {check(key); if(failFiles) throw Crash(); files=true}
        override suspend fun verifyFresh() {check(key && files)}
    }
    @Test fun completeRequiresQuiesceKeysCleanupVerificationThenReopens()=runBlocking {
        val j=Journal(); val gate=LocalOperationGate(j); val engine=Engine(); var stops=0; var prepares=0
        gate.armAfterCredential()
        val c=LocalOperationCoordinator(gate,engine,{check(gate.blocked && engine.key && engine.files); prepares++}) {stops++}
        c.resume(); assertFalse(gate.blocked); assertEquals(1,stops); assertEquals(1,prepares)
        assertEquals(LocalOperationState.entries.filter {it!=LocalOperationState.NONE && it!=LocalOperationState.CORRUPT},j.transitions)
        c.resume(); assertEquals(1,engine.deletes)
    }
    @Test fun restartAtEveryDurableTransitionCompletesWithoutOpeningOldState()=runBlocking {
        for(stage in LocalOperationState.entries.filter {it!=LocalOperationState.NONE && it!=LocalOperationState.CORRUPT}) {
            val j=Journal(); val engine=Engine(); var stops=0
            val gate=LocalOperationGate(j); gate.armAfterCredential()
            if(stage!=LocalOperationState.ARMED) {
                j.crashAfter=stage
                try {LocalOperationCoordinator(gate,engine) {stops++}.resume(); fail("fault missed")} catch(_: Crash) {}
            }
            assertTrue(gate.blocked); assertEquals(stage,j.value)
            j.crashAfter=null
            val restarted=LocalOperationGate(j)
            LocalOperationCoordinator(restarted,engine) {stops++}.resume()
            assertFalse(restarted.blocked); assertTrue(engine.key && engine.files); assertTrue(stops>=1)
        }
    }
    @Test fun quiesceFailureCannotCrossIrreversibleBoundary()=runBlocking {
        val j=Journal(); val gate=LocalOperationGate(j); val engine=Engine(); gate.armAfterCredential()
        try {LocalOperationCoordinator(gate,engine) {throw Crash()}.resume(); fail("accepted")} catch(_: Crash) {}
        assertEquals(LocalOperationState.QUIESCING,j.value); assertEquals(0,engine.deletes); assertTrue(gate.blocked)
    }
    @Test fun corruptJournalNeverAuthorizesDeletion()=runBlocking {
        val j=Journal(LocalOperationState.CORRUPT); val gate=LocalOperationGate(j); val engine=Engine()
        LocalOperationCoordinator(gate,engine) {}.resume(); assertEquals(0,engine.deletes); assertTrue(gate.blocked)
    }
    @Test fun keyFailureRetainsPendingAndRetryIsPossible()=runBlocking {
        val j=Journal(); val gate=LocalOperationGate(j); val engine=Engine().apply {failKeys=true}; gate.armAfterCredential()
        val c=LocalOperationCoordinator(gate,engine) {}
        try {c.resume(); fail("accepted")} catch(_: Crash) {}
        assertEquals(LocalOperationState.KEY_DESTRUCTION_PENDING,j.value); assertFalse(engine.files)
        engine.failKeys=false; c.resume(); assertFalse(gate.blocked)
    }
    @Test fun fileFailureRetainsFenceAfterCryptographicBoundary()=runBlocking {
        val j=Journal(); val gate=LocalOperationGate(j); val engine=Engine().apply {failFiles=true}; gate.armAfterCredential()
        val c=LocalOperationCoordinator(gate,engine) {}
        try {c.resume(); fail("accepted")} catch(_: Crash) {}
        assertTrue(engine.key); assertEquals(LocalOperationState.STORAGE_CLEANUP_PENDING,j.value); assertTrue(gate.blocked)
        engine.failFiles=false; c.resume(); assertFalse(gate.blocked)
    }
    @Test fun completeMarkerDoesNotBypassFreshVerification()=runBlocking {
        val j=Journal(LocalOperationState.COMPLETE); val gate=LocalOperationGate(j); val engine=Engine()
        try {LocalOperationCoordinator(gate,engine) {}.resume(); fail("accepted")} catch(_: IllegalStateException) {}
        assertTrue(gate.blocked); assertEquals(LocalOperationState.COMPLETE,j.value)
    }
    @Test fun failedJournalResetCannotOpenCurrentProcessAndRestartRechecks()=runBlocking {
        val j=Journal().apply {clearFails=true}; val gate=LocalOperationGate(j); val engine=Engine(); gate.armAfterCredential()
        try {LocalOperationCoordinator(gate,engine) {}.resume(); fail("accepted")} catch(_: Crash) {}
        assertEquals(LocalOperationState.COMPLETE,j.value); assertTrue(gate.blocked)
        j.clearFails=false; val restarted=LocalOperationGate(j)
        LocalOperationCoordinator(restarted,engine) {}.resume(); assertFalse(restarted.blocked)
    }
    @Test fun secondWipeQuiescesFreshOwnersAgain()=runBlocking {
        val j=Journal(); val gate=LocalOperationGate(j); val engine=Engine(); var stops=0
        val c=LocalOperationCoordinator(gate,engine) {stops++}
        repeat(2) {engine.key=false; engine.files=false; gate.armAfterCredential(); c.resume()}
        assertEquals(2,stops); assertEquals(2,engine.deletes)
    }
    @Test fun noEngineMeansExistingNonDestructivePreview()=runBlocking {
        val j=Journal(); val gate=LocalOperationGate(j); gate.armAfterCredential()
        LocalOperationCoordinator(gate) {}.resume(); assertTrue(gate.blocked)
        assertEquals(LocalOperationState.KEY_DESTRUCTION_PENDING,j.value)
        assertFalse(org.ghostcloak.app.BuildConfig.EMERGENCY_WIPE_DESTRUCTIVE_READY)
    }
    @Test fun aliasOwnershipIsExactAndDoesNotMatchOtherNamespaces() {
        assertTrue(AndroidLocalDestruction.ownedAlias("ghost-cloak.db.local"))
        assertTrue(AndroidLocalDestruction.ownedAlias("ghost-cloak.db.demo-alice"))
        assertTrue(AndroidLocalDestruction.ownedAlias("ghostcloak.auth."+"a".repeat(64)+".12345678-1234-1234-1234-123456789abc"))
        for(alias in listOf("other-app.key","ghost-cloak.other","ghost-cloak.db../outside","ghost-cloak.db.LOCAL","ghostcloak.auth.bad","prefix.ghost-cloak.db.local")) assertFalse(AndroidLocalDestruction.ownedAlias(alias))
    }
}
