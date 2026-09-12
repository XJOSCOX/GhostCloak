package org.ghostcloak.app

import android.view.WindowManager
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ActivityScenario
import kotlinx.coroutines.*
import org.ghostcloak.app.access.*
import org.ghostcloak.app.application.AndroidLocalNotifications
import org.ghostcloak.app.application.GhostApplication
import org.ghostcloak.app.ui.theme.GhostCloakTheme
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AppLockScreenTest {
    @get:Rule val compose = createComposeRule()
    @Test fun lockedRootDoesNotComposePrivateRoutesAndNotificationRequestCannotBypassIt() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val persistence = object : LockPersistence {
            var bytes: ByteArray? = null
            override suspend fun read() = bytes?.copyOf()
            override suspend fun write(bytes: ByteArray) { this.bytes = bytes.copyOf() }
        }
        val controller = AppLockController(persistence, scope, android.os.SystemClock::elapsedRealtime, { 1 })
        withContext(Dispatchers.Main) {
            controller.start(); controller.initialize()
            controller.configure(LockMode.PIN, LockTiming.IMMEDIATE, "824619".toCharArray(), "824619".toCharArray())
            controller.stop(); controller.start()
        }
        var composed = false; val route = mutableIntStateOf(0)
        try {
            compose.setContent { GhostCloakTheme { AppLockGate(controller) {
                composed = true
                Text(if (route.intValue == 0) "Private conversation preview" else "Private Chats")
            } } }
            compose.onNodeWithText("Unlock Ghost Cloak").assertIsDisplayed()
            compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                java.io.File(context.getExternalFilesDir(null), "app-lock-screen.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            compose.onNodeWithText("Private conversation preview").assertDoesNotExist(); assertFalse(composed)
            compose.runOnIdle { route.intValue++ } // Same root destination change requested by a notification.
            compose.onNodeWithText("Private Chats").assertDoesNotExist(); assertFalse(composed)
            compose.onNodeWithText("PIN").performTextInput("824619")
            compose.onNodeWithText("Unlock", useUnmergedTree = true).performClick()
            compose.waitUntil(20000) { controller.state.value.canShowContent }
            compose.onNodeWithText("Private Chats").assertIsDisplayed()
            compose.runOnIdle { controller.stop() }
            compose.onNodeWithText("Private Chats").assertDoesNotExist()
        } finally { scope.cancel() }
    }

    @Test fun settingsEnrollPinAndTimingThroughSharedPageThenRequireAuthentication() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val persistence = object : LockPersistence {
            var bytes: ByteArray? = null
            override suspend fun read() = bytes?.copyOf()
            override suspend fun write(bytes: ByteArray) { this.bytes = bytes.copyOf() }
        }
        val controller = AppLockController(persistence, scope, android.os.SystemClock::elapsedRealtime, { 1 })
        withContext(Dispatchers.Main) { controller.start(); controller.initialize() }
        var saved = false
        try {
            compose.setContent { GhostCloakTheme { AppLockGate(controller) { AppLockSettingsScreen(controller) { saved = true } } } }
            compose.onNodeWithText("PIN", substring = false).performClick()
            compose.onNodeWithText("After 30 seconds").performScrollTo().performClick()
            compose.onNodeWithText("New PIN").performScrollTo().performTextInput("824619")
            compose.onNodeWithText("Confirm PIN").performScrollTo().performTextInput("824619")
            compose.onNodeWithText("Save app lock").performScrollTo().performClick()
            compose.waitUntil(20000) { saved }
            assertEquals(LockMode.PIN, controller.state.value.mode)
            assertEquals(LockTiming.SECONDS_30, controller.state.value.timing)
            compose.onNodeWithText("Confirm your current unlock method to change app lock.").assertExists()
        } finally { scope.cancel() }
    }

    @Test fun realActivityProtectsFirstFrameAndRecreationIncludingNotificationRoot() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val controller = (context.applicationContext as GhostApplication).appLock
        ActivityScenario.launch<MainActivity>(AndroidLocalNotifications.rootIntent(context)).use { scenario ->
            try {
                withTimeout(10000) { while (!controller.state.value.canShowContent) delay(25) }
                scenario.onActivity { assertTrue(it.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0) }
                withContext(Dispatchers.Main) { assertTrue(controller.configure(LockMode.PIN, LockTiming.IMMEDIATE, "824619".toCharArray(), "824619".toCharArray())) }
                scenario.recreate()
                assertTrue(controller.state.value.canShowContent) // Configuration change is not a background timeout.
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
                assertFalse(controller.state.value.canShowContent)
                context.startActivity(AndroidLocalNotifications.rootIntent(context))
                compose.onNodeWithText("Unlock Ghost Cloak").assertIsDisplayed()
                compose.onNodeWithText("Create identity").assertDoesNotExist()
                scenario.onActivity { assertTrue(it.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0) }
            } finally {
                withContext(Dispatchers.Main) {
                    controller.start()
                    if (controller.state.value.mode == LockMode.PIN) {
                        assertTrue(controller.verifyPin("824619".toCharArray()))
                        assertTrue(controller.verifyPin("824619".toCharArray(), UnlockPurpose.MANAGE))
                        assertTrue(controller.configure(LockMode.OFF, LockTiming.IMMEDIATE, charArrayOf(), charArrayOf()))
                    }
                }
            }
        }
    }
}
