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

class LocalDeletionScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun longPressAndClearRequireConfirmationAndLockedRootExposesNeither() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val persistence = object : LockPersistence {
            var bytes: ByteArray? = null
            override suspend fun read() = bytes
            override suspend fun write(bytes: ByteArray) { this.bytes = bytes.copyOf() }
        }
        val lock = AppLockController(persistence, scope, android.os.SystemClock::elapsedRealtime, { 1 })
        val contact = ContactStatus(Contact("contact", "account", "Alice", "device"), null, SessionLifecycle.ACTIVE)
        val messages = mutableStateOf(listOf(
            Message("one", "device", Direction.INCOMING, "First local message", 1, MessageState.RECEIVED),
            Message("two", "device", Direction.OUTGOING, "Second local message", 2, MessageState.DELIVERED)))
        var deletes = 0; var clears = 0
        withContext(Dispatchers.Main) { lock.start(); lock.initialize() }
        try {
            compose.setContent { GhostCloakTheme { AppLockGate(lock) {
                ConversationScreen(AppState(loading = false, messages = messages.value, networkStatus = NetworkStatus.CONNECTED),
                    contact, {}, {}, { _, _ -> }, { id -> deletes++; messages.value = messages.value.filterNot { it.localId == id } },
                    clear = { clears++; messages.value = emptyList() })
            } } }
            compose.onNodeWithText("First local message").performClick()
            compose.onNodeWithText("Delete").assertDoesNotExist()
            compose.onNodeWithText("First local message").performTouchInput { longClick() }
            compose.onNodeWithText("Delete").performClick()
            compose.onNodeWithText("This removes the message from this device only.").assertIsDisplayed()
            compose.onNodeWithText("Cancel").performClick(); assertEquals(0, deletes)
            compose.onNodeWithText("First local message").performTouchInput { longClick() }
            compose.onNodeWithText("Delete").performClick()
            compose.onNodeWithText("Delete").performClick()
            compose.onNodeWithText("First local message").assertDoesNotExist()
            compose.onNodeWithText("Second local message").assertExists(); assertEquals(1, deletes)
            compose.onNodeWithContentDescription("Conversation options").performClick()
            compose.onNodeWithText("Clear conversation").performClick()
            compose.onNodeWithText("This removes all messages with this contact from this device. The contact will remain.").assertIsDisplayed()
            compose.onNodeWithText("Cancel").performClick(); assertEquals(0, clears)
            compose.onNodeWithContentDescription("Conversation options").performClick()
            compose.onNodeWithText("Clear conversation").performClick()
            compose.onNodeWithText("Clear").performClick(); assertEquals(1, clears)
            compose.onNodeWithText("Second local message").assertDoesNotExist()
            compose.onNodeWithText("Alice").assertExists()
            withContext(Dispatchers.Main) {
                lock.configure(LockMode.PIN, LockTiming.IMMEDIATE, "824619".toCharArray(), "824619".toCharArray())
                lock.stop(); lock.start()
            }
            compose.onNodeWithText("Unlock Ghost Cloak").assertExists()
            compose.onNodeWithContentDescription("Conversation options").assertDoesNotExist()
            compose.onNodeWithText("Delete").assertDoesNotExist()
            compose.onNodeWithText("Clear conversation").assertDoesNotExist()
        } finally { scope.cancel() }
    }
}
