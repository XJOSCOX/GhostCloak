package org.ghostcloak.app

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.*
import org.ghostcloak.app.access.*
import org.ghostcloak.app.application.*
import org.ghostcloak.app.ui.screens.ConversationScreen
import org.ghostcloak.app.ui.theme.GhostCloakTheme
import org.ghostcloak.identity.SessionLifecycle
import org.ghostcloak.messaging.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class DisappearingScreenTest {
    @get:Rule val compose = createComposeRule()
    @Test fun unlockingAfterDelayedCleanupNeverComposesExpiredPlaintext() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val persistence = object : LockPersistence {
            override suspend fun read() = LockConfiguration(LockMode.BIOMETRIC).encode()
            override suspend fun write(bytes: ByteArray) = Unit
        }
        val lock = AppLockController(persistence, scope, android.os.SystemClock::elapsedRealtime, { 1 })
        var wall = 1000L; var elapsed = 1000L
        val clock = ExpiryClock({ wall }, { elapsed }, { 1 })
        val stale = AppState(loading = false, networkStatus = NetworkStatus.CONNECTED, messages = listOf(
            Message("expired", "device", Direction.INCOMING, "Never render this expired body", 1,
                MessageState.RECEIVED, expiry = ExpiryDeadline.start(30, clock.now()))))
        val contact = ContactStatus(Contact("contact", "account", "Alice", "device"), null, SessionLifecycle.ACTIVE)
        withContext(Dispatchers.Main) { lock.start(); lock.initialize() }
        try {
            compose.setContent { GhostCloakTheme { AppLockGate(lock) {
                ConversationScreen(stale.withoutExpired(clock.now()), contact, {}, {}, { _, _ -> }, {})
            } } }
            compose.onNodeWithText("Unlock Ghost Cloak").assertExists()
            compose.onNodeWithText("Never render this expired body").assertDoesNotExist()
            wall += 30_000; elapsed += 30_000
            withContext(Dispatchers.Main) { assertTrue(lock.completeBiometric(lock.beginBiometric(UnlockPurpose.UNLOCK)!!)) }
            compose.onNodeWithText("Alice").assertExists()
            compose.onNodeWithText("Never render this expired body").assertDoesNotExist()
        } finally { scope.cancel() }
    }
    @Test fun selectorShowsFixedTimersAndPolicyRowsAreNotChatBubbles() {
        val seconds = mutableStateOf(0)
        val contact = ContactStatus(Contact("contact", "account", "Alice", "device"), null, SessionLifecycle.ACTIVE)
        val row = Message("event", "device", Direction.INCOMING, "Disappearing messages set to 1 hour", 1,
            MessageState.RECEIVED, policyEvent = true)
        compose.setContent { GhostCloakTheme {
            ConversationScreen(AppState(loading = false, networkConfigured = true, networkStatus = NetworkStatus.CONNECTED,
                messages = listOf(row), disappearingPolicies = mapOf("device" to seconds.value)),
                contact, {}, {}, { _, _ -> }, {}, disappearing = { seconds.value = it })
        } }
        compose.onNodeWithText(row.body).performTouchInput { longClick() }
        compose.onNodeWithText("Delete").assertDoesNotExist()
        compose.onNodeWithContentDescription("Conversation options").performClick()
        compose.onNodeWithText("Disappearing messages · Off").performClick()
        for (timer in DisappearingTimer.entries) compose.onNodeWithText(if (timer.seconds == 0) "Off ✓" else timer.label).assertExists()
        compose.onNodeWithText("1 hour").performClick(); compose.runOnIdle { assertEquals(3600, seconds.value) }
        compose.onNodeWithContentDescription("Conversation options").performClick()
        compose.onNodeWithText("Disappearing messages · 1 hour").assertExists()
    }
}
