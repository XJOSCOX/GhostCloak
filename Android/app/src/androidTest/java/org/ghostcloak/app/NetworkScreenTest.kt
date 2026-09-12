package org.ghostcloak.app

import android.app.Application
import androidx.compose.ui.graphics.asAndroidBitmap
import java.io.File
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import org.ghostcloak.app.application.*
import org.ghostcloak.app.ui.components.NetworkActions
import org.ghostcloak.app.ui.navigation.GhostApp
import org.ghostcloak.app.ui.screens.*
import org.ghostcloak.app.ui.theme.GhostCloakTheme
import org.ghostcloak.identity.RandomIdentifiers
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NetworkScreenTest {
    @get:Rule val compose = createComposeRule()
    @Test fun networkFirstLaunchModelPreservesIdentityOnFailureAndConnectsOnRetry() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val api = SyntheticNetwork().apply { offline = true }
        val runtime = AppRuntime(context, "https://fixture.invalid", "ui-${RandomIdentifiers.create()}", api)
        lateinit var model: GhostViewModel
        val owner = ViewModelStore()
        instrumentation.runOnMainSync {
            model = GhostViewModel(context.applicationContext as Application, runtime)
            assertTrue(model.state.value.networkConfigured)
            owner.put("network", model)
        }
        try {
            compose.setContent { GhostCloakTheme { GhostApp(model) } }
            compose.waitUntil(20000) { model.state.value.ready && !model.state.value.loading }
            compose.onNodeWithText("Create local identity").assertDoesNotExist()
            compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
                File(context.getExternalFilesDir(null), "e0-first-launch.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            }
            compose.onNodeWithText("Encrypted messaging via Ghost Cloak staging", substring = true).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Username").performScrollTo().performTextInput("alice")
            compose.onNodeWithText("Create identity").performScrollTo().performClick()
            compose.waitUntil(20000) { model.state.value.identity != null && !model.state.value.loading }
            val original = model.state.value.identity!!
            compose.onNodeWithText("Offline · Try Sync").assertIsDisplayed()
            assertEquals(0, api.registrations)
            api.offline = false
            compose.onNodeWithText("Connect to Ghost Cloak").performClick()
            compose.waitUntil(20000) { model.state.value.networkConnected && !model.state.value.loading }
            assertEquals(1, api.registrations)
            assertArrayEquals(original.publicKey, model.state.value.identity!!.publicKey)
            assertEquals(original.deviceId, model.state.value.identity!!.deviceId)
            compose.onAllNodesWithText("Chats").onFirst().assertIsDisplayed()
            compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
                File(context.getExternalFilesDir(null), "e0-connected.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            }
            compose.onNodeWithContentDescription("Settings").performClick()
            compose.onNodeWithText("Sync").performScrollTo().performClick()
            compose.waitUntil(20000) { !model.state.value.loading }
            assertNull(model.state.value.error)
        } finally { instrumentation.runOnMainSync { owner.clear() }; runtime.close() }
    }
    @Test fun usernameLookupIsPrimaryEvenBeforeReconnection() {
        var lookedUp: String? = null
        compose.setContent { GhostCloakTheme { AddContactScreen(
            AppState(loading = false, ready = true, networkConfigured = true, networkStatus = NetworkStatus.NEEDS_CONNECT),
            {}, {}, { lookedUp = it }, {}) } }
        compose.onNodeWithText("Exact username").performTextInput("bob")
        compose.onNodeWithText("Find and add contact").performClick()
        assertEquals("bob", lookedUp)
        compose.onNodeWithText("Paste contact card").assertDoesNotExist()
    }
    @Test fun networkStatusAndForegroundActionsReflectOperationState() {
        val state = mutableStateOf(AppState(loading = false, networkConfigured = true, networkStatus = NetworkStatus.NEEDS_CONNECT))
        var connects = 0; var syncs = 0
        compose.setContent { GhostCloakTheme { NetworkActions(state.value, { connects++ }, { syncs++ }) } }
        compose.onNodeWithText("Connect to Ghost Cloak").performClick()
        compose.onNodeWithText("Sync").performClick()
        assertEquals(1, connects); assertEquals(1, syncs)
        for ((status, text) in listOf(NetworkStatus.CONNECTING to "Connecting…", NetworkStatus.SYNCING to "Syncing…")) {
            compose.runOnIdle { state.value = state.value.copy(loading = true, networkStatus = status) }
            compose.onNodeWithText(text).assertIsDisplayed()
            compose.onNodeWithText("Sync").assertIsNotEnabled()
        }
        compose.runOnIdle { state.value = state.value.copy(loading = false, networkStatus = NetworkStatus.ERROR, networkRequiresConnect = false) }
        compose.onNodeWithText("Needs attention · Try Sync").assertIsDisplayed()
        compose.onNodeWithText("Connect to Ghost Cloak").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(networkStatus = NetworkStatus.OFFLINE) }
        compose.onNodeWithText("Offline · Try Sync").assertIsDisplayed()
        compose.onNodeWithText("Connect to Ghost Cloak").assertDoesNotExist()
        compose.onNodeWithText("Sync").assertIsEnabled()
        compose.runOnIdle { state.value = state.value.copy(networkStatus = NetworkStatus.CONNECTED) }
        compose.onNodeWithText("Connected").assertIsDisplayed()
        compose.onNodeWithText("Connect to Ghost Cloak").assertDoesNotExist()
    }
}
