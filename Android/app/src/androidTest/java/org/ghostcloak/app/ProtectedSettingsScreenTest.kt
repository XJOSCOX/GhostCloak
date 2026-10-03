package org.ghostcloak.app

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.ghostcloak.app.access.*
import org.ghostcloak.app.application.*
import org.ghostcloak.app.ui.navigation.GhostApp
import org.ghostcloak.app.ui.theme.GhostCloakTheme
import org.ghostcloak.identity.RandomIdentifiers
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ProtectedSettingsScreenTest {
    @get:Rule val compose=createComposeRule()
    private class Fixture : AutoCloseable {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        var bytes: ByteArray?=null; var now=1000L; var arms=0
        val lock=AppLockController(object : LockPersistence {
            override suspend fun read()=bytes?.copyOf()
            override suspend fun write(bytes: ByteArray) {this@Fixture.bytes=bytes.copyOf()}
        },scope,{now},{1},true,{arms++})
        suspend fun ready(pin: Boolean=true, enabled: Boolean=false)=withContext(Dispatchers.Main) {
            lock.start(); lock.initialize()
            if(pin) {
                assertTrue(lock.configure(LockMode.PIN,LockTiming.MINUTES_5,"123456".toCharArray(),"123456".toCharArray()))
                if(enabled) {
                    lock.verifyPin("123456".toCharArray(),UnlockPurpose.MANAGE)
                    assertTrue(lock.configureEmergency("654321".toCharArray(),"654321".toCharArray(),charArrayOf(),true))
                }
            }
        }
        override fun close() {scope.cancel()}
    }
    private fun normalSettingsInput(pin: String="123456") {
        compose.onNodeWithText("PIN").performScrollTo().performTextInput(pin)
        compose.onNodeWithText("Open Settings").performScrollTo().performClick()
    }
    @Test fun settingsChallengeKeepsPrivateSubtreeAbsentForWrongOrSafeExitPin()=runBlocking {
        Fixture().use {f->f.ready(enabled=true); var composed=0
            compose.setContent {GhostCloakTheme {SettingsAccessGate(f.lock,{}) {composed++; Text("Protected settings")}}}
            compose.onNodeWithText("Authenticate to open Settings").assertExists()
            compose.onNodeWithText("Protected settings").assertDoesNotExist(); assertEquals(0,composed)
            normalSettingsInput("654321"); compose.waitUntil(30000) {!f.lock.state.value.busy}
            compose.onNodeWithText("Protected settings").assertDoesNotExist(); assertEquals(0,f.arms)
            normalSettingsInput("123455"); compose.waitUntil(30000) {!f.lock.state.value.busy}
            compose.onNodeWithText("Protected settings").assertDoesNotExist()
            normalSettingsInput(); compose.waitUntil(30000) {f.lock.state.value.settingsGranted}
            compose.onNodeWithText("Protected settings").assertIsDisplayed(); assertFalse(f.lock.state.value.manageGranted)
        }
    }
    @Test fun cancelReturnsToPriorScreenAndOffModeOpensWithoutFakeChallenge()=runBlocking {
        Fixture().use {f->f.ready(); var returned by mutableStateOf(false)
            compose.setContent {GhostCloakTheme {if(returned) Text("Prior screen") else SettingsAccessGate(f.lock,{returned=true}) {Text("Private settings")}}}
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithText("Prior screen").assertIsDisplayed(); assertFalse(f.lock.state.value.settingsGranted)
        }
    }
    @Test fun expiryAndBackgroundRevokeSettingsWithoutRelockingDelayedGlobalSession()=runBlocking {
        Fixture().use {f->f.ready()
            compose.setContent {GhostCloakTheme {SettingsAccessGate(f.lock,{}) {Text("Private settings")}}}
            normalSettingsInput(); compose.waitUntil(30000) {f.lock.state.value.settingsGranted}
            compose.runOnIdle {f.now+=AppLockController.SETTINGS_AUTH_MILLIS; f.lock.refreshAuthorization()}
            compose.onNodeWithText("Private settings").assertDoesNotExist(); compose.onNodeWithText("Authenticate to open Settings").assertExists()
            normalSettingsInput(); compose.waitUntil(30000) {f.lock.state.value.settingsGranted}
            compose.runOnIdle {f.lock.stop(); f.now++; f.lock.start()}
            assertTrue(f.lock.state.value.canShowContent); compose.onNodeWithText("Private settings").assertDoesNotExist()
        }
    }
    @Test fun appLockOffSettingsComposesNormally()=runBlocking {
        Fixture().use {f->f.ready(pin=false)
            compose.setContent {GhostCloakTheme {SettingsAccessGate(f.lock,{}) {Text("Ordinary settings")}}}
            compose.onNodeWithText("Ordinary settings").assertIsDisplayed(); compose.onNodeWithText("Authenticate to open Settings").assertDoesNotExist()
        }
    }
    @Test fun changeRequiresFreshNormalAndCurrentSafeExitBeforeNewPinFields()=runBlocking {
        Fixture().use {f->f.ready(enabled=true)
            withContext(Dispatchers.Main) {assertTrue(f.lock.verifyPin("123456".toCharArray(),UnlockPurpose.SETTINGS))}
            compose.setContent {GhostCloakTheme {EmergencyWipeSettingsScreen(f.lock) {}}}
            compose.onNodeWithText("On").assertExists(); compose.onNodeWithText("Change PIN").performScrollTo().performClick()
            compose.onNodeWithText("New Safe Exit PIN").assertDoesNotExist()
            compose.onNodeWithText("PIN").performScrollTo().performTextInput("123456")
            compose.waitUntil(30000) {try {compose.onNodeWithText("Confirm access").assertIsEnabled(); true} catch(_: AssertionError) {false}}
            compose.onNodeWithText("Confirm access").performScrollTo().performClick()
            compose.waitUntil(30000) {f.lock.state.value.manageGranted}
            compose.onNodeWithText("New Safe Exit PIN").assertDoesNotExist()
            compose.onNodeWithText("Current Safe Exit PIN").performScrollTo().performTextInput("654320")
            compose.waitUntil(30000) {try {compose.onNodeWithText("Confirm Safe Exit PIN").assertIsEnabled(); true} catch(_: AssertionError) {false}}
            compose.onNodeWithText("Confirm Safe Exit PIN").performScrollTo().performClick()
            compose.waitUntil(30000) {f.lock.state.value.message == "Incorrect PIN. Try again." && !f.lock.state.value.busy}; compose.onNodeWithText("New Safe Exit PIN").assertDoesNotExist()
            compose.onNodeWithText("Current Safe Exit PIN").performScrollTo().performTextInput("654321")
            compose.waitUntil(30000) {try {compose.onNodeWithText("Confirm Safe Exit PIN").assertIsEnabled(); true} catch(_: AssertionError) {false}}
            compose.onNodeWithText("Confirm Safe Exit PIN").performScrollTo().performClick()
            compose.waitUntil(30000) {f.lock.state.value.emergencyAdministrationGranted}
            compose.onNodeWithText("New Safe Exit PIN").performScrollTo().performTextInput("987654")
            compose.onNodeWithText("Confirm Safe Exit PIN").performScrollTo().performTextInput("987654")
            compose.onNode(isToggleable()).performScrollTo().performClick()
            compose.onNodeWithText("Save new PIN").performScrollTo().performClick()
            compose.waitUntil(30000) {!f.lock.state.value.busy && !f.lock.state.value.manageGranted}
            withContext(Dispatchers.Main) {
                f.lock.verifyPin("123456".toCharArray(),UnlockPurpose.MANAGE)
                assertFalse(f.lock.verifyEmergencyAdministration("654321".toCharArray()))
                assertTrue(f.lock.verifyEmergencyAdministration("987654".toCharArray()))
            }
            assertEquals(0,f.arms)
        }
    }
    @Test fun realNavigationProtectsSettingsBlockedRowsAndReauthenticatesCriticalPage()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val runtime=AppRuntime(context,"",RandomIdentifiers.create())
        runtime.use {it.create("Synthetic")}
        val owner=ViewModelStore(); lateinit var model: GhostViewModel
        Fixture().use {f->f.ready()
            withContext(Dispatchers.Main) {model=GhostViewModel(context.applicationContext as android.app.Application,runtime); owner.put("settings",model)}
            try {
                compose.setContent {GhostCloakTheme {AppLockGate(f.lock) {GhostApp(model)}}}
                compose.waitUntil(20000) {model.state.value.ready && !model.state.value.loading}
                compose.onNodeWithContentDescription("Profiles").performClick()
                compose.onNodeWithContentDescription("Settings").performClick()
                compose.onNodeWithText("Privacy & Security").assertDoesNotExist()
                compose.onNodeWithContentDescription("Back").performClick()
                compose.onNodeWithText("Your profile").assertExists()
                compose.onNodeWithContentDescription("Settings").performClick(); normalSettingsInput()
                compose.waitUntil(30000) {f.lock.state.value.settingsGranted}
                compose.onNodeWithText("Blocked contacts").performScrollTo().performClick()
                compose.onNodeWithText("Authenticate to open Settings").assertDoesNotExist()
                compose.onNodeWithContentDescription("Back").performClick()
                compose.onNodeWithText("App lock",substring=false).performScrollTo().performClick()
                compose.onNodeWithText("Confirm your current unlock method to change app lock.").assertExists()
                compose.onNodeWithContentDescription("Back").performClick()
                compose.onNodeWithContentDescription("Chat").performClick()
                compose.onNodeWithContentDescription("Settings").performClick()
                compose.onNodeWithText("Authenticate to open Settings").assertExists()
            } finally {withContext(Dispatchers.Main) {owner.clear()}; runtime.close()}
            Unit
        }
    }
}
