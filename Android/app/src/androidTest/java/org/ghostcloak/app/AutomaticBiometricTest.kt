package org.ghostcloak.app

import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.*
import org.ghostcloak.app.access.*
import org.ghostcloak.app.ui.theme.GhostCloakTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AutomaticBiometricTest {
    @get:Rule val compose = createComposeRule()
    @Test fun readyHostPromptsOnceDespiteThemeRefreshAndNotificationRouteChange() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val persistence = object : LockPersistence {
            override suspend fun read() = LockConfiguration(LockMode.BIOMETRIC).encode()
            override suspend fun write(bytes: ByteArray) = Unit
        }
        val controller = AppLockController(persistence, scope, android.os.SystemClock::elapsedRealtime, { 1 })
        val ready = mutableStateOf(false); val dark = mutableStateOf(false); val route = mutableStateOf("conversation")
        val prompts = mutableListOf<Long>()
        withContext(Dispatchers.Main) { controller.start(); controller.initialize() }
        try {
            compose.setContent { GhostCloakTheme(darkTheme = dark.value) {
                AppLockGate(controller) { Text(if (route.value == "chats") "Chats after unlock" else "Conversation after unlock") }
                // Same lifecycle-ready effect as the production BiometricButton; replace only OS presentation.
                AutomaticBiometricPrompt(controller, available = true, hostReady = ready.value) { prompts.add(it) }
            } }
            compose.waitForIdle(); assertTrue(prompts.isEmpty())
            compose.runOnIdle { ready.value = true }
            compose.waitUntil { prompts.size == 1 }
            compose.runOnIdle { controller.cancelBiometric(prompts.single()); dark.value = true; route.value = "chats" }
            compose.waitForIdle(); assertEquals(1, prompts.size)
            compose.onNodeWithText("Chats after unlock").assertDoesNotExist()
            compose.runOnIdle { controller.stop(true); controller.start() }
            compose.waitForIdle(); assertEquals(1, prompts.size)
            compose.runOnIdle { controller.stop(); ready.value = false }
            compose.runOnIdle { controller.start(); ready.value = true }
            compose.waitUntil { prompts.size == 2 }
            withContext(Dispatchers.Main) { assertTrue(controller.completeBiometric(prompts.last())) }
            compose.onNodeWithText("Chats after unlock").assertIsDisplayed()
            compose.waitForIdle(); assertEquals(2, prompts.size)
        } finally { scope.cancel() }
    }
}
