package org.ghostcloak.app

import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.*
import org.junit.Assert.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.ghostcloak.app.ui.components.ErrorNotice
import org.ghostcloak.app.ui.theme.GhostCloakTheme
import org.junit.Rule
import org.junit.Test

class ErrorNoticeTest {
    @get:Rule val compose = createComposeRule()
    @Test fun successfulForegroundSyncClearsTransientActionError() = runBlocking {
        val app = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as android.app.Application
        val api = SyntheticNetwork()
        val runtime = org.ghostcloak.app.application.AppRuntime(app, "https://fixture.invalid", org.ghostcloak.identity.RandomIdentifiers.create(), api)
        val store = androidx.lifecycle.ViewModelStore()
        var loop: Job? = null
        try {
            runtime.use { runtime.create(it, "alice") }
            val model = withContext(Dispatchers.Main) { org.ghostcloak.app.application.GhostViewModel(app, runtime).also { store.put("test", it) } }
            api.offline = true
            withContext(Dispatchers.Main) { model.syncNetwork() }.join()
            assertNotNull(model.state.value.error)
            assertTrue(model.state.value.errorTransient)
            assertFalse(model.state.value.errorImportant)
            api.offline = false
            loop = launch(Dispatchers.Main) { model.foregroundSync() }
            withTimeout(5000) { while (model.state.value.error != null) delay(25) }
            assertFalse(model.state.value.errorTransient)
            assertEquals(org.ghostcloak.app.application.NetworkStatus.CONNECTED, model.state.value.networkStatus)
        } finally {
            loop?.cancelAndJoin()
            withContext(Dispatchers.Main) { store.clear() }
            runtime.close()
        }
    }
    @Test fun ordinaryNoticeExpiresAfterFiveSeconds() {
        compose.mainClock.autoAdvance = false
        compose.setContent { GhostCloakTheme { ErrorNotice("Username not found") } }
        compose.onNodeWithText("Username not found").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(4500)
        compose.onNodeWithContentDescription("Dismiss notice").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithText("Username not found").assertDoesNotExist()
        compose.onNodeWithContentDescription("Dismiss notice").assertDoesNotExist()
    }
    @Test fun ordinaryNoticeCanBeDismissedButCriticalFailureStaysVisible() {
        val important = mutableStateOf(false)
        val message = mutableStateOf("Temporary connection problem")
        compose.setContent { GhostCloakTheme { ErrorNotice(message.value, important.value) } }
        compose.onNodeWithText("Unable to continue").assertDoesNotExist()
        compose.onNodeWithText(message.value).assertIsDisplayed()
        compose.onNodeWithContentDescription("Dismiss notice").performClick()
        compose.onNodeWithText(message.value).assertDoesNotExist()
        compose.runOnIdle { message.value = "Encrypted storage is unavailable"; important.value = true }
        compose.onNodeWithText("Action needed").assertIsDisplayed()
        compose.onNodeWithText(message.value).assertIsDisplayed()
        compose.onNodeWithContentDescription("Dismiss notice").assertDoesNotExist()
    }
}
