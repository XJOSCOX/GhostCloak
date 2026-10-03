package org.ghostcloak.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.ghostcloak.app.access.*
import org.ghostcloak.app.application.RecoveryStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlinx.coroutines.*

class SafeExitRecoveryScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun retryTapInvokesCallbackAndFailureRemainsRetryableUntilOnboarding() {
        val state = mutableStateOf(LocalOperationState.KEY_DESTRUCTION_PENDING)
        val status = mutableStateOf(RecoveryStatus.IDLE)
        var attempts = 0
        compose.setContent { MaterialTheme {
            if (state.value != LocalOperationState.NONE)
                SafeExitRecoveryScreen(state.value,status.value) { attempts++ }
            else androidx.compose.material3.Text("Fresh onboarding")
        } }
        compose.onNodeWithText("Ghost Cloak is completing a secure local operation.").assertIsDisplayed()
        compose.onNodeWithText("Retry").performClick()
        assertEquals(1,attempts)
        compose.runOnIdle { status.value=RecoveryStatus.FAILED }
        compose.onNodeWithText("Could not finish. You can retry.").assertIsDisplayed()
        compose.onNodeWithText("Retry").performClick()
        assertEquals(2,attempts)
        compose.runOnIdle { status.value=RecoveryStatus.RUNNING }
        compose.onNodeWithText("Retry").assertIsNotEnabled()
        compose.runOnIdle { state.value=LocalOperationState.NONE }
        compose.onNodeWithText("Fresh onboarding").assertIsDisplayed()
        compose.onNodeWithText("Retry").assertDoesNotExist()
    }

    @Test fun persistedDestructionAfterProcessRecreationRetryReconstructsCoordinatorAndOpensOnboarding() {
        val journal = object : LocalOperationJournal {
            var value=LocalOperationState.KEY_DESTRUCTION_PENDING
            override fun read()=value
            override fun write(state: LocalOperationState) { value=state }
            override fun clearCompleted() { check(value==LocalOperationState.COMPLETE); value=LocalOperationState.NONE }
        }
        val oldProcessGate=LocalOperationGate(journal)
        assertTrue(oldProcessGate.blocked)
        // No old gate/coordinator is passed to the recreated screen.
        val newProcessGate=LocalOperationGate(journal)
        var coordinators=0
        var keyDeletes=0
        var storageDeletes=0
        val engine=object : LocalDestruction {
            override suspend fun destroyKeys() { keyDeletes++ }
            override suspend fun cleanupStorage() { storageDeletes++ }
            override suspend fun verifyFresh()=Unit
        }
        val recovery=SafeExitRecovery(newProcessGate,journal,{
            coordinators++
            LocalOperationCoordinator(newProcessGate,engine) {}
        },{}, {})
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val status=mutableStateOf(RecoveryStatus.IDLE)
        try {
            compose.setContent { MaterialTheme {
                val state=newProcessGate.state.collectAsState().value
                if(state==LocalOperationState.NONE) androidx.compose.material3.Text("Fresh onboarding")
                else SafeExitRecoveryScreen(state,status.value) {
                    status.value=RecoveryStatus.RUNNING
                    scope.launch { status.value=if(recovery.resume()==SafeExitRetryResult.COMPLETED)
                        RecoveryStatus.IDLE else RecoveryStatus.FAILED }
                }
            } }
            compose.onNodeWithText("Retry").performClick()
            compose.waitUntil(10_000) { newProcessGate.state.value==LocalOperationState.NONE }
            compose.onNodeWithText("Fresh onboarding").assertIsDisplayed()
            assertEquals(1,coordinators)
            assertEquals(1,keyDeletes)
            assertEquals(1,storageDeletes)
        } finally { scope.cancel() }
    }
}
